package patternrouter.mixins.late.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import patternrouter.client.RecipeContext;

/**
 * Через RecipeHandlerRef проходит любой перенос рецепта из NEI в открытый интерфейс (в том числе через
 * NotEnoughEnergistics). Здесь видно, из какой категории рецепт и какие у него несъедаемые входы.
 */
@Mixin(value = RecipeHandlerRef.class, remap = false)
public abstract class RecipeHandlerRefMixin {

    @Shadow
    @Final
    public IRecipeHandler handler;

    @Shadow
    @Final
    public int recipeIndex;

    @Inject(method = "fillCraftingGrid", at = @At("HEAD"), remap = false)
    private void patternrouter$onFill(GuiContainer gui, int multiplier, CallbackInfo ci) {
        RecipeContext.capture(this.handler, this.recipeIndex);
    }

    @Inject(method = "craft", at = @At("HEAD"), remap = false)
    private void patternrouter$onCraft(GuiContainer gui, int multiplier, CallbackInfoReturnable<Boolean> cir) {
        RecipeContext.capture(this.handler, this.recipeIndex);
    }
}
