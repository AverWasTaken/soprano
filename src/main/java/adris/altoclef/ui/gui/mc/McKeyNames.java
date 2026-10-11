package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.KeyNames;
import com.mojang.blaze3d.platform.InputConstants;

// "Right Shift" for 344, straight from the game's own key table so it follows the language
final class McKeyNames implements KeyNames {

    @Override
    public String name(int glfwKey) {
        if (glfwKey < 0) {
            return "None";
        }
        try {
            String s = InputConstants.Type.KEYSYM.getOrCreate(glfwKey).getDisplayName().getString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        } catch (Throwable ignored) {
            // falls through to the number
        }
        return "Key " + glfwKey;
    }
}
