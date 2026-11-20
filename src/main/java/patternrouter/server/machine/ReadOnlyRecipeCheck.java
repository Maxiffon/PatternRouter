package patternrouter.server.machine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import gregtech.api.logic.ProcessingLogic;
import gregtech.api.util.GTRecipe;

/**
 * Which machines may be asked "could you run this recipe" through their own {@code validateRecipe}. In GT that method
 * is the first step of starting a recipe, not a pure check: some machines drain their inputs there (the Nano Forge
 * takes all its MagMatter and a nanite), place water blocks (the old GT++ algae pond, wash plant and fishing pond),
 * lock a material into their saved state (froth flotation) or set what the running recipe will consume (plasma mixer,
 * Godforge exotic module). Only the overrides listed here were read in the bytecode (GT 5.09.54.205) and found to
 * only read: they compare the recipe with coils, tiers, stored EU, simulated fluid draws or the void protection
 * forecast, and at most reset numbers of their own logic that the machine sets the same way before every recipe.
 * Every other override, including ones added by a GT update or another mod, is never called: the panel then shows
 * the power check only, and the machine reports its own reason when it tries the recipe.
 */
final class ReadOnlyRecipeCheck {

    private static final Set<String> READ_ONLY = new HashSet<>(
        Arrays.asList(
            "bartworks.common.tileentities.multis.MTECircuitAssemblyLine$1",
            "bartworks.common.tileentities.multis.mega.MTEMegaBlastFurnaceLegacy$1",
            "goodgenerator.blocks.tileEntity.MTEComponentAssemblyLine$1",
            "goodgenerator.blocks.tileEntity.MTEFuelRefineFactoryLegacy$1",
            "goodgenerator.blocks.tileEntity.MTEPreciseAssembler$1",
            "goodgenerator.blocks.tileEntity.base.MTELargeFusionComputer$2",
            "gregtech.common.tileentities.machines.multi.MTEAlgaePond$1",
            "gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace$1",
            "gregtech.common.tileentities.machines.multi.MTEEndothermicFridge$1",
            "gregtech.common.tileentities.machines.multi.MTEExothermicHearth$1",
            "gregtech.common.tileentities.machines.multi.MTEFusionComputer$2",
            "gregtech.common.tileentities.machines.multi.MTEIndustrialCentrifuge$1",
            "gregtech.common.tileentities.machines.multi.MTEIndustrialChisel$1",
            "gregtech.common.tileentities.machines.multi.MTEIndustrialElectromagneticSeparator$1",
            "gregtech.common.tileentities.machines.multi.MTEIndustrialLaserEngraver$1",
            "gregtech.common.tileentities.machines.multi.MTENaquadahFuelRefinery$1",
            "gregtech.common.tileentities.machines.multi.MTEPlasmaForge$2",
            "gregtech.common.tileentities.machines.multi.beamcrafting.MTEBeamCrafter$1",
            "gregtech.common.tileentities.machines.multi.compressor.MTEHIPCompressor$1",
            "gregtech.common.tileentities.machines.multi.compressor.MTEIndustrialCompressor$1",
            "gregtech.common.tileentities.machines.multi.compressor.MTENeutroniumCompressor$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamAlloySmelter$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamCentrifuge$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamCompressor$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamForgeHammer$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamFurnaceMulti$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamMacerator$1",
            "gregtech.common.tileentities.machines.multi.steam.MTESteamMixer$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.processing.MTEIndustrialCentrifugeLegacy$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.processing.MTEIndustrialDehydrator$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.processing.advanced.MTEAdvEBF$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.MTEAlloyBlastSmelter$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.MTEIndustrialRockBreakerLegacy$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.MTEMassFabricator$1",
            "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.mega.MTEMegaAlloyBlastSmelter$1",
            "gtnhintergalactic.tile.multi.elevatormodules.TileEntityModuleAssembler$1",
            "gtnhintergalactic.tile.multi.elevatormodules.TileEntityModuleResearch$1",
            "gtnhlanth.common.tileentity.MTEDigester$1",
            "kubatech.tileentity.gregtech.multiblock.MTEDEFusionCrafter$1",
            "kubatech.tileentity.gregtech.multiblock.MTEIndustrialArcFurnace$1",
            "tectech.thing.metaTileEntity.multi.bec.MTEBECIONode$1",
            "tectech.thing.metaTileEntity.multi.godforge.MTEMoltenModule$1",
            "tectech.thing.metaTileEntity.multi.godforge.MTEPlasmaModule$1",
            "tectech.thing.metaTileEntity.multi.godforge.MTESmeltingModule$1"));

    /** Per logic class: may its validateRecipe be called. */
    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private ReadOnlyRecipeCheck() {}

    /**
     * True if every validateRecipe the call can reach, from the logic's own class up to {@link ProcessingLogic}, is a
     * checked one. False also when nothing overrides it: GT's own just says yes, so asking tells nothing.
     */
    static boolean mayAsk(ProcessingLogic logic) {
        return CACHE.computeIfAbsent(logic.getClass(), ReadOnlyRecipeCheck::decide);
    }

    private static boolean decide(Class<?> type) {
        boolean overridden = false;
        for (Class<?> c = type; c != null && c != ProcessingLogic.class; c = c.getSuperclass()) {
            if (!declaresValidate(c)) continue;
            if (!READ_ONLY.contains(c.getName())) return false;
            overridden = true;
        }
        return overridden;
    }

    private static boolean declaresValidate(Class<?> c) {
        try {
            c.getDeclaredMethod("validateRecipe", GTRecipe.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            // Can't tell what it declares: treat it as an unknown override.
            return true;
        }
    }
}
