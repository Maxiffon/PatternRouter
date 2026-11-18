package patternrouter.server.ae2;

import java.util.Locale;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.storage.data.IAEStackType;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.helpers.ICustomNameObject;
import appeng.parts.p2p.PartP2PInterface;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;

/**
 * Pattern providers as the interface terminal sees them. Several methods of IInterfaceViewable are default methods
 * added in newer AE2 GTNH builds; older ones (GTNH 2.9.0-RC-1) lack some, hence the fallbacks.
 */
public final class ProviderCompat {

    private ProviderCompat() {}

    public static String rawName(IInterfaceViewable view) {
        String n;
        try {
            n = view.getRawName();
        } catch (Throwable t) {
            n = view.getName();
        }
        return n == null ? "" : n;
    }

    public static IChatComponent nameSuffix(IInterfaceViewable view) {
        try {
            return view.getNameSuffix();
        } catch (Throwable t) {
            return null;
        }
    }

    public static ItemStack icon(IInterfaceViewable view) {
        try {
            ItemStack d = view.getDisplayRep();
            return d != null ? d : view.getSelfRep();
        } catch (Throwable t) {
            return null;
        }
    }

    public static int numSlots(IInterfaceViewable view) {
        try {
            return view.numSlots();
        } catch (Throwable t) {
            return view.rows() * view.rowSize();
        }
    }

    public static boolean isCraftingProvider(IInterfaceViewable view) {
        try {
            return view.isCraftingPatternProvider();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The player renamed this provider (anvil, name press); otherwise AE2 shows the neighbour block's name. */
    public static boolean isCustomNamed(IInterfaceViewable view) {
        try {
            if (view instanceof ICustomNameObject named) return named.hasCustomName();
            if (view.getTileEntity() instanceof ICustomNameObject named) return named.hasCustomName();
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * A P2P interface output answers getPatterns()/getName() with its input's slots and name (AE2 does this for the
     * interface terminal), so every output looked like a separate interface placed at the output. Patterns put there
     * land in the input and the input pushes them to the block it faces. List the input instead: once, at its own
     * place, with the machine it feeds. An output without an input stays as it is.
     */
    public static IInterfaceViewable p2pInputOf(IInterfaceViewable view) {
        try {
            if (view instanceof PartP2PInterface p2p && p2p.isOutput()) {
                PartP2PInterface in = p2p.getInput();
                if (in != null) return in;
            }
        } catch (Throwable ignored) {}
        return view;
    }

    /** Active in its network; {@code listed} is the node the provider was found by (a P2P output for an input). */
    public static boolean isActive(IInterfaceViewable view, IGridNode listed) {
        if (view == listed.getMachine()) return listed.isActive();
        try {
            IGridNode n = ((IGridHost) view).getGridNode(ForgeDirection.UNKNOWN);
            return n != null && n.isActive();
        } catch (Throwable t) {
            return listed.isActive();
        }
    }

    /**
     * Can this provider push fluids? GT CRIBs say so directly (the Crafting Input Bus can't); AE2 providers list
     * their stack types (a plain interface: items only). Unknown builds: assume yes, never hide wrongly.
     */
    public static boolean acceptsFluids(IInterfaceViewable view) {
        if (view.getTileEntity() instanceof IGregTechTileEntity gtTile
            && gtTile.getMetaTileEntity() instanceof MTEHatchCraftingInputME crib) {
            return crib.supportsFluids();
        }
        try {
            for (IAEStackType<?> t : view.getSupportedStackTypes()) {
                if (t != null && t.getId() != null
                    && t.getId()
                        .toLowerCase(Locale.ROOT)
                        .contains("fluid"))
                    return true;
            }
            // Fluid-capable providers from add-ons that don't report types (dual interfaces). The simple class name
            // only: a package name says nothing about the provider.
            String cls = view.getClass()
                .getSimpleName()
                .toLowerCase(Locale.ROOT);
            return cls.contains("fluid") || cls.contains("dual");
        } catch (Throwable t) {
            return true;
        }
    }

    /** {dimension, x, y, z} of the provider, or null if it has no location. */
    public static int[] position(IInterfaceViewable view) {
        try {
            DimensionalCoord c = view.getLocation();
            return new int[] { c.getDimension(), c.x, c.y, c.z };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The provider's block is still there and loaded. A provider broken or unloaded after the list was sent must not
     * get a pattern: it would be written into a dead tile entity and vanish.
     */
    public static boolean isLoaded(IInterfaceViewable view) {
        TileEntity te = view.getTileEntity();
        if (te == null || te.isInvalid()) return false;
        World w = te.getWorldObj();
        return w != null && w.blockExists(te.xCoord, te.yCoord, te.zCoord)
            && w.getTileEntity(te.xCoord, te.yCoord, te.zCoord) == te;
    }
}
