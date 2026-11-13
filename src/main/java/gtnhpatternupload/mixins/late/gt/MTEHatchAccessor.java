package gtnhpatternupload.mixins.late.gt;

import net.minecraft.util.ChunkCoordinates;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import gregtech.api.metatileentity.implementations.MTEHatch;

/** Контроллер многоблока сам записывает свои координаты в люк при проверке структуры. */
@Mixin(value = MTEHatch.class, remap = false)
public interface MTEHatchAccessor {

    @Accessor(value = "ae2CraftingIconOwner", remap = false)
    ChunkCoordinates gtnhpatternupload$getOwner();
}
