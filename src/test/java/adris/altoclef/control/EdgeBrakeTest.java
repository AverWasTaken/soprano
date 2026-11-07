package adris.altoclef.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EdgeBrakeTest {
    // args: wasEngaged, userTask, onGround, blocked, pathing, realMoveKey, speed, supportedAhead, dropAhead
    private static boolean start(boolean userTask, boolean onGround, boolean blocked, boolean pathing, boolean key,
                                 double speed, boolean supported, boolean drop) {
        return EdgeBrake.decide(false, userTask, onGround, blocked, pathing, key, speed, supported, drop);
    }

    private static boolean hold(boolean userTask, boolean onGround, boolean blocked, boolean pathing, boolean key,
                                double speed, boolean supported) {
        return EdgeBrake.decide(true, userTask, onGround, blocked, pathing, key, speed, supported, false);
    }

    @Test
    public void engagesOnADriftTowardADrop() {
        assertTrue(start(true, true, false, false, false, 0.1, false, true));
        // a soft shove on a rim walks us off just the same
        assertTrue(start(true, true, false, false, false, 0.02, false, true));
    }

    @Test
    public void doesNotEngageWhenSafeOrGated() {
        assertFalse(start(true, true, false, true, false, 0.1, false, true));
        assertFalse(start(true, true, false, false, false, 0.009, false, true));
        assertFalse(start(true, false, false, false, false, 0.1, false, true));
        assertFalse(start(true, true, true, false, false, 0.1, false, true));
        assertFalse(start(true, true, false, false, true, 0.1, false, true));
        assertFalse(start(false, true, false, false, false, 0.1, false, true));
        assertFalse(start(true, true, false, false, false, 0.1, true, true));
        assertFalse(start(true, true, false, false, false, 0.1, false, false));
    }

    // slowing down is the brake working: it stays on until the bot is basically stopped
    @Test
    public void holdsWhileSlowing() {
        assertTrue(hold(true, true, false, false, false, 0.02, false));
        assertTrue(hold(true, true, false, false, false, 0.004, false));
        // the jar stops us under 0.003, not at it
        assertTrue(hold(true, true, false, false, false, 0.003, false));
        assertFalse(hold(true, true, false, false, false, 0.002, false));
    }

    @Test
    public void releasesWhenSupportedOrAGateFails() {
        assertFalse(hold(true, true, false, false, false, 0.1, true));
        assertFalse(hold(true, true, false, true, false, 0.1, false));
        assertFalse(hold(true, true, false, false, true, 0.1, false));
        assertFalse(hold(true, false, false, false, false, 0.1, false));
        assertFalse(hold(false, true, false, false, false, 0.1, false));
        assertFalse(hold(true, true, true, false, false, 0.1, false));
    }

    // args: userTask, blocked, realMoveKey, onGround, rising, groundUnder, speed, knocked, dropNear
    @Test
    public void knockHoldsSneakInTheAirNextToADrop() {
        // coming down still over the pillar top: sneak has to be down already when the ground comes back
        assertTrue(EdgeBrake.knockHold(true, false, false, false, false, true, 0.0, true, true));
        assertTrue(EdgeBrake.knockHold(true, false, false, false, false, true, 0.36, true, true));
        // the one grounded tick of the slide still holds
        assertTrue(EdgeBrake.knockHold(true, false, false, true, false, false, 0.27, true, true));
    }

    @Test
    public void knockHoldLetsGo() {
        // still going up: the back-off can't act yet and the crouch would only slow the lean
        assertFalse(EdgeBrake.knockHold(true, false, false, false, true, true, 0.36, true, true));
        // already past the edge, nothing under the box to back off onto
        assertFalse(EdgeBrake.knockHold(true, false, false, false, false, false, 0.36, true, true));
        // down and stopped
        assertFalse(EdgeBrake.knockHold(true, false, false, true, false, false, 0.002, true, true));
        // no drop near, or nobody hit us
        assertFalse(EdgeBrake.knockHold(true, false, false, false, false, true, 0.3, true, false));
        assertFalse(EdgeBrake.knockHold(true, false, false, false, false, true, 0.3, false, true));
        // the usual gates
        assertFalse(EdgeBrake.knockHold(false, false, false, false, false, true, 0.3, true, true));
        assertFalse(EdgeBrake.knockHold(true, true, false, false, false, true, 0.3, true, true));
        assertFalse(EdgeBrake.knockHold(true, false, true, false, false, true, 0.3, true, true));
    }
}
