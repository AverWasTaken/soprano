package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.GuiHotkey;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler {

    // head and cancelled: the press never reaches KeyMapping, and never reaches the screen it just opened either
    @Inject(
            method = "keyPress",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$openGui(final long window, final int action, final KeyEvent event, final CallbackInfo ci) {
        try {
            // todo 26.3: keys come off SDL now. event.key() is an SDL scancode (InputConstants.KEY_B == 5, not glfw's 66)
            // and keycode() is the SDL keycode ('b' == 98), action is PRESS 1 / RELEASE 0 / REPEAT -1 (glfw repeat was 2).
            // GuiHotkey and KeyBind still compare glfw numbers, so they need remapping before the hotkey works again.
            // until then the default (344) never matches and a custom glfw bind fires on the wrong key
            if (window == Minecraft.getInstance().getWindow().handle() && GuiHotkey.onKey(event.key(), action)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // a key press is the middle of everything, the gui does not get to throw out of it
            Safe.report("gui key mixin", t);
        }
    }
}
