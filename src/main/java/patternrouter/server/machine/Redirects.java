package patternrouter.server.machine;

import java.lang.reflect.Method;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Blocks that pass what an interface pushes on to another block: Thaumic Tinkerer's Transvector Interface (bound to a
 * block by a focus) and Steve's Factory Manager's inventory relay (the block it faces). Looked up by name, neither mod
 * is required.
 */
final class Redirects {

    /** Relays may be chained; stop on loops and long chains. */
    private static final int MAX_HOPS = 8;

    private static final Method TRANSVECTOR_TARGET = method(
        "thaumic.tinkerer.common.block.tile.transvector.TileTransvectorInterface",
        "getTile");
    private static final Method RELAY_FACING = method("vswe.stevesfactory.blocks.TileEntityRelay", "getFacing");

    private Redirects() {}

    /** The block behind transvectors and relays, or the tile itself if it is neither. */
    static TileEntity follow(TileEntity te) {
        for (int hop = 0; hop < MAX_HOPS && te != null; hop++) {
            TileEntity next = next(te);
            if (next == null || next == te) return te;
            te = next;
        }
        return te;
    }

    private static TileEntity next(TileEntity te) {
        try {
            if (TRANSVECTOR_TARGET != null && TRANSVECTOR_TARGET.getDeclaringClass()
                .isInstance(te)) {
                return (TileEntity) TRANSVECTOR_TARGET.invoke(te);
            }
            if (RELAY_FACING != null && RELAY_FACING.getDeclaringClass()
                .isInstance(te)) {
                ForgeDirection facing = ForgeDirection.getOrientation(((Number) RELAY_FACING.invoke(te)).intValue());
                return facing == ForgeDirection.UNKNOWN ? null : Worlds.neighbour(te, facing);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method method(String className, String name) {
        try {
            Method m = Class.forName(className)
                .getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
