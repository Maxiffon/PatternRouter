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
import gtnhpatternupload.Net;
import gtnhpatternupload.server.Candidates;

/**
 * Боковая панель у терминала шаблонов. Всё на событиях Forge и обычных кнопках: интерфейс AE2 не патчится.
 */
public class ClientPanel {

    private static final int W = 204;
    /** Три строки: имя машины, подпись (форма/схема из CRIB), признаки. */
    private static final int ROW_H = 34;
    private static final int ROWS = 6;
    private static final int HEADER = 24;
    private static final int FOOTER = 22;
    private static final int BASE_ID = 0x6A5000;
    private static final RenderItem ITEM_RENDER = new RenderItem();

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    // Текущий терминал и раскладка
    private GuiContainer gui;
    private int px, py, ph;
    private boolean visible;
    private final List<RowButton> rows = new ArrayList<>();
    private GuiButton up, down;
    private Background bg;

    // Состояние списка
    private String lastKey;
    private int token;
    private List<Net.Entry> entries = Collections.emptyList();
    private int note = -1;
    private int scroll;
    private boolean waiting;
    private String status = "";
    private int statusColor;
    private long statusUntil;
    private String lastUploadName;

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
        layout();
        rows.clear();
        bg = new Background(BASE_ID, px, py, W, ph);
        e.buttonList.add(bg);
        for (int i = 0; i < ROWS; i++) {
            RowButton b = new RowButton(BASE_ID + 1 + i, px + 4, py + HEADER + i * ROW_H, W - 8, ROW_H - 2, i);
            rows.add(b);
            e.buttonList.add(b);
        }
        up = new GuiButton(BASE_ID + 100, px + W - 46, py + ph - FOOTER + 2, 20, 18, "▲");
        down = new GuiButton(BASE_ID + 101, px + W - 24, py + ph - FOOTER + 2, 20, 18, "▼");
        e.buttonList.add(up);
        e.buttonList.add(down);
    }

    @SubscribeEvent
    public void onDrawPre(GuiScreenEvent.DrawScreenEvent.Pre e) {
        if (e.gui != gui || gui == null) return;
        ItemStack pattern = findPattern();
        long now = System.currentTimeMillis();
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
        visible = pattern != null || now < statusUntil;
        int maxScroll = Math.max(0, entries.size() - ROWS);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        for (RowButton b : rows) b.visible = visible && scroll + b.slot < entries.size();
        up.visible = down.visible = visible && entries.size() > ROWS;
        up.enabled = scroll > 0;
        down.enabled = scroll < maxScroll;
        // Высота панели — по содержимому, а не на весь экран.
        int shown = Math.min(entries.size(), ROWS);
        int body = shown > 0 ? shown * ROW_H + (noteText().isEmpty() ? 0 : 24) : 34;
        ph = HEADER + body + FOOTER;
        bg.height = ph;
        up.yPosition = down.yPosition = py + ph - FOOTER + 2;
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
                lastUploadName = displayName(entries.get(index));
                Net.CH.sendToServer(new Net.Upload(token, index));
                setStatus(I18n.format("gtnhpatternupload.status.uploading"), 0x5A5A5A, 3000);
            }
        }
    }

    // ------------------------------------------------------------------ ответы сервера

    public void onCandidates(Net.CandidateList msg) {
        if (gui == null) return;
        for (Net.Entry e : msg.entries) {
            e.name = resolveName(e.name, e.icon);
            e.suffix = parseSuffix(e.suffix);
        }
        token = msg.token;
        entries = msg.entries;
        note = msg.note;
        scroll = 0;
        waiting = false;
    }

    public void onResult(Net.Result msg) {
        String name = lastUploadName != null ? lastUploadName : resolveName(msg.name, null);
        switch (msg.code) {
            case Net.Result.OK:
                setStatus(I18n.format("gtnhpatternupload.result.ok", name), 0x1E7A1E, 4000);
                break;
            case Net.Result.FULL:
                setStatus(I18n.format("gtnhpatternupload.result.full", name), 0xB00000, 4000);
                break;
            case Net.Result.DENIED:
                setStatus(I18n.format("gtnhpatternupload.result.denied"), 0xB00000, 4000);
                break;
            case Net.Result.NO_PATTERN:
                setStatus(I18n.format("gtnhpatternupload.result.nopattern"), 0xB00000, 4000);
                break;
            default:
                setStatus(I18n.format("gtnhpatternupload.result.gone"), 0xB00000, 4000);
                lastKey = null; // запросить список заново
        }
    }

    // ------------------------------------------------------------------ для NEI

    public boolean overlaps(GuiContainer g, int x, int y, int w, int h) {
        return g == gui && visible && x < px + W && x + w > px && y < py + ph && y + h > py;
    }

    // ------------------------------------------------------------------ логика

    private static boolean isPatternTerm(GuiScreen g) {
        return g instanceof GuiContainer && ((GuiContainer) g).inventorySlots instanceof ContainerPatternTerm;
    }

    private void layout() {
        int left = field("guiLeft", "field_147003_i");
        int top = field("guiTop", "field_147009_r");
        int xSize = field("xSize", "field_146999_f");
        ph = HEADER + ROWS * ROW_H + FOOTER;
        // Справа, с отступом под колонку ячеек вида/улучшений (NEI уступает это место); не помещается — слева.
        boolean right = !"left".equalsIgnoreCase(gtnhpatternupload.Config.panelSide);
        px = right ? left + xSize + 28 : left - W - 6;
        if (right && px + W > gui.width - 2) px = left - W - 6;
        if (!right && px < 2) px = left + xSize + 28;
        if (px < 2) px = 2;
        py = Math.max(2, Math.min(top, gui.height - ph - 2));
    }

    private int field(String dev, String srg) {
        Integer v = ObfuscationReflectionHelper.getPrivateValue(GuiContainer.class, gui, dev, srg);
        return v == null ? 0 : v;
    }

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
        note = -1;
        waiting = true;
    }

    private void resetList() {
        entries = Collections.emptyList();
        note = -1;
        waiting = false;
        scroll = 0;
    }

    private void setStatus(String text, int color, long ms) {
        status = text;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + ms;
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

    /**
     * Строка признаков. В строке списка (светлый фон AE2) — тёмные цвета, в подсказке (тёмный фон) — яркие.
     */
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

    // ------------------------------------------------------------------ отрисовка в стиле AE2 (окно Minecraft)

    private static final int GUI_FILL = 0xFFC6C6C6;
    private static final int GUI_TEXT = 0x404040;
    private static final int GUI_TEXT_DIM = 0x5A5A5A;

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

    /** Утопленный слот 18×18, как ячейки AE2. */
    private static void drawSlot(int x, int y) {
        Gui.drawRect(x, y, x + 18, y + 18, 0xFF373737);
        Gui.drawRect(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        Gui.drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
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

    private void drawTooltip(List<String> lines, int x, int y) {
        // GuiScreen.drawHoveringText защищён; рисуем простую подсказку сами.
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

    /** Фон панели, заголовок и строка состояния. Рисуется первым из наших кнопок. */
    private class Background extends GuiButton {

        Background(int id, int x, int y, int w, int h) {
            super(id, x, y, w, h, "");
            this.enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mx, int my) {
            this.visible = ClientPanel.this.visible;
            if (!visible) return;
            FontRenderer fr = mc.fontRenderer;
            drawWindow(xPosition, yPosition, width, height);
            // Заголовок — как «Терминал шаблонов» у AE2: тёмно-серый, без тени.
            fr.drawString(I18n.format("gtnhpatternupload.title"), xPosition + 8, yPosition + 8, GUI_TEXT);
            if (entries.size() > ROWS) {
                String page = (scroll + 1) + "-" + Math.min(scroll + ROWS, entries.size()) + "/" + entries.size();
                fr.drawString(page, xPosition + width - 8 - fr.getStringWidth(page), yPosition + 8, GUI_TEXT_DIM);
            }
            // Область списка — утопленная, как поле терминала.
            int shown = Math.min(entries.size(), ROWS);
            if (shown > 0) {
                int lx = xPosition + 4, ly = yPosition + HEADER - 2, lw = width - 8, lh = shown * ROW_H + 2;
                drawRect(lx, ly, lx + lw, ly + lh, 0xFF8B8B8B);
                drawRect(lx, ly, lx + lw, ly + 1, 0xFF373737);
                drawRect(lx, ly, lx + 1, ly + lh, 0xFF373737);
                drawRect(lx + 1, ly + lh - 1, lx + lw, ly + lh, 0xFFFFFFFF);
                drawRect(lx + lw - 1, ly + 1, lx + lw, ly + lh, 0xFFFFFFFF);
            }
            String n = noteText();
            int textY = yPosition + HEADER + (shown == 0 ? 2 : shown * ROW_H + 4);
            if (!n.isEmpty()) {
                List<String> wrapped = fr.listFormattedStringToWidth(n, width - 16);
                for (int i = 0; i < wrapped.size() && textY + i * 10 < yPosition + height - FOOTER; i++) {
                    fr.drawString(wrapped.get(i), xPosition + 8, textY + i * 10, GUI_TEXT_DIM);
                }
            }
            if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
                String s = fr.trimStringToWidth(status, width - 60);
                fr.drawString(s, xPosition + 8, yPosition + height - FOOTER + 7, statusColor);
            }
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mx, int my) {
            return false;
        }
    }

    /** Строка списка: иконка машины, имя, признаки. */
    private class RowButton extends GuiButton {

        final int slot;

        RowButton(int id, int x, int y, int w, int h, int slot) {
            super(id, x, y, w, h, "");
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
            // Строка — светлая плашка на утопленном поле; подходящие помечены цветной полоской слева.
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, hover ? 0xFFDADADA : GUI_FILL);
            drawRect(xPosition, yPosition + height, xPosition + width, yPosition + height + 1, 0xFF8B8B8B);
            int stripe = (e.flags & Net.Entry.MOLD_MATCH) != 0 ? 0xFF2E9E2E
                : (e.flags & Net.Entry.MOLD_MISMATCH) != 0 ? 0xFFC03030
                    : (e.flags & Net.Entry.RECIPE_MATCH) != 0 ? 0xFF4A7FC0 : 0xFF8B8B8B;
            drawRect(xPosition, yPosition, xPosition + 2, yPosition + height, stripe);
            drawSlot(xPosition + 4, yPosition + (height - 18) / 2);
            if (hover) drawRect(
                xPosition + 5,
                yPosition + (height - 18) / 2 + 1,
                xPosition + 21,
                yPosition + (height - 18) / 2 + 17,
                0x80FFFFFF);
            if (e.icon != null) {
                GL11.glPushMatrix();
                RenderHelper.enableGUIStandardItemLighting();
                GL11.glEnable(GL12.GL_RESCALE_NORMAL);
                ITEM_RENDER.renderItemAndEffectIntoGUI(
                    mc.fontRenderer,
                    mc.getTextureManager(),
                    e.icon,
                    xPosition + 5,
                    yPosition + (height - 18) / 2 + 1);
                RenderHelper.disableStandardItemLighting();
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glPopMatrix();
            }
            FontRenderer fr = mc.fontRenderer;
            int tx = xPosition + 26, tw = width - 29;
            fr.drawString(fr.trimStringToWidth(e.name, tw), tx, yPosition + 2, GUI_TEXT);
            if (e.suffix != null && !e.suffix.isEmpty()) {
                // Подписи CRIB бывают цветными (§); на светлом фоне их цвета не трогаем, только базовый тёмный.
                fr.drawString(fr.trimStringToWidth(e.suffix, tw), tx, yPosition + 12, GUI_TEXT_DIM);
            }
            fr.drawString(fr.trimStringToWidth(flagsLine(e, false), tw), tx, yPosition + 22, GUI_TEXT_DIM);
        }
    }
}
