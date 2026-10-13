package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.KeyBind;
import baritone.Baritone;
import baritone.api.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

// the front door: the keyboard mixin asks onKey for every press, #gui calls open
public final class GuiHotkey {

    private static final int GLFW_PRESS = 1;
    private static final int GLFW_REPEAT = 2;
    private static final int GLFW_KEY_B = 66;

    private GuiHotkey() {
    }

    // true means the press was ours and the game should not see it
    public static boolean onKey(int key, int action) {
        try {
            Minecraft mc = Minecraft.getInstance();
            // the game looks at ctrl+b (narrator) before it asks the screen, and a text field of ours is not an edit box
            // it knows about, so typing it in the search field would flip the narrator. hand it to the screen ourselves
            if (key == GLFW_KEY_B && (action == GLFW_PRESS || action == GLFW_REPEAT) && Screen.hasControlDown()
                    && mc.gui.screen() instanceof GuiScreen gui) {
                gui.keyPressed(key, 0, Keys.MOD_CTRL);
                return true;
            }
            if (action != GLFW_PRESS || key < 0) {
                return false;
            }
            Settings s = Baritone.settings();
            KeyBind bind = new KeyBind(s.guiKey.value);
            bind.enabled = s.guiKeyEnabled.value;
            if (!bind.opens(key, mc.gui.screen() != null) || !canOpen(mc)) {
                return false;
            }
            show(mc);
            return true;
        } catch (Throwable t) {
            Safe.report("gui hotkey", t);
            return false;
        }
    }

    // for #gui: null when it is open or on its way, else what to tell the player. it runs from inside chat's enter key,
    // and the chat screen closes itself right after the command, which would take a screen opened now down with it. so
    // open on the next tick, when chat is gone
    public static String open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof GuiScreen) {
            return null;
        }
        if (mc.player == null || mc.level == null) {
            return "Join a world first, the gui needs one behind it.";
        }
        if (mc.getOverlay() != null) {
            return "The game is still loading. Try again in a second.";
        }
        mc.schedule(() -> {
            try {
                if (canOpen(mc)) {
                    show(mc);
                }
            } catch (Throwable t) {
                Safe.report("gui open", t);
            }
        });
        return null;
    }

    private static boolean canOpen(Minecraft mc) {
        return mc.gui.screen() == null && mc.player != null && mc.level != null && mc.getOverlay() == null;
    }

    private static void show(Minecraft mc) {
        if (mc.gui.screen() instanceof GuiScreen) {
            return;
        }
        mc.gui.setScreen(new GuiScreen());
    }
}
