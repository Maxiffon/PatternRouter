package patternrouter.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import patternrouter.common.Items;

/**
 * Where each player put patterns of a given kind (recipe category + circuit/mold/lens, or output), so the panel can
 * offer it again even when the machine behind the interface is invisible to the mod (chests, conduits,
 * transvectors). Saved with the world in data/patternrouter_choices.dat.
 */
public final class RememberedChoices extends WorldSavedData {

    private static final String NAME = "patternrouter_choices";
    /** Remembered providers per key, most recent first. */
    private static final int PER_KEY = 3;
    /** Kinds of patterns remembered per player; keeps the save small whatever a client sends. */
    private static final int MAX_KINDS_PER_PLAYER = 1000;

    /** player -> key -> positions {dim, x, y, z}. */
    private final Map<UUID, Map<String, List<int[]>>> data = new HashMap<>();

    public RememberedChoices(String name) {
        super(name);
    }

    /** The server's choices, or null while no world is loaded. */
    static RememberedChoices get() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null || server.worldServers.length == 0) return null;
        World w = server.worldServers[0];
        RememberedChoices c = (RememberedChoices) w.mapStorage.loadData(RememberedChoices.class, NAME);
        if (c == null) {
            c = new RememberedChoices(NAME);
            w.mapStorage.setData(NAME, c);
        }
        return c;
    }

    /**
     * What the player is placing: recipe category plus its circuit/mold/lens, or the output if no category. Empty if
     * neither is known.
     */
    static String keyFor(String category, List<ItemStack> nonConsumables, ItemStack output) {
        if (category != null && !category.isEmpty()) {
            List<String> setup = new ArrayList<>();
            for (ItemStack s : nonConsumables) {
                if (s != null && s.getItem() != null) setup.add(Items.idAndDamage(s));
            }
            Collections.sort(setup);
            return "cat:" + category + "|" + String.join(",", setup);
        }
        return output == null || output.getItem() == null ? ""
            : "out:" + Item.itemRegistry.getNameForObject(output.getItem()) + ":" + output.getItemDamage();
    }

    boolean isRemembered(UUID player, String key, int[] position) {
        Map<String, List<int[]>> m = data.get(player);
        List<int[]> l = m == null ? null : m.get(key);
        if (l == null) return false;
        for (int[] p : l) if (same(p, position)) return true;
        return false;
    }

    void remember(UUID player, String key, int[] pos) {
        Map<String, List<int[]>> kinds = data.computeIfAbsent(player, p -> new LinkedHashMap<>());
        // Most recently used kind last; beyond the cap the longest unused ones are forgotten.
        List<int[]> l = kinds.remove(key);
        if (l == null) l = new ArrayList<>();
        kinds.put(key, l);
        l.removeIf(p -> same(p, pos));
        l.add(0, pos);
        while (l.size() > PER_KEY) l.remove(l.size() - 1);
        Iterator<String> oldest = kinds.keySet()
            .iterator();
        while (kinds.size() > MAX_KINDS_PER_PLAYER && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        markDirty();
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        data.clear();
        NBTTagList players = tag.getTagList("players", 10);
        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound p = players.getCompoundTagAt(i);
            UUID id;
            try {
                id = UUID.fromString(p.getString("uuid"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Map<String, List<int[]>> m = new LinkedHashMap<>();
            NBTTagList entries = p.getTagList("entries", 10);
            for (int j = 0; j < entries.tagCount(); j++) {
                NBTTagCompound e = entries.getCompoundTagAt(j);
                int[] flat = e.getIntArray("pos");
                List<int[]> l = new ArrayList<>();
                for (int k = 0; k + 3 < flat.length; k += 4)
                    l.add(new int[] { flat[k], flat[k + 1], flat[k + 2], flat[k + 3] });
                if (!l.isEmpty()) m.put(e.getString("key"), l);
            }
            data.put(id, m);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList players = new NBTTagList();
        for (Map.Entry<UUID, Map<String, List<int[]>>> p : data.entrySet()) {
            NBTTagCompound pt = new NBTTagCompound();
            pt.setString(
                "uuid",
                p.getKey()
                    .toString());
            NBTTagList entries = new NBTTagList();
            for (Map.Entry<String, List<int[]>> e : p.getValue()
                .entrySet()) {
                NBTTagCompound et = new NBTTagCompound();
                et.setString("key", e.getKey());
                int[] flat = new int[e.getValue()
                    .size() * 4];
                int k = 0;
                for (int[] pos : e.getValue()) for (int v : pos) flat[k++] = v;
                et.setIntArray("pos", flat);
                entries.appendTag(et);
            }
            pt.setTag("entries", entries);
            players.appendTag(pt);
        }
        tag.setTag("players", players);
    }
}
