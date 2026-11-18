package patternrouter.server.machine;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Tile entity lookups that never load a chunk. */
public final class Worlds {

    private Worlds() {}

    public static boolean isLoaded(World world, ChunkCoordinates at) {
        return world != null && world.blockExists(at.posX, at.posY, at.posZ);
    }

    /** The tile entity at the position, or null if there is none or its chunk is not loaded. */
    public static TileEntity loadedTile(World world, int x, int y, int z) {
        return world != null && world.blockExists(x, y, z) ? world.getTileEntity(x, y, z) : null;
    }

    public static TileEntity loadedTile(World world, ChunkCoordinates at) {
        return loadedTile(world, at.posX, at.posY, at.posZ);
    }

    /** The loaded tile entity next to {@code from} on side {@code side}, or null. */
    public static TileEntity neighbour(TileEntity from, ForgeDirection side) {
        return loadedTile(
            from.getWorldObj(),
            from.xCoord + side.offsetX,
            from.yCoord + side.offsetY,
            from.zCoord + side.offsetZ);
    }
}
