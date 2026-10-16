package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Keys;
import net.minecraft.client.input.InputWithModifiers;

// the one place the game's numbers become the kit's. 1.21.11 still reads the keyboard through glfw, so a key is its glfw
// code (right shift 344, escape 256) and a mouse button counts from 0, which is exactly what the kit, the guiKey setting
// and the keybind logic speak. the 26.3 build has an sdl table in here instead. the setting is glfw numbers on every
// version, so a settings.txt carries over between the 1.21.4, 1.21.11 and 26.3 builds untouched
public final class McKeys {

    private McKeys() {
    }

    // glfw key code in, glfw key code out. the game's "unknown key" is -1 and 0 is no key glfw ever sends
    public static int toKit(int glfwKey) {
        return glfwKey <= 0 ? Keys.NONE : glfwKey;
    }

    // glfw stops numbering at the menu key
    private static final int LAST_KEY = 348;

    // and back. -1 is the game's own "unknown key". the 26.3 build can save keys glfw never numbered (they ride above
    // 0x10000 there), those are nothing on this side
    public static int toGame(int kitKey) {
        return kitKey <= 0 || kitKey > LAST_KEY ? -1 : kitKey;
    }

    // left 0, right 1, middle 2 in the game and in the kit
    public static int buttonToKit(int button) {
        return Math.max(0, button);
    }

    // the events carry the modifiers that were held for that very input, so no asking the keyboard
    public static int mods(InputWithModifiers e) {
        int m = 0;
        if (e.hasShiftDown()) {
            m |= Keys.MOD_SHIFT;
        }
        // the shortcut modifier: ctrl, or cmd on a mac, same as 1.21.4's Screen.hasControlDown
        if (e.hasControlDownWithQuirk()) {
            m |= Keys.MOD_CTRL;
        }
        if (e.hasAltDown()) {
            m |= Keys.MOD_ALT;
        }
        return m;
    }
}
