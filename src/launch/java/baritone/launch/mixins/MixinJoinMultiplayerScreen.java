/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.utils.portal.Portal;
import baritone.utils.portal.PortalSlider;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the viafabricplus version slider on the server list. applies on every loader, but without vfp it's one
// early return, a null check and no widget
@Mixin(JoinMultiplayerScreen.class)
public abstract class MixinJoinMultiplayerScreen extends Screen {

    @Unique
    private PortalSlider portalSlider;

    protected MixinJoinMultiplayerScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addPortalSlider(CallbackInfo ci) {
        portalSlider = null;
        if (!Portal.available()) {
            return;
        }
        // a vfp that's there but broken shouldn't take the server list down with it, it just gets no slider
        try {
            portalSlider = addRenderableWidget(new PortalSlider(5, 6, portalSliderWidth(), 20));
        } catch (LinkageError | RuntimeException e) {
            portalSlider = null;
        }
    }

    @Inject(method = "repositionElements", at = @At("RETURN"))
    private void movePortalSlider(CallbackInfo ci) {
        // still null on the first call, init runs repositionElements before our TAIL gets to make the slider
        if (portalSlider == null) {
            return;
        }
        try {
            portalSlider.setRectangle(portalSliderWidth(), 20, 5, 6);
            // also the hook for coming back from vfp's screen or edit server, widgets survive those
            portalSlider.refresh();
        } catch (LinkageError | RuntimeException e) {
            portalSlider.visible = false;
        }
    }

    // top left, wide enough to be usable but never into the centered title. vfp's own button has the top right
    @Unique
    private int portalSliderWidth() {
        int beside = (width - font.width(title)) / 2 - 10;
        return Math.max(80, Math.min(beside, 150));
    }
}
