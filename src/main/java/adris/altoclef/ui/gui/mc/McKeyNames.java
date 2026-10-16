package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.KeyNames;
import com.mojang.blaze3d.platform.InputConstants;

// "Right Shift" for 344, straight from the game's own key table so it follows the language. on 1.21.11 that table is
// keyed by glfw code already, McKeys.toGame is the identity here and only kept so the 26.3 build differs in one place
final class McKeyNames implements KeyNames {

    @Override
    public String name(int glfwKey) {
        if (glfwKey < 0) {
            return "None";
        }
        try {
            int code = McKeys.toGame(glfwKey);
            if (code > 0) {
                String s = InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // falls through to the number
        }
        return "Key " + glfwKey;
    }
}
