package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.KeyNames;
import com.mojang.blaze3d.platform.InputConstants;

// "Right Shift" for 229, straight from the game's own key table so it follows the language
// todo 26.3: the game keys are sdl scancodes now (InputConstants.KEY_*), so the "glfw" codes the guiKey setting and the
// facade hand us (default 344) have to become scancodes too, or this names the wrong key
final class McKeyNames implements KeyNames {

    @Override
    public String name(int glfwKey) {
        if (glfwKey < 0) {
            return "None";
        }
        try {
            String s = InputConstants.Type.KEYBOARD.getOrCreate(glfwKey).getDisplayName().getString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        } catch (Throwable ignored) {
            // falls through to the number
        }
        return "Key " + glfwKey;
    }
}
