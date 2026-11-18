package patternrouter.server.recipe;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;

/**
 * Patterns that skip the vacuum freezer: "dust -> ingot" sent to an EBF whose hot ingot the player moves to a freezer
 * by other means (an import bus with an ore dictionary card). No machine makes the ingot from the dust, so the machine
 * to look for is the one making the hot ingot.
 */
public final class SkippedCooling {

    private SkippedCooling() {}

    /**
     * The pattern with the item the freezer cools into its output (scaled to the pattern), or null when no single-item
     * freezer recipe makes the output or the pattern already holds that item.
     */
    public static PatternContents beforeFreezer(PatternContents contents) {
        ItemStack out = contents.outputItem();
        if (out == null || out.stackSize <= 0 || contents.isCraftingPattern()) return null;
        try {
            for (GTRecipe r : gregtech.api.recipe.RecipeMaps.vacuumFreezerRecipes.getAllRecipes()) {
                ItemStack hot = singleItemInput(r);
                ItemStack made = matchingOutput(r, out);
                if (hot == null || made == null || out.stackSize % made.stackSize != 0) continue;
                if (contents.hasItemInput(hot)) return null;
                ItemStack scaled = hot.copy();
                scaled.stackSize = hot.stackSize * (out.stackSize / made.stackSize);
                return contents.withOutputItem(scaled);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * The NEI tab is the vacuum freezer's (it is kept for the same output), yet the pattern is no freezer recipe: the
     * player transferred "hot ingot -> ingot" and put the dust in.
     */
    public static boolean isFreezerTabOfShortcut(String category, PatternContents contents,
        List<ItemStack> nonConsumables) {
        RecipeMap<?> freezer = gregtech.api.recipe.RecipeMaps.vacuumFreezerRecipes;
        return RecipeMaps.forCategory(category) == freezer && contents.recipeIn(freezer, nonConsumables) == null;
    }

    /** The only consumed input of a recipe without fluid inputs, or null. */
    private static ItemStack singleItemInput(GTRecipe r) {
        if (r.mFluidInputs != null) for (FluidStack f : r.mFluidInputs) if (f != null && f.amount > 0) return null;
        if (r.mInputs == null) return null;
        ItemStack only = null;
        for (ItemStack in : r.mInputs) {
            if (in == null || in.getItem() == null || in.stackSize <= 0) continue;
            if (only != null) return null;
            only = in;
        }
        return only;
    }

    private static ItemStack matchingOutput(GTRecipe r, ItemStack wanted) {
        if (r.mOutputs == null) return null;
        for (ItemStack o : r.mOutputs) if (o != null && o.stackSize > 0 && o.isItemEqual(wanted)) return o;
        return null;
    }
}
