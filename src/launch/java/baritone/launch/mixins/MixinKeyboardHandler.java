package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.GuiHotkey;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
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
    private void soprano$openGui(final long window, final int key, final int scancode, final int action, final int mods, final CallbackInfo ci) {
        try {
            if (window == Minecraft.getInstance().getWindow().getWindow() && GuiHotkey.onKey(key, action)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // a key press is the middle of everything, the gui does not get to throw out of it
            Safe.report("gui key mixin", t);
        }
    }
}
