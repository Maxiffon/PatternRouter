package patternrouter.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/** Drawing in the style of AE2 windows (a vanilla Minecraft window with bevels). */
final class AeStyle {

    static final int GUI_FILL = 0xFFC6C6C6;
    static final int GUI_TEXT = 0x404040;
    static final int GUI_TEXT_DIM = 0x5A5A5A;
    static final int GUI_TEXT_BAD = 0x9A1010;

    static final int OUTLINE = 0xFF000000;
    static final int BEVEL_LIGHT = 0xFFFFFFFF;
    static final int BEVEL_DARK = 0xFF555555;
    static final int INSET_FILL = 0xFF8B8B8B;
    static final int INSET_EDGE = 0xFF373737;

    static final int SLOT_SIZE = 18;
    static final int LINE_HEIGHT = 10;

    private static final int TOOLTIP_FILL = 0xF0100010;
    private static final int TOOLTIP_TOP = 0xFF5000FF;
    private static final int TOOLTIP_TEXT = 0xFFFFFF;

    private static final RenderItem ITEM_RENDER = new RenderItem();

    private AeStyle() {}

    static boolean isOver(GuiButton b, int mx, int my) {
        return mx >= b.xPosition && my >= b.yPosition && mx < b.xPosition + b.width && my < b.yPosition + b.height;
    }

    /**
     * AE2-style window with rounded corners: black outline (a diagonal pixel at each corner), light bevel top-left,
     * dark
     * bevel bottom-right.
     */
    static void drawWindow(int x, int y, int w, int h) {
        Gui.drawRect(x + 2, y, x + w - 2, y + 1, OUTLINE);
        Gui.drawRect(x + 2, y + h - 1, x + w - 2, y + h, OUTLINE);
        Gui.drawRect(x, y + 2, x + 1, y + h - 2, OUTLINE);
        Gui.drawRect(x + w - 1, y + 2, x + w, y + h - 2, OUTLINE);
        pixel(x + 1, y + 1, OUTLINE);
        pixel(x + w - 2, y + 1, OUTLINE);
        pixel(x + 1, y + h - 2, OUTLINE);
        pixel(x + w - 2, y + h - 2, OUTLINE);
        Gui.drawRect(x + 2, y + 1, x + w - 2, y + h - 1, GUI_FILL);
        Gui.drawRect(x + 1, y + 2, x + w - 1, y + h - 2, GUI_FILL);
        Gui.drawRect(x + 2, y + 1, x + w - 3, y + 3, BEVEL_LIGHT);
        Gui.drawRect(x + 1, y + 2, x + 3, y + h - 3, BEVEL_LIGHT);
        Gui.drawRect(x + 3, y + h - 3, x + w - 2, y + h - 1, BEVEL_DARK);
        Gui.drawRect(x + w - 3, y + 3, x + w - 1, y + h - 2, BEVEL_DARK);
    }

    private static void pixel(int x, int y, int color) {
        Gui.drawRect(x, y, x + 1, y + 1, color);
    }

    /** Inset area (like the slot area of a terminal). */
    static void drawInset(int x, int y, int w, int h) {
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, INSET_FILL);
        Gui.drawRect(x + 1, y, x + w - 1, y + 1, INSET_EDGE);
        Gui.drawRect(x, y + 1, x + 1, y + h - 1, INSET_EDGE);
        Gui.drawRect(x + 1, y + h - 1, x + w - 1, y + h, BEVEL_LIGHT);
        Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, BEVEL_LIGHT);
    }

    /** Raised tile: light bevel top-left, shadow bottom-right; inverted when pressed. */
    static void drawRaised(int x, int y, int w, int h, int fill, boolean pressed) {
        int hi = pressed ? BEVEL_DARK : BEVEL_LIGHT;
        int lo = pressed ? BEVEL_LIGHT : BEVEL_DARK;
        // Edge without the corner pixels: softly rounded.
        Gui.drawRect(x + 1, y, x + w - 1, y + 1, INSET_EDGE);
        Gui.drawRect(x + 1, y + h - 1, x + w - 1, y + h, INSET_EDGE);
        Gui.drawRect(x, y + 1, x + 1, y + h - 1, INSET_EDGE);
        Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, INSET_EDGE);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        Gui.drawRect(x + 1, y + 1, x + w - 2, y + 2, hi);
        Gui.drawRect(x + 1, y + 1, x + 2, y + h - 2, hi);
        Gui.drawRect(x + 2, y + h - 2, x + w - 1, y + h - 1, lo);
        Gui.drawRect(x + w - 2, y + 2, x + w - 1, y + h - 1, lo);
    }

    /** Soft drop shadow so the panel floats above the screen. */
    static void drawShadow(int x, int y, int w, int h) {
        Gui.drawRect(x + 4, y + h, x + w + 2, y + h + 3, 0x50000000);
        Gui.drawRect(x + w, y + 4, x + w + 3, y + h + 3, 0x50000000);
        Gui.drawRect(x + 3, y + h, x + w + 1, y + h + 1, 0x30000000);
    }

    /** Inset 18x18 slot like AE2 cells. */
    static void drawSlot(int x, int y) {
        Gui.drawRect(x, y, x + SLOT_SIZE, y + SLOT_SIZE, INSET_EDGE);
        Gui.drawRect(x + 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, BEVEL_LIGHT);
        Gui.drawRect(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, INSET_FILL);
    }

    static void drawItem(Minecraft mc, ItemStack stack, int x, int y) {
        if (stack == null) return;
        GL11.glPushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        ITEM_RENDER.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    /** Vanilla-looking tooltip next to the mouse, flipped left if it would leave a screen this wide. */
    static void drawTooltip(List<String> rawLines, int x, int y, int screenWidth, int screenHeight) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        // Long lines (a CRIB's name with all its circuits) wrap, so the tooltip never runs off the screen.
        int maxW = Math.max(80, Math.min(260, screenWidth - 16));
        List<String> lines = new java.util.ArrayList<>();
        for (String l : rawLines) {
            if (fr.getStringWidth(l) <= maxW) lines.add(l);
            else {
                // Wrapped parts keep the line's colour.
                String color = net.minecraft.util.EnumChatFormatting.WHITE.toString();
                for (String part : fr.listFormattedStringToWidth(l, maxW)) {
                    String fmt = net.minecraft.client.gui.FontRenderer.getFormatFromString(part);
                    lines.add(part.startsWith("§") ? part : color + part);
                    if (!fmt.isEmpty()) color = fmt;
                }
            }
        }
        int w = 0;
        for (String l : lines) w = Math.max(w, fr.getStringWidth(l));
        int tx = x + 12, ty = y - 12, h = lines.size() * LINE_HEIGHT + 2;
        if (tx + w + 6 > screenWidth) tx = x - w - 16;
        tx = Math.max(4, Math.min(tx, screenWidth - w - 4));
        ty = Math.max(4, Math.min(ty, screenHeight - h - 4));
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        Gui.drawRect(tx - 3, ty - 3, tx + w + 3, ty + h, TOOLTIP_FILL);
        Gui.drawRect(tx - 3, ty - 3, tx + w + 3, ty - 2, TOOLTIP_TOP);
        for (int i = 0; i < lines.size(); i++) {
            fr.drawStringWithShadow(lines.get(i), tx, ty + i * LINE_HEIGHT, TOOLTIP_TEXT);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }
}
