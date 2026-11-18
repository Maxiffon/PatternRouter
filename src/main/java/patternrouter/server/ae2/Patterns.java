package patternrouter.server.ae2;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEStack;
import appeng.container.implementations.ContainerPatternTerm;
import appeng.items.misc.ItemEncodedPattern;

/** Encoded patterns: the terminal's output slot and pattern outputs. */
public final class Patterns {

    /** Slot of the encoded pattern in the terminal's pattern inventory (0 holds the blank patterns). */
    public static final int ENCODED_OUTPUT_SLOT = 1;

    /** Decoded outputs of patterns already seen; ItemStack has identity equality, entries go with the stacks. */
    private static final Map<ItemStack, ItemStack> DECODED = new WeakHashMap<>();

    private Patterns() {}

    /** The terminal's blank/encoded pattern slots, or null if it has no encoded output slot. */
    public static IInventory patternSlots(ContainerPatternTerm term) {
        IInventory inv = term.getPatternTerminal()
            .getInventoryByName(StorageName.CRAFTING_PATTERN.getName());
        return inv == null || inv.getSizeInventory() <= ENCODED_OUTPUT_SLOT ? null : inv;
    }

    public static ItemStack encodedPattern(ContainerPatternTerm term) {
        IInventory inv = patternSlots(term);
        if (inv == null) return null;
        ItemStack s = inv.getStackInSlot(ENCODED_OUTPUT_SLOT);
        return s != null && s.getItem() instanceof ItemEncodedPattern ? s : null;
    }

    /**
     * Output of a pattern, e.g. one sitting in some interface, for conflict search over the whole network. Reads the
     * first "out" entry of the pattern NBT directly: decoding a crafting pattern makes AE2 search all workbench
     * recipes, which over thousands of patterns froze big servers for seconds. Full decoding (cached) only as a
     * fallback.
     */
    public static ItemStack storedOutput(ItemStack pattern, World world) {
        if (pattern == null) return null;
        NBTTagCompound tag = pattern.getTagCompound();
        if (tag != null) {
            NBTTagList outs = tag.getTagList("out", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < outs.tagCount(); i++) {
                NBTTagCompound t = outs.getCompoundTagAt(i);
                if (t == null || t.hasNoTags()) continue;
                try {
                    ItemStack s = ItemStack.loadItemStackFromNBT(t);
                    if (s != null && s.getItem() != null) return s;
                } catch (Throwable ignored) {}
                break; // first output is not a plain item (fluid, new format): decode instead
            }
        }
        if (DECODED.containsKey(pattern)) return DECODED.get(pattern);
        ItemStack out = decodedOutput(pattern, world);
        DECODED.put(pattern, out);
        return out;
    }

    /**
     * Main pattern output, decoded in the server world: ItemEncodedPattern.getOutput() uses the AE2 client proxy
     * world and returns null on a server.
     */
    private static ItemStack decodedOutput(ItemStack pattern, World world) {
        if (pattern == null || !(pattern.getItem() instanceof ICraftingPatternItem patternItem) || world == null)
            return null;
        try {
            ICraftingPatternDetails d = patternItem.getPatternForItem(pattern, world);
            if (d == null) return null;
            IAEStack<?>[] outs = d.getCondensedAEOutputs();
            if (outs == null || outs.length == 0 || outs[0] == null) return null;
            return outs[0].getItemStackForNEI();
        } catch (Throwable t) {
            return null;
        }
    }
}
