package patternrouter.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;

import codechicken.nei.ItemPanels;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.PanelWidget;
import codechicken.nei.Widget;
import codechicken.nei.api.API;
import codechicken.nei.api.INEIGuiAdapter;

/** NEI integration. Only loaded when NEI is present. */
public final class NeiCompat {

    private NeiCompat() {}

    /** Ask NEI not to draw its item list under the panel or take clicks there. */
    public static void register(ClientPanel panel) {
        API.registerNEIGuiHandler(new INEIGuiAdapter() {

            @Override
            public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) {
                return panel.overlaps(gui, x, y, w, h);
            }
        });
    }

    /**
     * NEI recomputes hidden slots on its own schedule (grid rendering cache); this marks the item and bookmark grids
     * changed so the next frame recomputes them whatever the cache mode.
     */
    public static void refreshMask() {
        try {
            ItemPanels.itemPanel.getGrid()
                .onItemsChanged();
            ItemPanels.bookmarkPanel.getGrid()
                .onItemsChanged();
        } catch (Throwable ignored) {}
    }

    /** Page bars ("< 1/3 >") of the item and bookmark panels as {x, y, w, h}; empty when NEI is hidden. */
    public static List<int[]> pageBars() {
        List<int[]> out = new ArrayList<>();
        try {
            if (!NEIClientConfig.isEnabled() || NEIClientConfig.isHidden()) return out;
            addBar(out, ItemPanels.itemPanel);
            if (!NEIClientConfig.isBookmarkPanelHidden()) addBar(out, ItemPanels.bookmarkPanel);
        } catch (Throwable ignored) {
            // NEI internals changed: just don't avoid the bar.
        }
        return out;
    }

    private static void addBar(List<int[]> out, PanelWidget<?> panel) {
        if (panel == null || panel.w <= 0) return;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (Widget w : new Widget[] { panel.pagePrev, panel.pageNext, panel.pageLabel }) {
            if (w == null || w.w <= 0 || w.h <= 0) continue;
            x0 = Math.min(x0, w.x);
            y0 = Math.min(y0, w.y);
            x1 = Math.max(x1, w.x + w.w);
            y1 = Math.max(y1, w.y + w.h);
        }
        if (x0 < x1 && y0 < y1) out.add(new int[] { x0, y0, x1 - x0, y1 - y0 });
    }
}
