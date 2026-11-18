package patternrouter.server.recipe;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import gregtech.api.recipe.RecipeCategory;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import patternrouter.PatternRouter;
import patternrouter.common.Items;

/** GT recipe maps: by NEI category, and what their recipes make and keep. */
public final class RecipeMaps {

    /** Per request: does this recipe map have any recipe with the pattern's output (one scan per map). */
    private static final Map<RecipeMap<?>, Boolean> CAN_MAKE = new HashMap<>();
    /** Per recipe map: items that some recipe uses without consuming them (stack size 0). */
    private static final Map<RecipeMap<?>, Set<String>> NON_CONSUMABLES = new HashMap<>();

    private RecipeMaps() {}

    /** GregTech recipe map for an NEI category id, or null. */
    public static RecipeMap<?> forCategory(String category) {
        if (category == null || category.isEmpty()) return null;
        for (RecipeCategory c : RecipeCategory.ALL_RECIPE_CATEGORIES.values()) {
            if (category.equals(c.unlocalizedName)) return c.recipeMap;
        }
        return null;
    }

    /**
     * The client sends the category of the last NEI transfer, which may belong to another pattern (encoded by hand,
     * or in an earlier session). Keep it only if that recipe map has a recipe from this pattern's inputs (plus the NEI
     * non-consumables) with the same output; otherwise drop it and let the pattern contents decide.
     */
    public static String checkedCategory(String category, List<ItemStack> nonConsumables, ItemStack pattern,
        World world) {
        RecipeMap<?> wanted = forCategory(category);
        if (wanted == null) return category;
        PatternContents contents = PatternContents.of(pattern, world);
        if (contents.isEmpty() || contents.recipeIn(wanted, nonConsumables) != null) return category;
        // Exact lookup can miss recipes whose inputs have alternatives (assembly line: any circuit of a tier).
        // A recipe with the same output in that map is enough to trust the category.
        try {
            for (GTRecipe r : wanted.getAllRecipes()) if (contents.producedBy(r)) return category;
        } catch (Throwable ignored) {}
        PatternRouter.LOG.debug("Ignoring NEI category '{}': the pattern is not a recipe of that machine", category);
        return "";
    }

    public static boolean canMake(RecipeMap<?> map, PatternContents contents) {
        return CAN_MAKE.computeIfAbsent(map, m -> {
            try {
                Collection<GTRecipe> all = m.getAllRecipes();
                // Furnace, microwave and other non-GT maps keep no recipe list: they build a recipe on request
                // (vanilla smelting), so only the lookup itself can tell. Without this the Multi Smelter never fits.
                if (all.isEmpty()) return true;
                for (GTRecipe r : all) if (contents.producedBy(r)) return true;
            } catch (Throwable ignored) {}
            return false;
        });
    }

    /** Some recipe of these maps uses the item without consuming it (circuit, mold, lens). */
    public static boolean isNonConsumable(ItemStack stack, Collection<RecipeMap<?>> maps) {
        String exact = Items.idAndDamage(stack);
        String anyDamage = Items.idAnyDamage(stack);
        for (RecipeMap<?> map : maps) {
            Set<String> kinds = NON_CONSUMABLES.computeIfAbsent(map, RecipeMaps::nonConsumedInputs);
            if (kinds.contains(exact) || kinds.contains(anyDamage)) return true;
        }
        return false;
    }

    private static Set<String> nonConsumedInputs(RecipeMap<?> map) {
        Set<String> kinds = new HashSet<>();
        for (GTRecipe r : map.getAllRecipes()) {
            if (r.mInputs == null) continue;
            for (ItemStack in : r.mInputs) {
                if (in != null && in.getItem() != null && in.stackSize == 0) kinds.add(Items.idAndDamage(in));
            }
        }
        return kinds;
    }

    /** End of a request: the output checks belong to its pattern. */
    public static void endLookup() {
        CAN_MAKE.clear();
    }
}
