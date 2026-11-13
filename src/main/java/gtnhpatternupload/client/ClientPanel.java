package gtnhpatternupload.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiScreenEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import appeng.container.implementations.ContainerPatternTerm;
import appeng.items.misc.ItemEncodedPattern;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gtnhpatternupload.Config;
import gtnhpatternupload.Net;
import gtnhpatternupload.server.Candidates;

/**
 * Боковая панель у терминала шаблонов в стиле окон AE2. Всё на событиях Forge и обычных кнопках: интерфейс AE2 не
 * патчится. NEI уступает место под панелью через INEIGuiAdapter (см. NeiCompat).
 */
public class ClientPanel {

    private static final int MAX_W = 204;
    private static final int MIN_W = 150;
    /** Отступ справа от терминала: колонка ячеек вида/улучшений AE2. */
    private static final int RIGHT_GAP = 28;
    /** Отступ слева от терминала: колонка кнопок сортировки/вида AE2. */
    private static final int LEFT_GAP = 54;
    /** Строка кандидата: имя машины, подпись CRIB (форма/схема), признаки. */
    private static final int ROW_H = 34;
    private static final int ROWS = 5;
    /** Строка конфликта: машина со старым шаблоном и кнопка «Извлечь». */
    private static final int CONF_H = 24;
    private static final int CONF_ROWS = 3;
    private static final int HEADER = 22;
    private static final int FOOTER = 22;
    /** Текущая высота подвала (строка состояния может переноситься). */
    private int footer = FOOTER;

    private int statusWidth() {
        return pw - 16 - (up != null && up.visible ? 50 : 0);
    }

    private static final int BASE_ID = 0x6A5000;
    private static final RenderItem ITEM_RENDER = new RenderItem();

    private static final int GUI_FILL = 0xFFC6C6C6;
    private static final int GUI_TEXT = 0x404040;
    private static final int GUI_TEXT_DIM = 0x5A5A5A;
    private static final int GUI_TEXT_BAD = 0x9A1010;

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    // Терминал и раскладка
    private GuiContainer gui;
    private int px, py, pw, ph;
    private boolean visible;
    private Background bg;
    private final List<RowButton> rows = new ArrayList<>();
    private final List<ConflictRow> confRows = new ArrayList<>();
    private final List<GuiButton> extractButtons = new ArrayList<>();
    private GuiButton up, down;
    // Вычисленные в relayout() координаты блоков
    private int listY, noteY, confY;

    // Состояние
    private String lastKey;
    private int token;
    private List<Net.Entry> entries = Collections.emptyList();
    private List<Net.Entry> conflicts = Collections.emptyList();
    private int note = -1;
    private int scroll;
    private boolean waiting;
    private String status = "";
    private int statusColor;
    private long statusUntil;
    private String lastActionName;
    /** Шаблон, для которого показан список (иконка в заголовке). */
    private ItemStack currentPattern;

    public void post(Runnable r) {
        tasks.add(r);
    }

