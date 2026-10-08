package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DropPatienceTest {
    @Test
    public void nothingLockedNeverExpires() {
        DropPatience p = new DropPatience();
        for (int i = 0; i < DropPatience.TICKS * 3; i++) {
            p.tick();
        }
        assertFalse(p.isLocked());
        assertFalse(p.expired());
    }

    @Test
    public void aDropIsHeldForExactlyTheBudgetThenGivenUp() {
        DropPatience p = new DropPatience();
        p.lock(7);
        for (int i = 0; i < DropPatience.TICKS; i++) {
            p.tick();
            assertFalse("tick " + i, p.expired());
        }
        p.tick();
        assertTrue(p.expired());
        p.giveUp();
        assertFalse(p.isLocked());
        assertTrue(p.gaveUp(7));
        assertFalse(p.gaveUp(8));
    }

    @Test
    public void askingForTheSameDropAgainDoesNotRestartTheClock() {
        DropPatience p = new DropPatience();
        p.lock(7);
        for (int i = 0; i < DropPatience.TICKS; i++) {
            p.tick();
            p.lock(7);
        }
        p.tick();
        assertTrue(p.expired());
    }

    @Test
    public void aDifferentDropGetsItsOwnClock() {
        DropPatience p = new DropPatience();
        p.lock(7);
        for (int i = 0; i < DropPatience.TICKS; i++) {
            p.tick();
        }
        p.unlock();
        p.lock(8);
        assertFalse(p.expired());
        assertFalse(p.gaveUp(7));
    }

    @Test
    public void clearForgetsWhoWeGaveUpOn() {
        DropPatience p = new DropPatience();
        p.lock(7);
        p.giveUp();
        p.clear();
        assertFalse(p.gaveUp(7));
        assertFalse(p.isLocked());
    }

    @Test
    public void aDropInsideTheVanillaPickupBoxIsLeftToVanilla() {
        assertTrue(DropPatience.alreadyInReach(0.4, 0.0, -0.9));
        assertTrue(DropPatience.alreadyInReach(0, -0.4, 0));
        assertFalse(DropPatience.alreadyInReach(1.5, 0, 0));
        assertFalse(DropPatience.alreadyInReach(0, -1.0, 0));
        assertFalse(DropPatience.alreadyInReach(0, 2.5, 0));
    }
}
