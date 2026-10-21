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
import adris.altoclef.ui.gui.mc.UpdateCardWidget;
import baritone.utils.update.Updater;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the update card in the corner of the title screen. two hooks, both at the tail of a plain vanilla method that every
// loader has, after vanilla's own buttons are in. mod menu and friends can rearrange those all they like, the card is
// its own widget in its own corner
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    // init() runs on every open and every resize, so the widget is rebuilt each time; the check itself only runs once a
    // session (checkAsync is idempotent). nothing of ours may throw out of here, a title screen that cannot open is
    // the whole game not opening
    @Inject(method = "init()V", at = @At("TAIL"))
    private void soprano$addUpdateCard(CallbackInfo ci) {
        try {
            Updater.get().checkAsync();
            addRenderableWidget(new UpdateCardWidget(this));
        } catch (Throwable t) {
            Safe.report("title update card", t);
        }
    }

    @Inject(method = "removed()V", at = @At("TAIL"))
    private void soprano$releaseUpdateCard(CallbackInfo ci) {
        try {
            UpdateCardWidget.released();
        } catch (Throwable t) {
            Safe.report("title update card release", t);
        }
    }
}
