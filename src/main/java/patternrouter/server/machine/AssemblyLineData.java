package patternrouter.server.machine;

import java.util.List;

import net.minecraft.item.ItemStack;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDataAccess;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/**
 * An assembly line runs only what its data sticks hold: in data access hatches, or coming over an optical (or
 * wireless) data input from a data bank. GT's recipe lookup can't tell, the hatches can.
 */
public final class AssemblyLineData {

    private AssemblyLineData() {}

    /**
     * TRUE if one of the assembly lines among the machines has research for the output, FALSE if they have none,
     * null when there is no assembly line or its hatches can't be read.
     */
    public static Boolean researched(List<IMetaTileEntity> machines, ItemStack output) {
        if (output == null) return null;
        Boolean result = null;
        for (IMetaTileEntity mte : machines) {
            if (!(mte instanceof MTEAssemblyLine line)) continue;
            try {
                for (MTEHatchDataAccess hatch : line.mDataAccessHatches) {
                    if (hatch == null || !hatch.isValid()) continue;
                    for (RecipeAssemblyLine r : hatch.getAssemblyLineRecipes()) {
                        if (r != null && r.mOutput != null && r.mOutput.isItemEqual(output)) return Boolean.TRUE;
                    }
                }
                result = Boolean.FALSE;
            } catch (Throwable ignored) {}
        }
        return result;
    }
}
