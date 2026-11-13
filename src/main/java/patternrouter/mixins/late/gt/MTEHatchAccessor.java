package patternrouter.mixins.late.gt;

import net.minecraft.util.ChunkCoordinates;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import gregtech.api.metatileentity.implementations.MTEHatch;

/** The multiblock controller writes its coordinates into the hatch during the structure check. */
@Mixin(value = MTEHatch.class, remap = false)
public interface MTEHatchAccessor {

    @Accessor(value = "ae2CraftingIconOwner", remap = false)
    ChunkCoordinates patternrouter$getOwner();
}
