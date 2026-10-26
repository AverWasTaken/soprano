package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the sword's charge gate and the sprint lock that sets up the sweep
public class SwordSwingTest {

    @Test
    public void sprintComesOffInTheLastTicksOfTheCharge() {
        assertTrue(SwordSwing.lockSprint(true, true, 0, false));
        assertTrue(SwordSwing.lockSprint(true, true, SwordSwing.LOCK_LEAD_TICKS, false));
        // still charging with ticks to spare: nothing to lock yet, sprinting up to the mob is fine
        assertFalse(SwordSwing.lockSprint(true, true, SwordSwing.LOCK_LEAD_TICKS + 0.5, false));
        assertFalse(SwordSwing.lockSprint(true, true, 20, false));
    }

    @Test
    public void noLockWithoutASwordOrOutOfReach() {
        assertFalse(SwordSwing.lockSprint(false, true, 0, false));
        assertFalse(SwordSwing.lockSprint(true, false, 0, false));
    }

    @Test
    public void aCalmNeutralMobBesideTheTargetKeepsSprintAlone() {
        // a sprint hit can't sweep, so the neutral one next to the target is left out of it
        assertFalse(SwordSwing.lockSprint(true, true, 0, true));
    }

    @Test
    public void swingNeedsTheChargeWhateverElse() {
        assertFalse(SwordSwing.swing(false, false, false));
        assertFalse(SwordSwing.swing(false, true, true));
    }

    @Test
    public void chargedAndNotLockedSwingsAtOnce() {
        assertTrue(SwordSwing.swing(true, false, false));
    }

    @Test
    public void aFreshLockWaitsOneTickForTheStopPacket() {
        // asked this very tick: the server has not seen not-sprinting yet
        assertFalse(SwordSwing.swing(true, true, false));
        // asked on an earlier tick too: the stop is out
        assertTrue(SwordSwing.swing(true, true, true));
    }

    @Test
    public void everyChargeAndLockStateHasOneAnswer() {
        for (boolean ready : new boolean[]{false, true}) {
            for (boolean asked : new boolean[]{false, true}) {
                for (boolean earlier : new boolean[]{false, true}) {
                    boolean expected = ready && (!asked || earlier);
                    assertEquals(ready + " " + asked + " " + earlier, expected, SwordSwing.swing(ready, asked, earlier));
                }
            }
        }
    }
}
