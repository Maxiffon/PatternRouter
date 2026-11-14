package patternrouter.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

/**
 * Where each player put patterns of a given kind (recipe category + circuit/mold/lens, or output), so the panel can
 * offer it again even when the machine behind the interface is invisible to the mod (chests, conduits,
 * transvectors). Saved with the world in data/patternrouter_choices.dat.
 */
public final class Choices extends WorldSavedData {

    private static final String NAME = "patternrouter_choices";
    /** Remembered providers per key, most recent first. */
    private static final int PER_KEY = 3;

    /** player -> key -> positions {dim, x, y, z}. */
    private final Map<UUID, Map<String, List<int[]>>> data = new HashMap<>();

    public Choices(String name) {
        super(name);
    }

    static Choices get() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null || server.worldServers.length == 0) return null;
        World w = server.worldServers[0];
        Choices c = (Choices) w.mapStorage.loadData(Choices.class, NAME);
        if (c == null) {
            c = new Choices(NAME);
            w.mapStorage.setData(NAME, c);
        }
        return c;
    }

    List<int[]> remembered(UUID player, String key) {
        Map<String, List<int[]>> m = data.get(player);
        List<int[]> l = m == null ? null : m.get(key);
        return l == null ? Collections.emptyList() : l;
    }

    void remember(UUID player, String key, int[] pos) {
        List<int[]> l = data.computeIfAbsent(player, p -> new HashMap<>())
            .computeIfAbsent(key, k -> new ArrayList<>());
        l.removeIf(p -> same(p, pos));
        l.add(0, pos);
        while (l.size() > PER_KEY) l.remove(l.size() - 1);
        markDirty();
    }

    static boolean same(int[] a, int[] b) {
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
            Map<String, List<int[]>> m = new HashMap<>();
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
