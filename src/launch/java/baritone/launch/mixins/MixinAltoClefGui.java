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

import adris.altoclef.eventbus.events.GameOverlayEvent;
import adris.altoclef.ui.gui.mc.TripToastHud;
import baritone.altoclef.AltoClefBridge;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the hud half of Gui.render became its own class (Hud) in 26.3, and rendering is extract-then-draw now
@Mixin(Hud.class)
public class MixinAltoClefGui {

    @Shadow
    @Final
    private SubtitleOverlay subtitleOverlay;

    // RETURN so the hud lands on top of everything vanilla drew
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN")
    )
    private void altoclef$onRenderHud(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker, final CallbackInfo ci) {
        TripToastHud.beginFrame();
        AltoClefBridge.renderHud(graphics);
        // its own call and its own guard, after altoclef's so it lands on top: a trip question comes up when altoclef's
        // hud isn't drawing at all, and altoclef's hud giving up must not take the question with it
        TripToastHud.render(graphics, TripToastHud.subtitleRows(subtitleOverlay));
    }

    @Inject(
            method = "setOverlayMessage",
            at = @At("HEAD")
    )
    private void altoclef$onSetOverlayMessage(final Component message, final boolean animate, final CallbackInfo ci) {
        if (!AltoClefBridge.isRunning()) {
            return;
        }
        try {
            AltoClefBridge.publish(new GameOverlayEvent(message.getString()));
        } catch (Throwable t) {
            AltoClefBridge.onHookError(t);
        }
    }
}
