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
import adris.altoclef.ui.gui.mc.TripToastHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Y / N for the corner toast under the inventory and the pause menu, and the toast on top of the pause menu's blur.
// head of Screen.keyPressed and not of KeyboardHandler.keyPress: AbstractContainerScreen.keyPressed calls this first
// thing, and the anvil, creative and recipe book screens only call super once their text box passed on the key. so a y
// meant for a search box never gets here. which screens actually answer is TripToastHud's call (keysAnswer)
@Mixin(Screen.class)
public class MixinScreenTripKeys {

    @Inject(
            method = "keyPressed",
            at = @At("HEAD"),
            cancellable = true
    )
    private void soprano$tripKeys(final KeyEvent event, final CallbackInfoReturnable<Boolean> cir) {
        try {
            if (TripToastHud.onScreenKey((Screen) (Object) this, event)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable t) {
            // every key in every screen comes through here, it does not get to throw
            Safe.report("trip toast screen key mixin", t);
        }
    }

    // after the screen and its tooltips, where vanilla's toasts go too. the final wrapper, so a screen that overrides
    // extractRenderState without calling super still gets it
    @Inject(
            method = "extractRenderStateWithTooltipAndSubtitles",
            at = @At("TAIL")
    )
    private void soprano$tripToastOver(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float partialTick, final CallbackInfo ci) {
        try {
            TripToastHud.overScreen((Screen) (Object) this, g);
        } catch (Throwable t) {
            Safe.report("trip toast over screen mixin", t);
        }
    }
}
