package patternrouter.server.ae2;

import java.util.ArrayList;
import java.util.List;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.parts.misc.PartPatternRepeater;

/**
 * ME networks whose patterns reach the terminal's network through AE2 Pattern Repeater pairs (e.g. a CRIB on a
 * machine that sits in its own network). Kept in its own class: older AE2 builds have no Pattern Repeater, and then
 * only the terminal's network is used.
 */
final class PatternRepeaters {

    private static final int MAX_GRIDS = 32;

    private PatternRepeaters() {}

    /** The network itself first, then every network linked to it by repeaters (transitively). */
    static List<IGrid> reachable(IGrid main) {
        List<IGrid> out = new ArrayList<>();
        out.add(main);
        try {
            for (int i = 0; i < out.size() && out.size() < MAX_GRIDS; i++) {
                for (IGridNode node : out.get(i)
                    .getMachines(PartPatternRepeater.class)) {
                    if (!(node.getMachine() instanceof PartPatternRepeater repeater)) continue;
                    PartPatternRepeater pair = repeater.getPair();
                    IGridNode pairNode = pair == null ? null : pair.getGridNode();
                    IGrid pairGrid = pairNode == null ? null : pairNode.getGrid();
                    if (pairGrid != null && !GridAccess.containsSame(out, pairGrid)) out.add(pairGrid);
                }
            }
        } catch (Throwable ignored) {
            // No Pattern Repeater in this AE2 build.
        }
        return out;
    }
}
