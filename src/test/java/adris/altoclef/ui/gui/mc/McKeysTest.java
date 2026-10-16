package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Keys;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

// 1.21.11 hands over glfw numbers, which is what the kit and the guiKey setting use, so every mapping in here is the
// identity. this pins that down, a stray sdl table sneaking back in from 26.3 would move every key
public class McKeysTest {

    @Test
    public void theKitsKeysLineUpWithTheGamesConstants() {
        assertEquals(Keys.RIGHT_SHIFT, McKeys.toKit(InputConstants.KEY_RSHIFT));
        assertEquals(Keys.LEFT_SHIFT, McKeys.toKit(InputConstants.KEY_LSHIFT));
        assertEquals(Keys.RIGHT_CONTROL, McKeys.toKit(InputConstants.KEY_RCONTROL));
        assertEquals(Keys.LEFT_CONTROL, McKeys.toKit(InputConstants.KEY_LCONTROL));
        assertEquals(Keys.RIGHT_ALT, McKeys.toKit(InputConstants.KEY_RALT));
        assertEquals(Keys.LEFT_ALT, McKeys.toKit(InputConstants.KEY_LALT));
        assertEquals(Keys.ESCAPE, McKeys.toKit(InputConstants.KEY_ESCAPE));
        assertEquals(Keys.ENTER, McKeys.toKit(InputConstants.KEY_RETURN));
        assertEquals(Keys.KP_ENTER, McKeys.toKit(InputConstants.KEY_NUMPADENTER));
        assertEquals(Keys.TAB, McKeys.toKit(InputConstants.KEY_TAB));
        assertEquals(Keys.BACKSPACE, McKeys.toKit(InputConstants.KEY_BACKSPACE));
        assertEquals(Keys.DELETE, McKeys.toKit(InputConstants.KEY_DELETE));
        assertEquals(Keys.SPACE, McKeys.toKit(InputConstants.KEY_SPACE));
        assertEquals(Keys.UP, McKeys.toKit(InputConstants.KEY_UP));
        assertEquals(Keys.DOWN, McKeys.toKit(InputConstants.KEY_DOWN));
        assertEquals(Keys.LEFT, McKeys.toKit(InputConstants.KEY_LEFT));
        assertEquals(Keys.RIGHT, McKeys.toKit(InputConstants.KEY_RIGHT));
        assertEquals(Keys.HOME, McKeys.toKit(InputConstants.KEY_HOME));
        assertEquals(Keys.END, McKeys.toKit(InputConstants.KEY_END));
        assertEquals(Keys.PAGE_UP, McKeys.toKit(InputConstants.KEY_PAGEUP));
        assertEquals(Keys.PAGE_DOWN, McKeys.toKit(InputConstants.KEY_PAGEDOWN));
        assertEquals(Keys.INSERT, McKeys.toKit(InputConstants.KEY_INSERT));
        assertEquals(Keys.F2, McKeys.toKit(InputConstants.KEY_F2));
        assertEquals(Keys.F11, McKeys.toKit(InputConstants.KEY_F11));
        assertEquals(Keys.A, McKeys.toKit(InputConstants.KEY_A));
        assertEquals(Keys.C, McKeys.toKit(InputConstants.KEY_C));
        assertEquals(Keys.V, McKeys.toKit(InputConstants.KEY_V));
        assertEquals(Keys.X, McKeys.toKit(InputConstants.KEY_X));
        assertEquals(Keys.Z, McKeys.toKit(InputConstants.KEY_Z));
        assertEquals(Keys.ONE, McKeys.toKit(InputConstants.KEY_1));
        assertEquals('0', McKeys.toKit(InputConstants.KEY_0));
        assertEquals('9', McKeys.toKit(InputConstants.KEY_9));
        assertEquals('Z', McKeys.toKit(InputConstants.KEY_Z));
    }

    @Test
    public void theDefaultBindIsRightShiftOnTheGamesSide() {
        // the setting default is 344, that has to land on the game's own right shift key
        assertEquals(InputConstants.KEY_RSHIFT, McKeys.toGame(344));
        assertEquals(344, InputConstants.KEY_RSHIFT);
    }

    @Test
    public void everyGlfwKeyRoundTripsUnchanged() {
        // a settings file from the 1.21.4 or 26.3 build holds glfw numbers, they have to mean the same key here
        for (int key = 32; key <= 348; key++) {
            assertEquals("key " + key, key, McKeys.toKit(key));
            assertEquals("key " + key, key, McKeys.toGame(McKeys.toKit(key)));
        }
    }

    @Test
    public void nothingStaysNothing() {
        assertEquals(Keys.NONE, McKeys.toKit(InputConstants.UNKNOWN.getValue()));
        assertEquals(Keys.NONE, McKeys.toKit(0));
        assertEquals(Keys.NONE, McKeys.toKit(-1));
        assertEquals(InputConstants.UNKNOWN.getValue(), McKeys.toGame(Keys.NONE));
        assertEquals(-1, McKeys.toGame(0));
        // media play as the 26.3 build saves it, glfw has no number for it
        assertEquals(-1, McKeys.toGame(0x10000 + 262));
    }

    @Test
    public void mouseButtonsAreGlfwsFromZero() {
        assertEquals(0, McKeys.buttonToKit(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(0, InputConstants.MOUSE_BUTTON_LEFT);
        assertEquals(1, McKeys.buttonToKit(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(2, McKeys.buttonToKit(InputConstants.MOUSE_BUTTON_MIDDLE));
    }
}
