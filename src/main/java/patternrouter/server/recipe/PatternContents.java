package patternrouter.server.recipe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import patternrouter.PatternRouter;
import patternrouter.common.Items;

/** Pattern inputs and main output in a form GT recipe lookup understands. */
public final class PatternContents {

    /** The terminal's pattern is decoded several times per request; decoding a crafting pattern is slow. */
    private static ItemStack lastPattern;
    private static PatternContents lastContents;

    private final List<ItemStack> items = new ArrayList<>();
    private final List<FluidStack> fluids = new ArrayList<>();
    private ItemStack outItem;
    private FluidStack outFluid;
    private boolean craftingPattern;

    private PatternContents() {}

    /** Ends the reuse of the last decoded pattern (once per request). */
    public static void forget() {
        lastPattern = null;
        lastContents = null;
    }

    public static PatternContents of(ItemStack pattern, World world) {
        if (pattern != null && pattern == lastPattern && lastContents != null) return lastContents;
        PatternContents pc = new PatternContents();
        lastPattern = pattern;
        lastContents = pc;
        if (pattern == null || !(pattern.getItem() instanceof ICraftingPatternItem patternItem) || world == null)
            return pc;
        try {
            ICraftingPatternDetails d = patternItem.getPatternForItem(pattern, world);
            if (d == null) return pc;
            pc.craftingPattern = d.isCraftable();
            for (IAEStack<?> st : d.getCondensedAEInputs()) {
                if (st instanceof IAEItemStack item) pc.items.add(item.getItemStack());
                else if (st instanceof IAEFluidStack fluid) pc.fluids.add(fluid.getFluidStack());
            }
            IAEStack<?>[] outs = d.getCondensedAEOutputs();
            if (outs != null && outs.length > 0) {
                if (outs[0] instanceof IAEItemStack item) pc.outItem = item.getItemStack();
                else if (outs[0] instanceof IAEFluidStack fluid) pc.outFluid = fluid.getFluidStack();
            }
        } catch (Throwable t) {
            PatternRouter.LOG.debug("Cannot decode pattern: {}", t.toString());
        }
        return pc;
    }

    /** Crafting (workbench) pattern, run by molecular assemblers. */
    public boolean isCraftingPattern() {
        return craftingPattern;
    }

    public boolean hasFluidInputs() {
        return !fluids.isEmpty();
    }

    /** Nothing to look a recipe up with: no inputs or no output. */
    public boolean isEmpty() {
        return (items.isEmpty() && fluids.isEmpty()) || (outItem == null && outFluid == null);
    }

    /** A recipe in the map made from these inputs (plus machine contents) with the same output, or null. */
    public GTRecipe recipeIn(RecipeMap<?> map, List<ItemStack> held) {
        List<ItemStack> in = new ArrayList<>(items);
        in.addAll(held);
        try {
            return map.findRecipeQuery()
                .items(in.toArray(new ItemStack[0]))
                .fluids(fluids.toArray(new FluidStack[0]))
                .dontCheckStackSizes(true)
                .findAll()
                .filter(this::producedInProportion)
                .findFirst()
                .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Some recipe with another output also matches these inputs plus machine contents. */
    public boolean startsOtherRecipe(RecipeMap<?> map, List<ItemStack> held) {
        List<ItemStack> in = new ArrayList<>(items);
        in.addAll(held);
        try {
            return map.findRecipeQuery()
                .items(in.toArray(new ItemStack[0]))
                .fluids(fluids.toArray(new FluidStack[0]))
                .dontCheckStackSizes(true)
                .findAll()
                .anyMatch(r -> !producedBy(r));
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean producedBy(GTRecipe r) {
        if (outItem != null && r.mOutputs != null) {
            for (ItemStack o : r.mOutputs) if (o != null && o.isItemEqual(outItem)) return true;
        }
        if (outFluid != null && r.mFluidOutputs != null) {
            for (FluidStack o : r.mFluidOutputs) if (o != null && o.isFluidEqual(outFluid)) return true;
        }
        return false;
    }

    /**
     * Same output and the same proportions: the pattern must be k times the recipe (k = pattern output / recipe
     * output), so "1 ingot -> 2 rods" fits the extruder (1 -> 2) but not the lathe (1 -> 1 rod + dust). Inputs
     * the recipe lists differently (ore dictionary variants) are not compared.
     */
    private boolean producedInProportion(GTRecipe r) {
        if (!producedBy(r)) return false;
        if (!usesAllInputs(r)) return false;
        long k = multiplier(r);
        if (k <= 0) return k == 0; // 0: nothing to compare against, accept
        if (r.mInputs != null) {
            for (ItemStack need : r.mInputs) {
                if (need == null || need.stackSize <= 0) continue; // non-consumed (circuit, mold, lens)
                for (ItemStack have : items) {
                    if (have != null && have.isItemEqual(need)) {
                        if (have.stackSize != k * need.stackSize) return false;
                        break;
                    }
                }
            }
        }
        if (r.mFluidInputs != null) {
            for (FluidStack need : r.mFluidInputs) {
                if (need == null || need.amount <= 0) continue;
                for (FluidStack have : fluids) {
                    if (have != null && have.isFluidEqual(need)) {
                        if (have.amount != k * need.amount) return false;
                        break;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Every pattern input is something the recipe takes. EBF "dust + circuit 1" and "dust + circuit 11 + 100 L
     * oganesson" make the same ingot: a machine set to [1] would run the first one and the oganesson from the
     * pattern would pile up in its hatch.
     */
    private boolean usesAllInputs(GTRecipe r) {
        for (FluidStack have : fluids) {
            if (have == null) continue;
            boolean used = false;
            if (r.mFluidInputs != null) {
                for (FluidStack need : r.mFluidInputs) used |= need != null && need.isFluidEqual(have);
            }
            if (!used) return false;
        }
        for (ItemStack have : items) {
            if (have == null) continue;
            boolean used = false;
            if (r.mInputs != null) {
                for (ItemStack need : r.mInputs) {
                    if (need != null && Items.matches(need, have)) {
                        used = true;
                        break;
                    }
                }
            }
            if (!used) return false;
        }
        return true;
    }

    /** Pattern output / recipe output for the main output; 0 if unknown, -1 if not a whole multiple. */
    private long multiplier(GTRecipe r) {
        long pattern = 0, recipe = 0;
        if (outItem != null && r.mOutputs != null) {
            pattern = outItem.stackSize;
            for (ItemStack o : r.mOutputs) if (o != null && o.isItemEqual(outItem)) {
                recipe = o.stackSize;
                break;
            }
        } else if (outFluid != null && r.mFluidOutputs != null) {
            pattern = outFluid.amount;
            for (FluidStack o : r.mFluidOutputs) if (o != null && o.isFluidEqual(outFluid)) {
                recipe = o.amount;
                break;
            }
        }
        if (pattern <= 0 || recipe <= 0) return 0;
        return pattern % recipe == 0 ? pattern / recipe : -1;
    }
}
