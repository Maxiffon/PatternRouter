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

    private static final int W = 176;
    private static final int ROW_H = 24;
    private static final int ROWS = 7;
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
        Background bg = new Background(BASE_ID, px, py, W, ph);
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
                Net.CH.sendToServer(new Net.Upload(token, index));
                setStatus(I18n.format("gtnhpatternupload.status.uploading"), 0xAAAAAA, 3000);
            }
        }
    }

    // ------------------------------------------------------------------ ответы сервера

    public void onCandidates(Net.CandidateList msg) {
        if (gui == null) return;
        token = msg.token;
        entries = msg.entries;
        note = msg.note;
        scroll = 0;
        waiting = false;
    }

    public void onResult(Net.Result msg) {
        String name = translate(msg.name);
        switch (msg.code) {
            case Net.Result.OK:
                setStatus(I18n.format("gtnhpatternupload.result.ok", name), 0x55FF55, 4000);
                break;
            case Net.Result.FULL:
                setStatus(I18n.format("gtnhpatternupload.result.full", name), 0xFF5555, 4000);
                break;
            case Net.Result.DENIED:
                setStatus(I18n.format("gtnhpatternupload.result.denied"), 0xFF5555, 4000);
                break;
            case Net.Result.NO_PATTERN:
                setStatus(I18n.format("gtnhpatternupload.result.nopattern"), 0xFF5555, 4000);
                break;
            default:
                setStatus(I18n.format("gtnhpatternupload.result.gone"), 0xFF5555, 4000);
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
        return StatCollector.canTranslate(raw) ? StatCollector.translateToLocal(raw) : raw;
    }

    private static String displayName(Net.Entry e) {
        String n = translate(e.name);
        return e.suffix == null || e.suffix.isEmpty() ? n : n + " " + e.suffix;
    }

    private static String flagsLine(Net.Entry e, boolean long_) {
        StringBuilder sb = new StringBuilder();
        if ((e.flags & Net.Entry.MOLD_MATCH) != 0) sb.append("§a✔ ")
            .append(I18n.format("gtnhpatternupload.flag.mold"))
            .append("  ");
        else if ((e.flags & Net.Entry.MOLD_MISMATCH) != 0) sb.append("§c✘ ")
            .append(I18n.format("gtnhpatternupload.flag.othermold"))
            .append("  ");
        else if ((e.flags & Net.Entry.RECIPE_MATCH) != 0) sb.append("§a✔ ")
            .append(I18n.format("gtnhpatternupload.flag.machine"))
            .append("  ");
        if ((e.flags & Net.Entry.HAS_SAME) != 0) sb.append("§e")
            .append(I18n.format("gtnhpatternupload.flag.same"))
            .append("  ");
        if (e.free == 0) sb.append("§c")
            .append(I18n.format("gtnhpatternupload.flag.full"));
        else sb.append("§7")
            .append(I18n.format(long_ ? "gtnhpatternupload.flag.free.long" : "gtnhpatternupload.flag.free", e.free));
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
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0xE0101420);
            drawRect(xPosition, yPosition, xPosition + width, yPosition + 1, 0xFF3D7BFF);
            fr.drawStringWithShadow(I18n.format("gtnhpatternupload.title"), xPosition + 6, yPosition + 8, 0xFFFFFF);
            if (entries.size() > ROWS) {
                String page = (scroll + 1) + "-" + Math.min(scroll + ROWS, entries.size()) + "/" + entries.size();
                fr.drawString(page, xPosition + width - 6 - fr.getStringWidth(page), yPosition + 8, 0x888888);
            }
            String n = noteText();
            int textY = yPosition + HEADER + (entries.isEmpty() ? 4 : Math.min(entries.size(), ROWS) * ROW_H + 2);
            if (!n.isEmpty()) {
                List<String> wrapped = fr.listFormattedStringToWidth(n, width - 12);
                for (int i = 0; i < wrapped.size() && textY + i * 10 < yPosition + height - FOOTER; i++) {
                    fr.drawString(wrapped.get(i), xPosition + 6, textY + i * 10, 0xAAAAAA);
                }
            }
            if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
                String s = fr.trimStringToWidth(status, width - 60);
                fr.drawStringWithShadow(s, xPosition + 6, yPosition + height - FOOTER + 7, statusColor);
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
            int bg = (e.flags & Net.Entry.MOLD_MATCH) != 0 ? 0xC0183A24
                : (e.flags & Net.Entry.MOLD_MISMATCH) != 0 ? 0xC03A1818
                    : (e.flags & Net.Entry.RECIPE_MATCH) != 0 ? 0xC01C2A3A : 0xC0202020;
            if (hover) bg = (bg & 0x00FFFFFF) | 0xF0000000 | 0x202020;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, bg);
            if (e.icon != null) {
                GL11.glPushMatrix();
                RenderHelper.enableGUIStandardItemLighting();
                GL11.glEnable(GL12.GL_RESCALE_NORMAL);
                ITEM_RENDER.renderItemAndEffectIntoGUI(
                    mc.fontRenderer,
                    mc.getTextureManager(),
                    e.icon,
                    xPosition + 2,
                    yPosition + 3);
                RenderHelper.disableStandardItemLighting();
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glPopMatrix();
            }
            FontRenderer fr = mc.fontRenderer;
            int tx = xPosition + 21, tw = width - 23;
            fr.drawStringWithShadow(fr.trimStringToWidth(displayName(e), tw), tx, yPosition + 2, 0xFFFFFF);
            fr.drawString(fr.trimStringToWidth(flagsLine(e, false), tw), tx, yPosition + 12, 0xAAAAAA);
        }
    }
}
