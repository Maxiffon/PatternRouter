package patternrouter.common;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import gregtech.api.enums.ItemList;

/** Item comparisons shared by client and server. */
public final class Items {

    private Items() {}

    /** Same item, wildcard damage, or a shared ore dictionary name (e.g. any lens of the same colour). */
    public static boolean matches(ItemStack need, ItemStack have) {
        if (need == null || have == null || need.getItem() == null || have.getItem() == null) return false;
        if (need.getItem() == have.getItem()) {
            int dn = need.getItemDamage();
            if (dn == OreDictionary.WILDCARD_VALUE || dn == have.getItemDamage()) return true;
        }
        for (int a : OreDictionary.getOreIDs(need)) {
            for (int b : OreDictionary.getOreIDs(have)) if (a == b) return true;
        }
        return false;
    }

    /** Same item, damage and NBT. */
    public static boolean sameItem(ItemStack a, ItemStack b) {
        return a != null && b != null && a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
    }

    public static boolean isProgrammedCircuit(ItemStack stack) {
        try {
            return ItemList.Circuit_Integrated.getItem() == stack.getItem();
        } catch (Throwable t) {
            return false;
        }
    }

    /** "numeric id:damage" of the stack's item. */
    public static String idAndDamage(ItemStack stack) {
        return Item.getIdFromItem(stack.getItem()) + ":" + stack.getItemDamage();
    }

    /** "numeric id:*" (any damage) of the stack's item. */
    public static String idAnyDamage(ItemStack stack) {
        return Item.getIdFromItem(stack.getItem()) + ":" + OreDictionary.WILDCARD_VALUE;
    }
}
