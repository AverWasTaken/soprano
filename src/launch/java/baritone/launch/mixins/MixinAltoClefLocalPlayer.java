/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.altoclef.AltoClefBridge;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class MixinAltoClefLocalPlayer {

    // vanilla hands the camera the raw rotation so mouse look never lags. altoclef rotates once a tick, so while it runs we lerp
    // between ticks instead. only while it runs, otherwise normal mouse look would trail by up to a tick
    @Inject(
            method = "getViewXRot",
            at = @At("HEAD"),
            cancellable = true
    )
    private void altoclef$smoothPitch(final float partialTick, final CallbackInfoReturnable<Float> cir) {
        if (!AltoClefBridge.isRunning()) {
            return;
        }
        cir.setReturnValue(((Entity) (Object) this).getXRot(partialTick));
    }

    @Inject(
            method = "getViewYRot",
            at = @At("HEAD"),
            cancellable = true
    )
    private void altoclef$smoothYaw(final float partialTick, final CallbackInfoReturnable<Float> cir) {
        Entity self = (Entity) (Object) this;
        // riding something keeps vanilla's head rotation path, nothing for us to smooth in there
        if (!AltoClefBridge.isRunning() || self.isPassenger()) {
            return;
        }
        cir.setReturnValue(self.getYRot(partialTick));
    }
}
