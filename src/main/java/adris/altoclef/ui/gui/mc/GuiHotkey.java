package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.KeyBind;
import baritone.Baritone;
import baritone.api.Settings;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

// the front door: the keyboard mixin asks onKey for every press, #gui calls open
public final class GuiHotkey {

    private GuiHotkey() {
    }

    // true means the press was ours and the game should not see it. the event and the bind are both glfw numbers on
    // 1.21.11, McKeys is still the bridge so the 26.3 build only differs in there
    public static boolean onKey(KeyEvent event, int action) {
        try {
            Minecraft mc = Minecraft.getInstance();
            // the game looks at ctrl+b (narrator) before it asks the screen, and a text field of ours is not an edit box
            // it knows about, so typing it in the search field would flip the narrator. hand it to the screen ourselves
            // same test vanilla's narrator hotkey runs: glfw's b and the platform's shortcut modifier, so it also holds
            // with cmd on a mac
            if (event.key() == InputConstants.KEY_B && (action == InputConstants.PRESS || action == InputConstants.REPEAT)
                    && event.hasControlDownWithQuirk() && mc.screen instanceof GuiScreen gui) {
                gui.keyPressed(event);
                return true;
            }
            int key = McKeys.toKit(event.key());
            if (action != InputConstants.PRESS || key < 0) {
                return false;
            }
            Settings s = Baritone.settings();
            KeyBind bind = new KeyBind(s.guiKey.value);
            bind.enabled = s.guiKeyEnabled.value;
            if (!bind.opens(key, mc.screen != null) || !canOpen(mc)) {
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
        if (mc.screen instanceof GuiScreen) {
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
        return mc.screen == null && mc.player != null && mc.level != null && mc.getOverlay() == null;
    }

    private static void show(Minecraft mc) {
        if (mc.screen instanceof GuiScreen) {
            return;
        }
        mc.setScreen(new GuiScreen());
    }
}
