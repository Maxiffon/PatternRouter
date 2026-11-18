package patternrouter.server.machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.IFluidHandler;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.IInterfaceViewable;
import appeng.helpers.IInterfaceHost;
import appeng.parts.AEBasePart;
import appeng.util.InventoryAdaptor;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.recipe.RecipeMap;
import patternrouter.PatternRouter;
import patternrouter.server.ae2.GridAccess;

/** The GT machine behind a pattern provider: the blocks an interface feeds, subnets, the multiblock of a hatch. */
public final class MachineLocator {

    /** A network this big is somebody's main network, not a subnet feeding one machine group. */
    private static final int SUBNET_MAX_NODES = 4096;

    /** Per request: deliveryTargets() of each provider (asked for by several checks). */
    private static final Map<IInterfaceViewable, List<TileEntity>> TARGETS = new IdentityHashMap<>();
    /** Per request: GT machine inputs a subnet reaches (one grid scan per subnet). */
    private static final Map<IGrid, List<TileEntity>> SUBNETS = new IdentityHashMap<>();
    /** Per request: the terminal's network and those linked by Pattern Repeaters. */
    private static List<IGrid> lookupGrids = Collections.emptyList();

    private MachineLocator() {}

    /** Start of a request over these networks: none of them counts as a subnet. */
    public static void beginLookup(List<IGrid> networks) {
        lookupGrids = networks;
    }

    /** End of a request: drop the tiles and grids it saw. */
    public static void endLookup() {
        TARGETS.clear();
        SUBNETS.clear();
        lookupGrids = Collections.emptyList();
    }

    /** Recipe maps of the machine this interface/CRIB feeds, split by the machines' current mode. */
    public static MachineModes recipeMapsFed(IInterfaceViewable view) {
        MachineModes modes = new MachineModes();
        TileEntity self = view.getTileEntity();
        if (self == null) return modes;
        // CRIB and other GT hatches: the machine is the multiblock controller.
        addRecipeMaps(modes, self, true);
        // AE2 ME interface: the machine is the neighbour it outputs into.
        if (view instanceof IInterfaceHost) {
            for (TileEntity n : deliveryTargets(view)) addRecipeMaps(modes, n, true);
        }
        return modes;
    }

    private static void addRecipeMaps(MachineModes into, TileEntity te, boolean followHatchOwner) {
        if (!(te instanceof IGregTechTileEntity gtTile)) return;
        IMetaTileEntity mte = gtTile.getMetaTileEntity();
        if (mte instanceof RecipeMapWorkable workable) {
            Collection<RecipeMap<?>> available = workable.getAvailableRecipeMaps();
            if (!available.isEmpty()) {
                into.add(available, currentRecipeMap(workable));
                if (!into.machines.contains(mte)) into.machines.add(mte);
                return;
            }
        }
        if (mte instanceof MTEHatch && followHatchOwner) {
            addRecipeMaps(into, HatchOwners.controllerTile(mte, te), false);
        }
    }

