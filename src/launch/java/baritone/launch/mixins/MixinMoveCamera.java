package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.move.MoveSession;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the free camera of the schematic move mode. the tail of setup is fine on this version: it only places and turns the
// camera, the cull frustum gets built from it afterwards, so chunks get culled for the view we're actually showing
@Mixin(Camera.class)
public abstract class MixinMoveCamera {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    // yaw first, the way Camera has it
    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(
            method = "setup",
            at = @At("TAIL")
    )
    private void soprano$moveCamera(final BlockGetter level, final Entity entity, final boolean thirdPerson,
                                    final boolean thirdPersonReverse, final float partialTick, final CallbackInfo ci) {
        try {
            double[] pose = MoveSession.pose();
            if (pose == null) {
                return;
            }
            setPosition(pose[0], pose[1], pose[2]);
            setRotation((float) pose[3], (float) pose[4]);
            // detached is what draws the player's own body, and they're standing right there in the shot
            detached = true;
        } catch (Throwable t) {
            // fail open: a camera that throws once is the player's camera from then on
            Safe.report("move camera", t);
            MoveSession.end();
        }
    }
}
