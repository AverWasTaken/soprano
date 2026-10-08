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
    public void aWalkThatIsGettingSomewhereKeepsTheClockFromRunningOut() {
        DropPatience p = new DropPatience();
        p.lock(7);
        double dist = 30;
        // 300 ticks is three times the budget, but every 20 of them we are 2 blocks closer
        for (int i = 0; i < 300; i++) {
            p.tick();
            if (i % 20 == 0) {
                dist -= 2;
            }
            p.progress(dist);
            assertFalse("tick " + i, p.expired());
        }
    }

    @Test
    public void standingStillOrBackingOffGetsNoCredit() {
        DropPatience p = new DropPatience();
        p.lock(7);
        p.progress(10);
        for (int i = 0; i < DropPatience.TICKS; i++) {
            p.tick();
            // a tenth of a block of jitter is not a walk, and further than before is not a walk either
            p.progress(i % 2 == 0 ? 9.9 : 12);
        }
        p.tick();
        p.progress(9.8);
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
