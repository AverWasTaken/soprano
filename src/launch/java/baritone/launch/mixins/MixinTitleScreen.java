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
import adris.altoclef.ui.gui.mc.MenuMusic;
import adris.altoclef.ui.gui.mc.TitleMenu;
import adris.altoclef.ui.gui.mc.TitleTile;
import adris.altoclef.ui.gui.mc.UpdateCardWidget;
import baritone.utils.update.Updater;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// our title screen and the update card. vanilla's buttons stay in the screen (invisible, still pressed through the
// tiles), TitleMenu draws everything else. the render hooks: after the panorama we blur and draw the backdrop and brand,
// and right after Screen's own widget pass we return, which skips the logo, splash, version line and realms icons in
// one go without fighting the many mods that redirect those. the cost: other mods' tail hooks on this method don't run
// while ours is on. nothing of ours may throw out of here, a title screen that cannot open is the whole game not opening
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {

    @Shadow
    private boolean fading;

    // null = vanilla. decided on the first init only: init runs again on every resize and when the boot overlay
    // finishes, and flipping the setting mid screen should wait for the next open
    @Unique
    private TitleMenu soprano$menu;

    @Unique
    private boolean soprano$decided;

    protected MixinTitleScreen(Component title) {
        super(title);
    }

    @Shadow
    private Component getMultiplayerDisabledReason() {
        throw new AssertionError();
    }

    // init() runs on every open and every resize, so the tiles and the update card are rebuilt each time; the update
    // check itself only runs once a session (checkAsync is idempotent)
    @Inject(method = "init()V", at = @At("TAIL"))
    private void soprano$init(CallbackInfo ci) {
        if (!soprano$decided) {
            soprano$decided = true;
            try {
                soprano$menu = TitleMenu.create(fading);
            } catch (Throwable t) {
                Safe.report("title menu", t);
            }
            // a vanilla title has no speaker button, so it gets vanilla's music too
            if (soprano$menu == null) {
                MenuMusic.titleGone();
            }
        }
        TitleMenu menu = soprano$menu;
        boolean dressed = false;
        if (menu != null && !menu.broken()) {
            try {
                menu.rebuild(children(), getMultiplayerDisabledReason(), (int) minecraft.getWindow().getGuiScale());
                addRenderableOnly(menu.chrome());
                for (TitleTile tile : menu.tilesBeforeCard()) {
                    addRenderableWidget(tile);
                }
                dressed = true;
            } catch (Throwable t) {
                Safe.report("title menu init", t);
                soprano$undress(menu);
            }
        }
        try {
            Updater.get().checkAsync();
            addRenderableWidget(new UpdateCardWidget(this));
        } catch (Throwable t) {
            Safe.report("title update card", t);
        }
        if (dressed) {
            try {
                TitleTile copyright = menu.tileAfterCard();
                if (copyright != null) {
                    addRenderableWidget(copyright);
                }
            } catch (Throwable t) {
                Safe.report("title menu copyright", t);
                soprano$undress(menu);
            }
        }
    }

    // buttons added after our init tail (fabric's after init event, mod menu) get their tile here, before the widget
    // pass starts walking the list. a menu that broke last frame gets vanilla put back here for the same reason
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"))
    private void soprano$head(GuiGraphics g, int mouseX, int mouseY, float a, CallbackInfo ci) {
        TitleMenu menu = soprano$menu;
        if (menu == null) {
            return;
        }
        if (menu.broken()) {
            soprano$undress(menu);
            return;
        }
        try {
            menu.startEntryIfPending();
            for (TitleTile tile : menu.reclassify(children())) {
                addRenderableWidget(tile);
            }
        } catch (Throwable t) {
            menu.fail("title menu late buttons", t);
            soprano$undress(menu);
        }
    }

    // one full screen blur over the panorama (vanilla's menu blur option, nothing when it's 0), then our backdrop
    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/TitleScreen;renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void soprano$backdrop(GuiGraphics g, int mouseX, int mouseY, float a, CallbackInfo ci) {
        TitleMenu menu = soprano$menu;
        if (menu == null || menu.broken()) {
            return;
        }
        try {
            // the blur is real gl on whatever is already drawn, so the panorama's queued vignette goes down first
            g.flush();
            renderBlurredBackground();
        } catch (Throwable t) {
            // a sharp panorama is still a title screen
            Safe.report("title blur", t);
        }
        try {
            menu.drawBackdrop(g);
        } catch (Throwable t) {
            menu.fail("title backdrop", t);
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void soprano$skipVanillaChrome(GuiGraphics g, int mouseX, int mouseY, float a, CallbackInfo ci) {
        TitleMenu menu = soprano$menu;
        if (menu != null && !menu.broken()) {
            ci.cancel();
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

    // vanilla back: our hidden buttons visible again, our tiles out. the chrome renderable can't be taken out (Screen
    // only removes listeners), it draws nothing once the menu is broken
    @Unique
    private void soprano$undress(TitleMenu menu) {
        // vanilla for the rest of this screen's life, resizes included
        soprano$menu = null;
        try {
            List<TitleTile> ours = menu.undress();
            for (TitleTile tile : ours) {
                removeWidget(tile);
            }
        } catch (Throwable t) {
            Safe.report("title menu undress", t);
        }
    }
}
