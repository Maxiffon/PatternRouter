package patternrouter.server.recipe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import gregtech.api.recipe.RecipeMap;
import patternrouter.common.Items;
import patternrouter.server.machine.MachineInventory;

/** How a pattern fits a machine, by the same GT recipe lookup the machine runs. */
public enum RecipeFit {

    /** GT finds no recipe with this output. */
    NONE,
    /** Recipe found, everything the machine is set up with is needed. */
    EXACT,
    /** Recipe found, but the machine's contents also start another recipe. */
    CONFLICTING,
    /** Recipe found, spare circuits/molds in the machine change nothing. */
    WITH_SPARE_SETUP;

    public boolean fits() {
        return this == EXACT || this == WITH_SPARE_SETUP;
    }

    /**
     * Runs the same GT recipe lookup the machine would: pattern inputs plus everything the machine holds.
     * <ul>
     * <li>no recipe with the pattern's output: doesn't fit;</li>
     * <li>these contents also start a recipe with another output (a spare circuit or mold selects it): doesn't fit,
     * GT may run that one instead;</li>
     * <li>otherwise it fits; if some circuit/mold/lens can be removed and the recipe is still found, the machine is
     * set up for more recipes than this one (several circuits in one bus) - still fine, listed after exact ones.</li>
     * </ul>
     */
    public static RecipeFit of(RecipeMap<?> map, PatternContents contents, MachineInventory machine) {
        if (contents.recipeIn(map, machine.items) == null) return NONE;
        if (contents.startsOtherRecipe(map, machine.items)) return CONFLICTING;
        for (ItemStack setupItem : machine.setupItems) {
            List<ItemStack> without = new ArrayList<>();
            for (ItemStack h : machine.items) if (!Items.sameItem(h, setupItem)) without.add(h);
            if (contents.recipeIn(map, without) != null) return WITH_SPARE_SETUP;
        }
        return EXACT;
    }

    /** {@link #of} for the machine; with one possibility per input bus the best one counts. */
    public static RecipeFit best(RecipeMap<?> map, PatternContents contents, MachineInventory machine) {
        if (machine.perBus == null) return of(map, contents, machine);
        RecipeFit best = NONE;
        for (MachineInventory bus : machine.perBus) {
            RecipeFit f = of(map, contents, bus);
            if (f == EXACT) return EXACT;
            if (f == WITH_SPARE_SETUP) best = WITH_SPARE_SETUP;
            else if (f == CONFLICTING && best == NONE) best = CONFLICTING;
        }
        return best;
    }
}
