package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import baritone.utils.FreeCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the spectator camera (FreeCam): the mouse turns it instead of the player, and the scroll wheel sets how fast it flies
// instead of changing the hotbar slot
@Mixin(MouseHandler.class)
public class MixinFreeCamMouse {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Redirect(
            method = "turnPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"
            )
    )
    private void soprano$turnFreeCam(LocalPlayer player, double dx, double dy) {
        if (FreeCam.on()) {
            FreeCam.turn(dx, dy);
            return;
        }
        player.turn(dx, dy);
    }

    @Inject(
            method = "onScroll",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$scrollFreeCam(long window, double xOffset, double yOffset, CallbackInfo ci) {
        try {
            if (FreeCam.on() && minecraft.gui.screen() == null && window == minecraft.getWindow().handle()) {
                FreeCam.scroll(yOffset);
                ci.cancel();
            }
        } catch (Throwable t) {
            Safe.report("free cam scroll", t);
        }
    }
}
