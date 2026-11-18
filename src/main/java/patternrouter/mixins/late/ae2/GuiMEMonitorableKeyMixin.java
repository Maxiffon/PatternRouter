package patternrouter.mixins.late.ae2;

import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.client.gui.implementations.GuiMEMonitorable;
import patternrouter.client.ClientPanel;

/**
 * Keys go to the side panel (its search field, the duplicates question) while it wants them; otherwise AE2's search,
 * NEI keybinds (R, U, O over an item) or the inventory key ("E" closes the terminal) would take them. Taken at the
 * start of the terminal's keyboard handling, before anything below it sees the key; keyTyped is a fallback.
 */
@Mixin(value = GuiMEMonitorable.class, remap = false)
public abstract class GuiMEMonitorableKeyMixin {

    @Inject(method = { "handleKeyboardInput", "func_146282_l" }, at = @At("HEAD"), cancellable = true, require = 0)
    private void patternrouter$keyboard(CallbackInfo ci) {
        ClientPanel panel = ClientPanel.INSTANCE;
        if (panel == null || !panel.wantsKeys(this)) return;
        // Presses go to the panel; releases are eaten too, so nothing below acts on half a key stroke.
        if (Keyboard.getEventKeyState()) panel.onKey(this, Keyboard.getEventCharacter(), Keyboard.getEventKey());
        ci.cancel();
    }

    @Inject(method = { "keyTyped", "func_73869_a" }, at = @At("HEAD"), cancellable = true, require = 0)
    private void patternrouter$key(char typed, int key, CallbackInfo ci) {
        if (ClientPanel.INSTANCE != null && ClientPanel.INSTANCE.onKey(this, typed, key)) ci.cancel();
    }
}