    // ------------------------------------------------------------------ события

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Runnable r;
        while ((r = tasks.poll()) != null) r.run();
        if (gui != null && Minecraft.getMinecraft().currentScreen != gui) {
            gui = null;
            resetList();
        }
    }

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post e) {
        if (!isPatternTerm(e.gui)) return;
        if (gui != e.gui) resetList();
        gui = (GuiContainer) e.gui;
        layoutHorizontal();
        rows.clear();
        confRows.clear();
        extractButtons.clear();
        bg = new Background(BASE_ID);
        e.buttonList.add(bg);
        for (int i = 0; i < ROWS; i++) {
            RowButton b = new RowButton(BASE_ID + 1 + i, i);
            rows.add(b);
            e.buttonList.add(b);
        }
        for (int i = 0; i < CONF_ROWS; i++) {
            ConflictRow c = new ConflictRow(BASE_ID + 20 + i, i);
            confRows.add(c);
            e.buttonList.add(c);
            GuiButton x = new GuiButton(
                BASE_ID + 40 + i,
                0,
                0,
                52,
                16,
                I18n.format("gtnhpatternupload.button.extract"));
            extractButtons.add(x);
            e.buttonList.add(x);
        }
        up = new GuiButton(BASE_ID + 100, 0, 0, 20, 16, "▲");
        down = new GuiButton(BASE_ID + 101, 0, 0, 20, 16, "▼");
        e.buttonList.add(up);
        e.buttonList.add(down);
        relayout();
    }

    @SubscribeEvent
    public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre e) {
        if (e.gui != gui || gui == null || bg == null) return;
        ItemStack pattern = findPattern();
        if (pattern != null) {
            String key = keyOf(pattern);
            if (!key.equals(lastKey)) {
                lastKey = key;
                request(pattern);
            }
        } else if (lastKey != null) {
            lastKey = null;
            resetList();
        }
        if (pattern != null) currentPattern = pattern;
        visible = pattern != null || System.currentTimeMillis() < statusUntil;
        relayout();
    }

    @SubscribeEvent
    public void onDrawPost(GuiScreenEvent.DrawScreenEvent.Post e) {
        if (e.gui != gui || gui == null || !visible) return;
        for (RowButton b : rows) {
            if (b.visible && b.hovered(e.mouseX, e.mouseY)) {
                Net.Entry en = entries.get(scroll + b.slot);
                List<String> tip = new ArrayList<>();
                tip.add(displayName(en));
                tip.add(flagsLine(en, true));
                tip.add("§7" + I18n.format("gtnhpatternupload.tip.click"));
                drawTooltip(tip, e.mouseX, e.mouseY);
            }
        }
        for (int i = 0; i < extractButtons.size(); i++) {
            GuiButton x = extractButtons.get(i);
            if (x.visible && e.mouseX >= x.xPosition
                && e.mouseY >= x.yPosition
                && e.mouseX < x.xPosition + x.width
                && e.mouseY < x.yPosition + x.height) {
                List<String> tip = new ArrayList<>();
                tip.add(displayName(conflicts.get(i)));
                tip.add("§7" + I18n.format("gtnhpatternupload.tip.extract"));
                drawTooltip(tip, e.mouseX, e.mouseY);
            }
        }
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.gui != gui || gui == null) return;
        int id = e.button.id;
        if (id < BASE_ID || id > BASE_ID + 200) return;
        e.setCanceled(true);
        if (e.button == up) scroll--;
        else if (e.button == down) scroll++;
        else if (e.button instanceof RowButton) {
            int index = scroll + ((RowButton) e.button).slot;
            if (index < entries.size()) {
                lastActionName = displayName(entries.get(index));
                Net.CH.sendToServer(new Net.Upload(token, index));
                setStatus(I18n.format("gtnhpatternupload.status.uploading"), GUI_TEXT_DIM, 3000);
            }
        } else {
            int i = extractButtons.indexOf(e.button);
            if (i >= 0 && i < conflicts.size()) {
                lastActionName = displayName(conflicts.get(i));
                Net.CH.sendToServer(new Net.ExtractOld(token, i));
                setStatus(I18n.format("gtnhpatternupload.status.extracting"), GUI_TEXT_DIM, 3000);
            }
        }
    }

    // ------------------------------------------------------------------ ответы сервера

    public void onCandidates(Net.CandidateList msg) {
        if (gui == null) return;
        for (Net.Entry e : msg.entries) prepare(e);
        for (Net.Entry e : msg.conflicts) prepare(e);
        token = msg.token;
        entries = msg.entries;
        conflicts = msg.conflicts;
        note = msg.note;
        scroll = 0;
        waiting = false;
    }

    public void onResult(Net.Result msg) {
        String name = lastActionName != null ? lastActionName : resolveName(msg.name, null);
        int good = 0x1E7A1E, bad = 0xB00000;
        switch (msg.code) {
            case Net.Result.OK:
                setStatus(I18n.format("gtnhpatternupload.result.ok", name), good, 4000);
                break;
            case Net.Result.EXTRACTED:
                setStatus(I18n.format("gtnhpatternupload.result.extracted", name), good, 4000);
                lastKey = null; // обновить список конфликтов
                break;
            case Net.Result.INV_FULL:
                setStatus(I18n.format("gtnhpatternupload.result.invfull"), bad, 4000);
                break;
            case Net.Result.FULL:
                setStatus(I18n.format("gtnhpatternupload.result.full", name), bad, 4000);
                break;
            case Net.Result.DENIED:
                setStatus(I18n.format("gtnhpatternupload.result.denied"), bad, 4000);
                break;
            case Net.Result.NO_PATTERN:
                setStatus(I18n.format("gtnhpatternupload.result.nopattern"), bad, 4000);
                break;
            default:
                setStatus(I18n.format("gtnhpatternupload.result.gone"), bad, 4000);
                lastKey = null; // запросить список заново
        }
    }

    // ------------------------------------------------------------------ для NEI

    public boolean overlaps(GuiContainer g, int x, int y, int w, int h) {
        return g == gui && visible && x < px + pw && x + w > px && y < py + ph && y + h > py;
    }

    // ------------------------------------------------------------------ раскладка

    private static boolean isPatternTerm(GuiScreen g) {
        return g instanceof GuiContainer && ((GuiContainer) g).inventorySlots instanceof ContainerPatternTerm;
    }

    /**
     * Ширина и сторона. Справа — сразу за колонкой ячеек AE2, ширина подстраивается под свободное место (NEI уступает
     * его). Слева — только если справа совсем тесно, и тогда за колонкой кнопок AE2, чтобы ничего не перекрыть.
     */
    private void layoutHorizontal() {
        int left = field("guiLeft", "field_147003_i");
        int xSize = field("xSize", "field_146999_f");
        int rightX = left + xSize + RIGHT_GAP;
        int rightSpace = gui.width - rightX - 4;
        int leftEdge = left - LEFT_GAP;
        int leftSpace = leftEdge - 4;
        boolean preferLeft = "left".equalsIgnoreCase(Config.panelSide);
        boolean useRight = preferLeft ? leftSpace < MIN_W && rightSpace >= leftSpace
            : rightSpace >= MIN_W || rightSpace >= leftSpace;
        if (useRight) {
            pw = Math.max(MIN_W, Math.min(MAX_W, rightSpace));
            px = rightX;
        } else {
            pw = Math.max(MIN_W, Math.min(MAX_W, leftSpace));
            px = Math.max(2, leftEdge - pw);
        }
    }

    /** Вертикальная раскладка по содержимому: список, пояснение, конфликты, строка состояния. */
    private void relayout() {
        int top = field("guiTop", "field_147009_r");
        int shown = Math.min(entries.size(), ROWS);
        int maxScroll = Math.max(0, entries.size() - ROWS);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        String n = noteText();
        int noteLines = n.isEmpty() ? 0
            : fr.listFormattedStringToWidth(n, pw - 16)
                .size();
        int confShown = Math.min(conflicts.size(), CONF_ROWS);
        int confBlock = confShown == 0 ? 0 : 14 + confShown * CONF_H + (conflicts.size() > CONF_ROWS ? 10 : 0) + 4;

        int body = shown * ROW_H + (shown > 0 ? 4 : 0) + (noteLines > 0 ? noteLines * 10 + 4 : 0) + confBlock;
        // Подвал растёт под длинную строку состояния (переносим, а не обрезаем).
        footer = FOOTER;
        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
            int lines = fr.listFormattedStringToWidth(status, statusWidth())
                .size();
            footer = Math.max(FOOTER, lines * 10 + 10);
        }
        ph = HEADER + Math.max(body, 12) + footer;
        py = Math.max(2, Math.min(top, gui.height - ph - 2));
        listY = py + HEADER;
        noteY = listY + shown * ROW_H + (shown > 0 ? 4 : 0);
        confY = noteY + (noteLines > 0 ? noteLines * 10 + 4 : 0);

        bg.xPosition = px;
        bg.yPosition = py;
        bg.width = pw;
        bg.height = ph;
        bg.visible = visible;
        for (RowButton b : rows) {
            b.xPosition = px + 5;
            b.yPosition = listY + b.slot * ROW_H;
            b.width = pw - 10;
            b.height = ROW_H - 1;
            b.visible = visible && scroll + b.slot < entries.size();
        }
        for (int i = 0; i < CONF_ROWS; i++) {
            ConflictRow c = confRows.get(i);
            GuiButton x = extractButtons.get(i);
            boolean on = visible && i < confShown;
            c.xPosition = px + 5;
            c.yPosition = confY + 14 + i * CONF_H;
            c.width = pw - 10;
            c.height = CONF_H - 1;
            c.visible = on;
            x.xPosition = px + pw - 5 - 54;
            x.yPosition = c.yPosition + (CONF_H - 1 - 16) / 2;
            x.visible = on;
        }
        up.xPosition = px + pw - 48;
        down.xPosition = px + pw - 26;
        up.yPosition = down.yPosition = py + ph - footer + 3;
        up.visible = down.visible = visible && entries.size() > ROWS;
        up.enabled = scroll > 0;
        down.enabled = scroll < maxScroll;
    }

    private int field(String dev, String srg) {
        Integer v = ObfuscationReflectionHelper.getPrivateValue(GuiContainer.class, gui, dev, srg);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ логика

    private ItemStack findPattern() {
        for (Object o : gui.inventorySlots.inventorySlots) {
            Slot s = (Slot) o;
            if (s.inventory instanceof InventoryPlayer) continue;
            ItemStack st = s.getStack();
            if (st != null && st.getItem() instanceof ItemEncodedPattern) return st;
        }
        return null;
    }

    private static String keyOf(ItemStack pattern) {
        return pattern.getTagCompound() == null ? ""
            : pattern.getTagCompound()
                .toString();
    }

    private void request(ItemStack pattern) {
        ItemStack out = null;
        try {
            out = ((ItemEncodedPattern) pattern.getItem()).getOutput(pattern);
        } catch (Throwable ignored) {}
        boolean ctx = RecipeContext.appliesTo(out);
        Net.CH.sendToServer(
            new Net.Request(
                ctx ? RecipeContext.category : "",
                ctx ? RecipeContext.nonConsumables : Collections.emptyList()));
        entries = Collections.emptyList();
        conflicts = Collections.emptyList();
        note = -1;
        waiting = true;
    }

    private void resetList() {
        entries = Collections.emptyList();
        conflicts = Collections.emptyList();
        note = -1;
        waiting = false;
        scroll = 0;
    }

    private void setStatus(String text, int color, long ms) {
        status = text;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + ms;
    }

    private static void prepare(Net.Entry e) {
        e.name = resolveName(e.name, e.icon);
        e.suffix = parseSuffix(e.suffix);
    }

    private static String translate(String raw) {
        if (raw == null) return "";
        if (StatCollector.canTranslate(raw)) return StatCollector.translateToLocal(raw);
        if (StatCollector.canTranslate(raw + ".name")) return StatCollector.translateToLocal(raw + ".name");
        return raw;
    }

    /**
     * Имя для показа. Сервер шлёт «сырое» имя: переименование игрока или ключ перевода машины (у GT это ключ без
     * «.name», например gt.blockmachines.industrialextruder). Если ключ не переводится — берём имя предмета-иконки.
     */
    private static String resolveName(String raw, ItemStack icon) {
        String t = translate(raw);
        boolean looksLikeKey = t.equals(raw) && raw.indexOf(' ') < 0 && raw.indexOf('.') > 0;
        if (looksLikeKey && icon != null) {
            try {
                return icon.getDisplayName();
            } catch (Throwable ignored) {}
        }
        return t;
    }

    /** Подпись CRIB (форма, схема, номер) приходит как JSON и переводится на языке игрока. */
    private static String parseSuffix(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            IChatComponent c = IChatComponent.Serializer.func_150699_a(json);
            return c == null ? ""
                : c.getFormattedText()
                    .trim();
        } catch (Throwable t) {
            return json;
        }
    }

    private static String displayName(Net.Entry e) {
        return e.suffix == null || e.suffix.isEmpty() ? e.name : e.name + " " + e.suffix;
    }

    /** Строка признаков. В строке списка (светлый фон AE2) — тёмные цвета, в подсказке (тёмный фон) — яркие. */
    private static String flagsLine(Net.Entry e, boolean tooltip) {
        String good = tooltip ? "§a" : "§2", bad = tooltip ? "§c" : "§4", warn = tooltip ? "§e" : "§6",
            dim = tooltip ? "§7" : "§8";
        StringBuilder sb = new StringBuilder();
        if ((e.flags & Net.Entry.MOLD_MATCH) != 0) sb.append(good)
            .append("✔ ")
            .append(I18n.format("gtnhpatternupload.flag.mold"))
            .append("  ");
        else if ((e.flags & Net.Entry.MOLD_MISMATCH) != 0) sb.append(bad)
            .append("✘ ")
            .append(I18n.format("gtnhpatternupload.flag.othermold"))
            .append("  ");
        else if ((e.flags & Net.Entry.RECIPE_MATCH) != 0) sb.append(good)
            .append("✔ ")
            .append(I18n.format("gtnhpatternupload.flag.machine"))
            .append("  ");
        if ((e.flags & Net.Entry.HAS_SAME) != 0) sb.append(warn)
            .append(I18n.format("gtnhpatternupload.flag.same"))
            .append("  ");
        if (e.free == 0) sb.append(bad)
            .append(I18n.format("gtnhpatternupload.flag.full"));
        else sb.append(dim)
            .append(I18n.format(tooltip ? "gtnhpatternupload.flag.free.long" : "gtnhpatternupload.flag.free", e.free));
        return sb.toString();
    }

    private String noteText() {
        if (waiting) return I18n.format("gtnhpatternupload.note.waiting");
        switch (note) {
            case Candidates.NOTE_NO_PATTERN:
                return I18n.format("gtnhpatternupload.note.nopattern");
            case Candidates.NOTE_NO_GRID:
                return I18n.format("gtnhpatternupload.note.nogrid");
            case Candidates.NOTE_DENIED:
                return I18n.format("gtnhpatternupload.note.denied");
            case Candidates.NOTE_NO_MACHINE:
                return I18n.format("gtnhpatternupload.note.nomachine");
            case Candidates.NOTE_UNKNOWN_RECIPE:
                return I18n.format("gtnhpatternupload.note.unknown");
            default:
                return entries.isEmpty() && note >= 0 ? I18n.format("gtnhpatternupload.note.empty") : "";
        }
    }

    // ------------------------------------------------------------------ отрисовка в стиле AE2 (окно Minecraft)

    /** Окно как у интерфейсов AE2: чёрный контур, светлая фаска сверху-слева, тёмная снизу-справа. */
    private static void drawWindow(int x, int y, int w, int h) {
        Gui.drawRect(x + 1, y, x + w - 1, y + 1, 0xFF000000);
        Gui.drawRect(x + 1, y + h - 1, x + w - 1, y + h, 0xFF000000);
        Gui.drawRect(x, y + 1, x + 1, y + h - 1, 0xFF000000);
        Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, 0xFF000000);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, GUI_FILL);
        Gui.drawRect(x + 1, y + 1, x + w - 3, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x + 1, y + 1, x + 3, y + h - 3, 0xFFFFFFFF);
        Gui.drawRect(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        Gui.drawRect(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
    }

    /** Утопленное поле (как область слотов в терминале). */
    private static void drawInset(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, 0xFF8B8B8B);
        Gui.drawRect(x, y, x + w, y + 1, 0xFF373737);
        Gui.drawRect(x, y, x + 1, y + h, 0xFF373737);
        Gui.drawRect(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        Gui.drawRect(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
    }

    /** Выпуклая плитка: светлая фаска сверху-слева, тень снизу-справа; нажатая — наоборот. */
    private static void drawRaised(int x, int y, int w, int h, int fill, boolean pressed) {
        int hi = pressed ? 0xFF555555 : 0xFFFFFFFF;
        int lo = pressed ? 0xFFFFFFFF : 0xFF555555;
        Gui.drawRect(x, y, x + w, y + h, 0xFF373737);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        Gui.drawRect(x + 1, y + 1, x + w - 2, y + 2, hi);
        Gui.drawRect(x + 1, y + 1, x + 2, y + h - 2, hi);
        Gui.drawRect(x + 2, y + h - 2, x + w - 1, y + h - 1, lo);
        Gui.drawRect(x + w - 2, y + 2, x + w - 1, y + h - 1, lo);
    }

    /** Мягкая тень под окном, чтобы панель «висела» над экраном. */
    private static void drawShadow(int x, int y, int w, int h) {
        Gui.drawRect(x + 4, y + h, x + w + 2, y + h + 3, 0x50000000);
        Gui.drawRect(x + w, y + 4, x + w + 3, y + h + 3, 0x50000000);
        Gui.drawRect(x + 3, y + h, x + w + 1, y + h + 1, 0x30000000);
    }

    /** Утопленный слот 18×18, как ячейки AE2. */
    private static void drawSlot(int x, int y) {
        Gui.drawRect(x, y, x + 18, y + 18, 0xFF373737);
        Gui.drawRect(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        Gui.drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    private static void drawItem(Minecraft mc, ItemStack stack, int x, int y) {
        if (stack == null) return;
        GL11.glPushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        ITEM_RENDER.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    private void drawTooltip(List<String> lines, int x, int y) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int w = 0;
        for (String l : lines) w = Math.max(w, fr.getStringWidth(l));
        int tx = x + 12, ty = y - 12, h = lines.size() * 10 + 2;
        if (tx + w + 6 > gui.width) tx = x - w - 16;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        Gui.drawRect(tx - 3, ty - 3, tx + w + 3, ty + h, 0xF0100010);
        Gui.drawRect(tx - 3, ty - 3, tx + w + 3, ty - 2, 0xFF5000FF);
        for (int i = 0; i < lines.size(); i++) fr.drawStringWithShadow(lines.get(i), tx, ty + i * 10, 0xFFFFFF);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------------ кнопки

    /** Окно панели, заголовок, пояснение, заголовок конфликтов, строка состояния. Рисуется первым. */
    private class Background extends GuiButton {

        Background(int id) {
            super(id, 0, 0, 1, 1, "");
            this.enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible) return;
            FontRenderer fr = mc.fontRenderer;
            drawShadow(xPosition, yPosition, width, height);
            drawWindow(xPosition, yPosition, width, height);
            // Заголовок: шаблон, который загружаем, в слоте + надпись.
            int tx = xPosition + 8;
            if (currentPattern != null) {
                drawSlot(xPosition + 5, yPosition + 2);
                drawItem(mc, currentPattern, xPosition + 6, yPosition + 3);
                tx = xPosition + 27;
            }
            fr.drawString(I18n.format("gtnhpatternupload.title"), tx, yPosition + 7, GUI_TEXT);
            if (entries.size() > ROWS) {
                String page = (scroll + 1) + "-" + Math.min(scroll + ROWS, entries.size()) + "/" + entries.size();
                fr.drawString(page, xPosition + width - 8 - fr.getStringWidth(page), yPosition + 7, GUI_TEXT_DIM);
            }
            int shown = Math.min(entries.size(), ROWS);
            if (shown > 0) drawInset(xPosition + 4, listY - 1, width - 8, shown * ROW_H + 1);
            String n = noteText();
            if (!n.isEmpty()) {
                List<String> wrapped = fr.listFormattedStringToWidth(n, width - 16);
                for (int i = 0; i < wrapped.size(); i++) {
                    fr.drawString(wrapped.get(i), xPosition + 8, noteY + i * 10, GUI_TEXT_DIM);
                }
            }
            int confShown = Math.min(conflicts.size(), CONF_ROWS);
            if (confShown > 0) {
                fr.drawString(
                    fr.trimStringToWidth(I18n.format("gtnhpatternupload.conflict.title", conflicts.size()), width - 16),
                    xPosition + 8,
                    confY + 3,
                    GUI_TEXT_BAD);
                drawInset(xPosition + 4, confY + 13, width - 8, confShown * CONF_H + 1);
                if (conflicts.size() > CONF_ROWS) {
                    fr.drawString(
                        I18n.format("gtnhpatternupload.conflict.more", conflicts.size() - CONF_ROWS),
                        xPosition + 8,
                        confY + 15 + confShown * CONF_H,
                        GUI_TEXT_DIM);
                }
            }
            if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
                List<String> lines = fr.listFormattedStringToWidth(status, statusWidth());
                for (int i = 0; i < lines.size(); i++) {
                    fr.drawString(lines.get(i), xPosition + 8, yPosition + height - footer + 6 + i * 10, statusColor);
                }
            }
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            return false;
        }
    }

    /** Строка кандидата: слот с иконкой машины, имя, подпись CRIB, признаки. */
    private class RowButton extends GuiButton {

        final int slot;

        RowButton(int id, int slot) {
            super(id, 0, 0, 1, 1, "");
            this.slot = slot;
        }

        boolean hovered(int mx, int my) {
            return mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible || scroll + slot >= entries.size()) return;
            Net.Entry e = entries.get(scroll + slot);
            boolean hover = hovered(mx, my);
            boolean pressed = hover && org.lwjgl.input.Mouse.isButtonDown(0);
            // Выпуклая плитка; при наведении — голубоватая подсветка, как выделение в AE2.
            drawRaised(xPosition + 1, yPosition + 1, width - 2, height - 2, hover ? 0xFFD3DCE8 : GUI_FILL, pressed);
            int stripe = (e.flags & Net.Entry.MOLD_MATCH) != 0 ? 0xFF2E9E2E
                : (e.flags & Net.Entry.MOLD_MISMATCH) != 0 ? 0xFFC03030
                    : (e.flags & Net.Entry.RECIPE_MATCH) != 0 ? 0xFF4A7FC0 : 0xFF8B8B8B;
            drawRect(xPosition + 2, yPosition + 2, xPosition + 5, yPosition + height - 2, stripe);
            drawRect(xPosition + 2, yPosition + 2, xPosition + 3, yPosition + height - 2, 0x40FFFFFF);
            int sy = yPosition + (height - 18) / 2;
            drawSlot(xPosition + 7, sy);
            drawItem(mc, e.icon, xPosition + 8, sy + 1);
            if (hover) drawRect(xPosition + 8, sy + 1, xPosition + 24, sy + 17, 0x60FFFFFF);
            FontRenderer fr = mc.fontRenderer;
            int tx = xPosition + 29, tw = width - 33;
            fr.drawString(fr.trimStringToWidth(e.name, tw), tx, yPosition + 2, GUI_TEXT);
            if (e.suffix != null && !e.suffix.isEmpty()) {
                fr.drawString(fr.trimStringToWidth(e.suffix, tw), tx, yPosition + 12, GUI_TEXT_DIM);
            }
            fr.drawString(fr.trimStringToWidth(flagsLine(e, false), tw), tx, yPosition + 22, GUI_TEXT_DIM);
        }
    }

    /** Строка конфликта: машина, где уже есть шаблон на этот результат. Кнопка «Извлечь» — отдельная. */
    private class ConflictRow extends GuiButton {

        final int slot;

        ConflictRow(int id, int slot) {
            super(id, 0, 0, 1, 1, "");
            this.slot = slot;
            this.enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            if (!visible || slot >= conflicts.size()) return;
            Net.Entry e = conflicts.get(slot);
            drawRaised(xPosition + 1, yPosition + 1, width - 2, height - 2, 0xFFD9CDB8, false);
            drawRect(xPosition + 2, yPosition + 2, xPosition + 5, yPosition + height - 2, 0xFFD08A1E);
            int sy = yPosition + (height - 18) / 2;
            drawSlot(xPosition + 7, sy);
            drawItem(mc, e.icon, xPosition + 8, sy + 1);
            FontRenderer fr = mc.fontRenderer;
            int tx = xPosition + 29, tw = width - 29 - 60;
            fr.drawString(fr.trimStringToWidth(e.name, tw), tx, yPosition + 2, GUI_TEXT);
            if (e.suffix != null && !e.suffix.isEmpty()) {
                fr.drawString(fr.trimStringToWidth(e.suffix, tw), tx, yPosition + 12, GUI_TEXT_DIM);
            }
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            return false;
        }
    }
}
