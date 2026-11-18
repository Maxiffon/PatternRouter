package patternrouter.server;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * Which multiblock controller a GT hatch belongs to.
 * <p>
 * Newer GT stores it in {@code MTEHatch.ae2CraftingIconOwner} (read by reflection, no mixin, so older GT still
 * loads). Older GT (e.g. GTNH 2.9.0-RC-1) lacks the field, and some multiblocks leave it empty: then the loaded
 * multiblocks are scanned for the hatch in their input lists, once per tick.
 */
final class Owners {

    private static final Field OWNER_FIELD = findField();

    /**
     * Last scan of each world, valid for the tick it was made in. Per world, so providers in several dimensions
     * don't make one request rescan the worlds in turn.
     */
    private static final Map<World, Scan> SCANS = new IdentityHashMap<>();

    private static final class Scan {

        final long tick;
        final Map<Object, ChunkCoordinates> owners;

        Scan(long tick, Map<Object, ChunkCoordinates> owners) {
            this.tick = tick;
            this.owners = owners;
        }
    }

    private Owners() {}

    private static Field findField() {
        try {
            Field f = MTEHatch.class.getDeclaredField("ae2CraftingIconOwner");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Controller coordinates of a formed multiblock using this hatch, or null. */
    static ChunkCoordinates ownerOf(IMetaTileEntity hatch, World world) {
        if (!(hatch instanceof MTEHatch)) return null;
        if (OWNER_FIELD != null) {
            try {
                ChunkCoordinates c = (ChunkCoordinates) OWNER_FIELD.get(hatch);
                if (c != null) return c;
                // Some multiblocks (GT++, e.g. Industrial Maceration Stack) set the crafting icon without the owner:
                // fall through to the scan.
            } catch (Throwable ignored) {}
        }
        return world == null ? null : scan(world).get(hatch);
    }

    private static Map<Object, ChunkCoordinates> scan(World world) {
        long tick = world.getTotalWorldTime();
        Scan last = SCANS.get(world);
        if (last != null && last.tick == tick) return last.owners;
        Map<Object, ChunkCoordinates> map = new IdentityHashMap<>();
        for (Object o : new ArrayList<Object>(world.loadedTileEntityList)) {
            if (!(o instanceof IGregTechTileEntity)) continue;
            IMetaTileEntity mte = ((IGregTechTileEntity) o).getMetaTileEntity();
            if (!(mte instanceof MTEMultiBlockBase)) continue;
            MTEMultiBlockBase multi = (MTEMultiBlockBase) mte;
            TileEntity te = (TileEntity) o;
            ChunkCoordinates c = new ChunkCoordinates(te.xCoord, te.yCoord, te.zCoord);
            addAll(map, multi.mInputBusses, c);
            addAll(map, multi.mInputHatches, c);
            addAll(map, multi.mDualInputHatches, c);
        }
        SCANS.put(world, new Scan(tick, map));
        return map;
    }

    /** A world unloads: forget its multiblocks. */
    static void forget(World world) {
        SCANS.remove(world);
    }

    private static void addAll(Map<Object, ChunkCoordinates> map, Collection<?> hatches, ChunkCoordinates c) {
        if (hatches == null) return;
        for (Object h : hatches) if (h != null) map.put(h, c);
    }
}
