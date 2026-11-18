package patternrouter.mixins.late.ae2;

import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.client.gui.AEBaseGui;
import patternrouter.client.ClientPanel;

/**
 * The mouse wheel over the side panel scrolls the panel's list. Without this, AE2 would scroll the terminal's item grid
 * and NEI its item list, both under the panel. Only wheel events over the panel are taken; clicks pass untouched.
 */
@Mixin(value = AEBaseGui.class, remap = false)
public abstract class AEBaseGuiWheelMixin {

    @Inject(method = { "handleMouseInput", "func_146274_d" }, at = @At("HEAD"), cancellable = true, require = 0)
    private void patternrouter$wheel(CallbackInfo ci) {
        int dwheel = Mouse.getEventDWheel();
        if (dwheel == 0 || ClientPanel.INSTANCE == null) return;
        if (ClientPanel.INSTANCE.onWheel(this, dwheel)) ci.cancel();
    }
}
