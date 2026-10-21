package baritone.launch.mixins;

import adris.altoclef.ui.gui.mc.move.MoveSession;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the hand doesn't look at detached, so a free camera fifty blocks up would still be holding a pickaxe
@Mixin(GameRenderer.class)
public class MixinMoveHand {

    @Inject(
            method = "renderItemInHand",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$hideHand(final Camera camera, final float partialTick, final Matrix4f projection, final CallbackInfo ci) {
        if (MoveSession.cameraOn()) {
            ci.cancel();
        }
    }
}
