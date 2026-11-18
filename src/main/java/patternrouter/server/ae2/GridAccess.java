package patternrouter.server.ae2;

import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerPatternTerm;

/** The terminal's ME network, the networks linked to it and the player's permissions there. */
public final class GridAccess {

    private GridAccess() {}

    public static IGrid gridOf(ContainerPatternTerm term) {
        Object target = term.getTarget();
        IGridNode node = null;
        if (target instanceof IActionHost actionHost) node = actionHost.getActionableNode();
        if (node == null && target instanceof IGridHost gridHost) node = gridHost.getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    /** The grid and every grid linked to it by Pattern Repeaters; just the grid on AE2 builds without them. */
    public static List<IGrid> linkedGrids(IGrid grid) {
        try {
            return PatternRepeaters.reachable(grid);
        } catch (Throwable t) {
            return Collections.singletonList(grid);
        }
    }

    /** The provider is in the terminal's network or one linked to it by Pattern Repeaters. */
    public static boolean isInTerminalNetworks(IInterfaceViewable view, IGrid terminalGrid) {
        IGridNode node = view.getGridNode(ForgeDirection.UNKNOWN);
        if (node == null || node.getGrid() == null) return false;
        return node.getGrid() == terminalGrid || linkedGrids(terminalGrid).contains(node.getGrid());
    }

    /** Build permission in the provider's own network (it may be behind a Pattern Repeater). */
    public static boolean canBuildAt(IInterfaceViewable view, EntityPlayerMP player) {
        IGridNode node = view.getGridNode(ForgeDirection.UNKNOWN);
        return node != null && node.getGrid() != null && canBuild(node.getGrid(), player);
    }

    public static boolean canBuild(IGrid grid, EntityPlayerMP player) {
        return hasPermission(grid, player, SecurityPermissions.BUILD);
    }

    public static boolean canExtract(IGrid grid, EntityPlayerMP player) {
        return hasPermission(grid, player, SecurityPermissions.EXTRACT);
    }

    private static boolean hasPermission(IGrid grid, EntityPlayerMP player, SecurityPermissions permission) {
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        return security == null || security.hasPermission(player, permission);
    }

    /** By identity: grids don't define equality. */
    public static boolean containsSame(List<IGrid> grids, IGrid grid) {
        for (IGrid g : grids) if (g == grid) return true;
        return false;
    }
}
