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

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ScreenOpenEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinAltoClefScreen {

    @Inject(
            method = "setScreen",
            at = @At("HEAD")
    )
    private void altoclef$onScreenOpenBegin(final Screen screen, final CallbackInfo ci) {
        EventBus.publish(new ScreenOpenEvent(screen, true));
    }

    // setScreen only has the one return, so TAIL really is the end
    @Inject(
            method = "setScreen",
            at = @At("TAIL")
    )
    private void altoclef$onScreenOpenEnd(final Screen screen, final CallbackInfo ci) {
        EventBus.publish(new ScreenOpenEvent(screen, false));
    }
}
