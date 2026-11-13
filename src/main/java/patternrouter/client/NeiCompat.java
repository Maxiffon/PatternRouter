package patternrouter.client;

import net.minecraft.client.gui.inventory.GuiContainer;

import codechicken.nei.api.API;
import codechicken.nei.api.INEIGuiAdapter;

/** Ask NEI not to draw its item list under the panel or take clicks there. */
public final class NeiCompat {

    private NeiCompat() {}

    public static void register(ClientPanel panel) {
        API.registerNEIGuiHandler(new INEIGuiAdapter() {

            @Override
            public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) {
                return panel.overlaps(gui, x, y, w, h);
            }
        });
    }
}
