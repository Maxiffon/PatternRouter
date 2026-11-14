package patternrouter.server;

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
final class Repeaters {

    /** Upper bound on networks followed through repeaters. */
    private static final int MAX_GRIDS = 32;

    private Repeaters() {}

    /** The network itself first, then every network linked to it by repeaters (transitively). */
    static List<IGrid> reachable(IGrid main) {
        List<IGrid> out = new ArrayList<>();
        out.add(main);
        try {
            for (int i = 0; i < out.size() && out.size() < MAX_GRIDS; i++) {
                for (IGridNode node : out.get(i)
                    .getMachines(PartPatternRepeater.class)) {
                    Object m = node.getMachine();
                    if (!(m instanceof PartPatternRepeater)) continue;
                    PartPatternRepeater pair = ((PartPatternRepeater) m).getPair();
                    IGridNode pn = pair == null ? null : pair.getGridNode();
                    IGrid g = pn == null ? null : pn.getGrid();
                    if (g != null && !containsSame(out, g)) out.add(g);
                }
            }
        } catch (Throwable ignored) {
            // No Pattern Repeater in this AE2 build.
        }
        return out;
    }

    static boolean containsSame(List<IGrid> grids, IGrid g) {
        for (IGrid x : grids) if (x == g) return true;
        return false;
    }
}
