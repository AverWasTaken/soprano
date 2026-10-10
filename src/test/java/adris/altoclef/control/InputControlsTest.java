package adris.altoclef.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import baritone.api.utils.input.Input;
import org.junit.Before;
import org.junit.Test;

public class InputControlsTest {
    private FakeKeys keys;
    private InputControls controls;

    @Before
    public void setUp() {
        keys = new FakeKeys();
        controls = new InputControls(keys);
    }

    @Test
    public void aHoldStaysUntilReleased() {
        controls.hold(Input.SNEAK);
        controls.onTickPre();
        controls.onTickPost();
        assertTrue(controls.isHeldDown(Input.SNEAK));
        controls.release(Input.SNEAK);
        assertFalse(controls.isHeldDown(Input.SNEAK));
    }

    @Test
    public void aFreshHoldClicksOnceAndAKeptHoldDoesNot() {
        controls.hold(Input.MOVE_FORWARD);
        controls.hold(Input.MOVE_FORWARD);
        assertEquals(1, keys.clicks);
    }

    @Test
    public void aTryPressLastsOneTick() {
        controls.tryPress(Input.JUMP);
        assertTrue(controls.isHeldDown(Input.JUMP));
        // a second press in the same tick is swallowed
        controls.tryPress(Input.JUMP);
        assertEquals(1, keys.clicks);
        controls.onTickPre();
        assertFalse(controls.isHeldDown(Input.JUMP));
    }

    @Test
    public void aHeldLeftClickWaitsWhileAnEntityIsUnderTheCrosshair() {
        keys.entityUnderCrosshair = true;
        controls.hold(Input.CLICK_LEFT);
        assertFalse(controls.isHeldDown(Input.CLICK_LEFT));
        keys.entityUnderCrosshair = false;
        controls.hold(Input.CLICK_LEFT);
        assertTrue(controls.isHeldDown(Input.CLICK_LEFT));
    }
}
