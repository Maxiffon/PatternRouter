package patternrouter.server.machine;

import java.util.Collection;
import java.util.List;

import net.minecraft.item.ItemStack;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDataAccess;
import gregtech.api.util.AssemblyLineUtils;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/**
 * An assembly line runs only what its data sticks hold: in data access hatches, or coming over an optical (or
 * wireless) data input from a data bank. GT's recipe lookup can't tell, the hatches can.
 */
public final class AssemblyLineData {

    private AssemblyLineData() {}

    /**
     * TRUE if every assembly line among the machines has research for the output, FALSE if one of them lacks it, null
     * when there is no assembly line or its hatches can't be read. Every one must know it: a P2P interface input hands
     * the ingredients to whichever of its outputs is free, and a line without the research leaves them in its buses.
     */
    public static Boolean researched(List<IMetaTileEntity> machines, ItemStack output) {
        if (output == null) return null;
        Boolean result = null;
        for (IMetaTileEntity mte : machines) {
            if (!(mte instanceof MTEAssemblyLine line)) continue;
            try {
                if (!knows(line, output)) return Boolean.FALSE;
                result = Boolean.TRUE;
            } catch (Throwable ignored) {}
        }
        return result;
    }

    private static boolean knows(MTEAssemblyLine line, ItemStack output) {
        return recipeOf(line, output) != null;
    }

    /** The researched recipe for this output on the first assembly line among the machines that has it, or null. */
    public static RecipeAssemblyLine recipeFor(List<IMetaTileEntity> machines, ItemStack output) {
        if (output == null) return null;
        for (IMetaTileEntity mte : machines) {
            if (!(mte instanceof MTEAssemblyLine line)) continue;
            try {
                RecipeAssemblyLine r = recipeOf(line, output);
                if (r != null) return r;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** The same places GT's checkProcessing reads: the stick in the controller slot, then the data hatches. */
    private static RecipeAssemblyLine recipeOf(MTEAssemblyLine line, ItemStack output) {
        ItemStack controllerStick = line.getStackInSlot(1);
        if (AssemblyLineUtils.isItemDataStick(controllerStick)) {
            Collection<RecipeAssemblyLine> fromStick = AssemblyLineUtils.findALRecipeFromDataStick(controllerStick);
            if (fromStick != null) {
                for (RecipeAssemblyLine r : fromStick) {
                    if (r != null && r.mOutput != null && r.mOutput.isItemEqual(output)) return r;
                }
            }
        }
        for (MTEHatchDataAccess hatch : line.mDataAccessHatches) {
            if (hatch == null || !hatch.isValid()) continue;
            for (RecipeAssemblyLine r : hatch.getAssemblyLineRecipes()) {
                if (r != null && r.mOutput != null && r.mOutput.isItemEqual(output)) return r;
            }
        }
        return null;
    }
}
