package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import baritone.Baritone;

// whether our boot screen and title screen are on. baritone (and its settings file) only exists once Minecraft's
// constructor returns, and nothing should read settings before that, so until then the answer is just yes. 1.21.4
// draws no frame that early anyway, but it also keeps a settings class init failure out of a render call
public final class SopranoMenus {

    private static volatile boolean ready;

    private SopranoMenus() {
    }

    // MixinMinecraft calls this at the end of the constructor, right after baritone is up
    public static void markReady() {
        ready = true;
    }

    public static boolean enabled() {
        if (!ready) {
            return true;
        }
        try {
            return Baritone.settings().guiMenus.value;
        } catch (Throwable t) {
            Safe.report("menus setting", t);
            return true;
        }
    }

    // the speaker button. the music only starts once the title has shown, long after markReady, so the early yes is
    // just there to keep the answer the same shape as enabled()
    static boolean music() {
        if (!ready) {
            return true;
        }
        try {
            return Baritone.settings().guiMenuMusic.value;
        } catch (Throwable t) {
            Safe.report("menu music setting", t);
            return true;
        }
    }

    // reduced motion for the boot screen, which has no KitSurface to sync Anim.enabled for it
    static boolean animations() {
        if (!ready) {
            return true;
        }
        try {
            return Baritone.settings().guiAnimations.value;
        } catch (Throwable t) {
            Safe.report("menus animation setting", t);
            return true;
        }
    }
}
