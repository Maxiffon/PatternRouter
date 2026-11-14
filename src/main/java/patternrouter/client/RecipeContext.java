package patternrouter.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;

/** Last recipe transferred from NEI: category (GT machine type), non-consumed inputs, result. */
public final class RecipeContext {

    /** How long the recipe is remembered; patterns are usually encoded right after the transfer. */
    private static final long TTL_MS = 30L * 60 * 1000;

    public static String category = "";
    public static List<ItemStack> nonConsumables = Collections.emptyList();
    public static ItemStack result;
    /** NEI tab title in the player's language, e.g. "Fluid Solidifier". */
    public static String machineName = "";
    public static long time;

    private RecipeContext() {}

    public static void capture(IRecipeHandler handler, int recipeIndex) {
        try {
            String id = handler.getOverlayIdentifier();
            List<ItemStack> nc = new ArrayList<>();
            List<PositionedStack> ingredients = handler.getIngredientStacks(recipeIndex);
            if (ingredients != null) {
                for (PositionedStack ps : ingredients) {
                    // GT shows non-consumed inputs (molds, circuits, lenses) in NEI with stack size 0.
                    if (ps != null && ps.item != null && ps.item.stackSize == 0) {
                        ItemStack c = ps.item.copy();
                        c.stackSize = 1;
                        nc.add(c);
                    }
                }
            }
            PositionedStack r = handler.getResultStack(recipeIndex);
            category = id == null ? "" : id;
            String title;
            try {
                title = handler.getRecipeName();
            } catch (Throwable t) {
                title = "";
            }
            machineName = title == null ? ""
                : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(title);
            nonConsumables = nc;
            result = r == null || r.item == null ? null : r.item.copy();
            time = System.currentTimeMillis();
        } catch (Throwable t) {
            category = "";
            nonConsumables = Collections.emptyList();
            result = null;
        }
    }

    /** Forget the recipe (e.g. when leaving a server). */
    public static void clear() {
        category = "";
        nonConsumables = Collections.emptyList();
        result = null;
        machineName = "";
        time = 0;
    }

    /**
     * The recipe is fresh and its result matches the pattern output (otherwise the pattern was made by hand). When
     * either side is unknown, don't guess: the server infers the machine from the pattern itself.
     */
    public static boolean appliesTo(ItemStack patternOutput) {
        if (time == 0 || System.currentTimeMillis() - time > TTL_MS) return false;
        if (result == null || patternOutput == null) return false;
        return result.getItem() == patternOutput.getItem()
            && (result.getItemDamage() == patternOutput.getItemDamage() || result.getItemDamage() == 32767);
    }
}
