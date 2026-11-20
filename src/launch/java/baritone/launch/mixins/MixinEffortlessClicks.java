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

import adris.altoclef.ui.gui.kit.Safe;
import baritone.effortless.BuildMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Effortless Soprano's build mode takes the player's own left and right clicks while it's on. only these three: the
// builder clicks through the game mode directly (BlockPlaceHelper, BlockBreakHelper), never through here, so a job
// running while build mode is on keeps all of its clicks. off, nothing here does anything
@Mixin(Minecraft.class)
public class MixinEffortlessClicks {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void soprano$effortlessAttack(final CallbackInfoReturnable<Boolean> cir) {
        try {
            if (BuildMode.onAttack()) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            Safe.report("effortless left click", t);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void soprano$effortlessUse(final CallbackInfo ci) {
        try {
            if (BuildMode.onUse()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Safe.report("effortless right click", t);
        }
    }

    // a held left button mines through continueAttack every tick, build mode doesn't
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void soprano$effortlessHold(final boolean leftClick, final CallbackInfo ci) {
        try {
            if (BuildMode.swallowHeldAttack()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Safe.report("effortless held click", t);
        }
    }
}
