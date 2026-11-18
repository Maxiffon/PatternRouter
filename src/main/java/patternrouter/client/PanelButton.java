package patternrouter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

import org.lwjgl.input.Mouse;

/**
 * Button in the panel's own style. Vanilla buttons draw a 20 px tall texture and lose their bottom edge when
 * shorter; this one draws a full bevel at any size, lighter on hover, flat and dim when disabled, pressed look
 * while the mouse is held on it.
 */
final class PanelButton extends GuiButton {

    private static final int FILL = 0xFFC6C6C6;
    private static final int FILL_HOVER = 0xFFDCDCF0;
    private static final int FILL_DISABLED = 0xFFA8A8A8;
    private static final int EDGE_DISABLED = 0xFF6E6E6E;
    private static final int TEXT_HOVER = 0x1E1E8C;
    private static final int TEXT_DISABLED = 0x7A7A7A;
    private static final int FONT_HEIGHT = 8;

    /** Drawn this far forward: above item icons when it sits on a box over the list. */
    float z;

    PanelButton(int id, int w, int h, String text) {
        super(id, 0, 0, w, h, text);
    }

    @Override
    public void drawButton(Minecraft mc, int mx, int my) {
        if (!visible) return;
        if (z != 0) org.lwjgl.opengl.GL11.glTranslatef(0, 0, z);
        try {
            draw(mc, mx, my);
        } finally {
            if (z != 0) org.lwjgl.opengl.GL11.glTranslatef(0, 0, -z);
        }
    }

    private void draw(Minecraft mc, int mx, int my) {
        boolean hover = AeStyle.isOver(this, mx, my);
        field_146123_n = hover;
        boolean pressed = hover && enabled && Mouse.isButtonDown(0);
        int fill = !enabled ? FILL_DISABLED : hover ? FILL_HOVER : FILL;
        if (enabled) AeStyle.drawRaised(xPosition, yPosition, width, height, fill, pressed);
        else {
            Gui.drawRect(xPosition, yPosition, xPosition + width, yPosition + height, EDGE_DISABLED);
            Gui.drawRect(xPosition + 1, yPosition + 1, xPosition + width - 1, yPosition + height - 1, fill);
        }
        FontRenderer fr = mc.fontRenderer;
        String s = fr.trimStringToWidth(displayString, width - 4);
        int color = !enabled ? TEXT_DISABLED : hover ? TEXT_HOVER : AeStyle.GUI_TEXT;
        int off = pressed ? 1 : 0;
        fr.drawString(
            s,
            xPosition + (width - fr.getStringWidth(s)) / 2 + off,
            yPosition + (height - FONT_HEIGHT) / 2 + off,
            color);
    }
}
