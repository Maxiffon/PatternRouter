package patternrouter.mixins.late.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.client.gui.implementations.GuiMEMonitorable;
import patternrouter.client.ClientPanel;

/**
 * Keys go to the side panel's search field while it has focus; otherwise AE2's search, NEI or the inventory key
 * ("E" closes the terminal) would take them.
 */
@Mixin(value = GuiMEMonitorable.class, remap = false)
public abstract class GuiMEMonitorableKeyMixin {

    @Inject(method = { "keyTyped", "func_73869_a" }, at = @At("HEAD"), cancellable = true, require = 0)
    private void patternrouter$key(char typed, int key, CallbackInfo ci) {
        if (ClientPanel.INSTANCE != null && ClientPanel.INSTANCE.onKey(this, typed, key)) ci.cancel();
    }
}
