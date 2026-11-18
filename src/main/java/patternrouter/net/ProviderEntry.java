package patternrouter.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/** One row of the candidate list: a pattern provider and what the server found out about it. */
public class ProviderEntry {

    /** Bits of {@link #flags}; sent as one byte. */
    public static final int RECIPE_MATCH = 1; // the machine runs recipes of this category
    public static final int SETUP_FITS = 2; // the machine's circuits/molds/lenses select this recipe
    public static final int SETUP_CONFLICTS = 4; // right machine, its setup starts another recipe
    public static final int HAS_SAME_OUTPUT = 8; // already holds a pattern with the same output
    public static final int REMEMBERED = 16; // the player put this kind of pattern here before
    public static final int NO_FLUIDS = 32; // the pattern has fluid inputs, the provider can't push fluids
    public static final int BY_NAME = 64; // machine not seen, but the interface name matches the NEI tab
    public static final int OTHER_MODE = 128; // the machine runs this recipe in a mode it is not set to

    private static final int MAX_FREE_SLOTS = 65535;
    private static final int MAX_SETUP_ITEMS = 6;

    public ItemStack icon;
    /** Player-given name or translation key; the client translates. */
    public String name = "";
    /** AE2's caption (CRIBs) as chat JSON; the client turns it into text. */
    public String suffix = "";
    public int freeSlots;
    /**
     * No free slot, but a capacity card still fits. Not part of the entry bytes: {@link CandidateListMessage} sends it
     * after the list, where clients before 1.4 don't read.
     */
    public boolean takesCard;
    public int flags;
    /** Provider position, for highlighting it in the world. */
    public int dim, x, y, z;
    /** What the machine is set up with (circuit, mold, lens); the client builds the caption. */
    public List<ItemStack> setupItems = new ArrayList<>();

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    void write(ByteBuf buf) {
        ByteBufUtils.writeItemStack(buf, icon);
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, suffix);
        buf.writeShort(Math.min(freeSlots, MAX_FREE_SLOTS));
        buf.writeByte(flags);
        buf.writeInt(dim);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        int n = Math.min(setupItems.size(), MAX_SETUP_ITEMS);
        buf.writeByte(n);
        for (int k = 0; k < n; k++) ByteBufUtils.writeItemStack(buf, setupItems.get(k));
    }

    static ProviderEntry read(ByteBuf buf) {
        ProviderEntry e = new ProviderEntry();
        e.icon = ByteBufUtils.readItemStack(buf);
        e.name = ByteBufUtils.readUTF8String(buf);
        e.suffix = ByteBufUtils.readUTF8String(buf);
        e.freeSlots = buf.readUnsignedShort();
        e.flags = buf.readUnsignedByte();
        e.dim = buf.readInt();
        e.x = buf.readInt();
        e.y = buf.readInt();
        e.z = buf.readInt();
        int n = buf.readUnsignedByte();
        for (int k = 0; k < n; k++) {
            ItemStack s = ByteBufUtils.readItemStack(buf);
            if (s != null) e.setupItems.add(s);
        }
        return e;
    }
}