    /** The map the machine's current mode runs; null if it can't say (no modes, or a recipe map per circuit). */
    private static RecipeMap<?> currentRecipeMap(RecipeMapWorkable workable) {
        try {
            return workable.getRecipeMap();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A CRIB, or a bus next to an ME interface, that GT has not attached to a formed multiblock. */
    public static boolean feedsUnformedMultiblock(IInterfaceViewable view) {
        List<TileEntity> tiles = new ArrayList<>();
        tiles.add(view.getTileEntity());
        if (view instanceof IInterfaceHost) tiles.addAll(deliveryTargets(view));
        for (TileEntity te : tiles) {
            if (te instanceof IGregTechTileEntity gtTile && gtTile.getMetaTileEntity() instanceof MTEHatchInputBus bus
                && HatchOwners.ownerOf(bus, te.getWorldObj()) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where an ME interface delivers: the blocks it faces and, when one of them belongs to another ME network (a
     * subnet), the GT machine inputs that subnet feeds.
     */
    public static List<TileEntity> deliveryTargets(IInterfaceViewable view) {
        List<TileEntity> known = TARGETS.get(view);
        if (known == null) {
            known = findDeliveryTargets(view);
            TARGETS.put(view, known);
        }
        return known;
    }

    private static List<TileEntity> findDeliveryTargets(IInterfaceViewable view) {
        TileEntity self = view.getTileEntity();
        if (!(view instanceof IInterfaceHost host) || self == null) return new ArrayList<>();
        EnumSet<ForgeDirection> sides = host.getTargets();
        List<TileEntity> out = new ArrayList<>();
        if (sides == null) return out;
        IGrid own = ownGrid(view);
        for (ForgeDirection side : sides) {
            TileEntity te = Worlds.neighbour(self, side);
            if (te == null) continue;
            out.add(te);
            if (te instanceof IGregTechTileEntity || !(te instanceof IGridHost gridHost)) continue;
            IGrid sub = null;
            try {
                IGridNode n = gridHost.getGridNode(side.getOpposite());
                if (n != null) sub = n.getGrid();
            } catch (Throwable ignored) {}
            // Unknown own network: don't guess, it could be the main network with every machine of the base.
            if (own != null && sub != null
                && sub != own
                && !GridAccess.containsSame(lookupGrids, sub)
                && acceptsPushFrom(te, side.getOpposite())) {
                for (TileEntity t : subnetInputs(sub)) if (!out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    /**
     * The neighbour takes what an interface pushes into it from this side: the same inventory check AE2's interface
     * uses, plus AE2 interfaces and fluid receivers (fluid-only patterns go to a subnet's fluid interface). A cable
     * or bus that takes nothing does not lead into a subnet.
     */
    private static boolean acceptsPushFrom(TileEntity te, ForgeDirection side) {
        if (te instanceof IInterfaceHost || te instanceof IFluidHandler) return true;
        try {
            return InventoryAdaptor.getAdaptor(te, side) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IGrid ownGrid(IInterfaceViewable view) {
        try {
            IGridNode own = view.getGridNode(ForgeDirection.UNKNOWN);
            if (own != null) return own.getGrid();
            if (view.getTileEntity() instanceof IGridHost host) {
                IGridNode n = host.getGridNode(ForgeDirection.UNKNOWN);
                if (n != null) return n.getGrid();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * GT inputs fed by a subnet: its own ME input buses/hatches and CRIBs, buses/machines faced by its export buses,
     * storage buses and parts, and those next to its interface blocks.
     */
    private static List<TileEntity> subnetInputs(IGrid sub) {
        List<TileEntity> cached = SUBNETS.get(sub);
        if (cached != null) return cached;
        List<TileEntity> out = new ArrayList<>();
        SUBNETS.put(sub, out);
        try {
            if (sub.getNodes()
                .size() > SUBNET_MAX_NODES) return out;
            for (IGridNode n : sub.getNodes()) {
                IGridHost machine = n.getMachine();
                if (machine instanceof IGregTechTileEntity) {
                    addMachineInput(out, (TileEntity) machine);
                } else if (machine instanceof AEBasePart part) {
                    TileEntity host = part.getTile();
                    ForgeDirection side = part.getSide();
                    if (host != null && side != null && side != ForgeDirection.UNKNOWN) {
                        addMachineInput(out, Worlds.neighbour(host, side));
                    }
                } else if (machine instanceof IInterfaceHost host && machine instanceof TileEntity te) {
                    EnumSet<ForgeDirection> sides = host.getTargets();
                    if (sides == null) continue;
                    for (ForgeDirection side : sides) {
                        TileEntity t = Worlds.neighbour(te, side);
                        if (t != null) addMachineInput(out, t);
                    }
                }
            }
        } catch (Throwable t) {
            PatternRouter.LOG.debug("Subnet scan failed: {}", t.toString());
        }
        return out;
    }

    /** Only machine inputs: an output hatch on a subnet says nothing about what the subnet feeds. */
    private static void addMachineInput(List<TileEntity> out, TileEntity te) {
        if (!(te instanceof IGregTechTileEntity gtTile) || out.contains(te)) return;
        IMetaTileEntity mte = gtTile.getMetaTileEntity();
        if (mte instanceof MTEHatchInputBus || mte instanceof MTEHatchInput || mte instanceof MTEBasicMachine)
            out.add(te);
    }
}
