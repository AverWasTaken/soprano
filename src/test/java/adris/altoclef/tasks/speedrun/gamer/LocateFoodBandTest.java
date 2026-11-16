package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LocateFoodBandTest {
    // floor 24, want 56, stop 70. held = bag here unless a test says otherwise
    private static String tick(LocateFoodBand b, int held, boolean prey, boolean eyeOut, long now) {
        return b.tick(held, held, 24, 56, 70, prey, eyeOut, false, now);
    }

    private static String cook(LocateFoodBand b, int held, int bag, boolean prey, long now) {
        return b.tick(held, bag, 24, 56, 70, prey, false, true, now);
    }

    @Test
    public void startsOnlyInTheBandWithPreyAndNoEyeOut() {
        LocateFoodBand b = new LocateFoodBand();
        assertNull(tick(b, 41, false, false, 0));
        assertFalse(b.active());
        assertNull(tick(b, 41, true, true, 0));
        assertFalse(b.active());
        // under the floor is the hard floor's, at the want there's nothing to do
        assertNull(tick(b, 23, true, false, 0));
        assertNull(tick(b, 56, true, false, 0));
        assertFalse(b.active());
        assertNotNull(tick(b, 24, true, false, 0));
        assertTrue(b.active());
    }

    @Test
    public void runsToTheStopNotTheWant() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 0);
        assertNull(tick(b, 60, true, false, 20));
        assertTrue(b.active());
        assertNotNull(tick(b, 70, true, false, 40));
        assertFalse(b.active());
        // got there, so it may come back later in the visit once the bag drops again
        assertNotNull(tick(b, 50, true, false, 60));
        assertTrue(b.active());
    }

    @Test
    public void preyGoneEndsItAndNoRestartThisVisit() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 0);
        assertNotNull(tick(b, 45, false, false, 100));
        assertFalse(b.active());
        assertNull(tick(b, 45, true, false, 200));
        assertFalse(b.active());
        // a new visit starts clean
        b.reset();
        assertNotNull(tick(b, 45, true, false, 300));
        assertTrue(b.active());
    }

    @Test
    public void capEndsItAndNoRestart() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 1000);
        assertNull(tick(b, 50, true, false, 1000 + LocateFoodBand.CAP_TICKS - 1));
        assertTrue(b.active());
        assertNotNull(tick(b, 50, true, false, 1000 + LocateFoodBand.CAP_TICKS));
        assertFalse(b.active());
        assertNull(tick(b, 50, true, false, 1000 + LocateFoodBand.CAP_TICKS + 20));
        assertFalse(b.active());
    }

    @Test
    public void clockGoingBackEndsIt() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 1000);
        assertNotNull(tick(b, 40, true, false, 500));
        assertFalse(b.active());
    }

    @Test
    public void handingToTheFloorIsNotSpent() {
        LocateFoodBand b = new LocateFoodBand();
        assertNull(b.drop(20));
        tick(b, 30, true, false, 0);
        assertNotNull(b.drop(20));
        assertFalse(b.active());
        // back in the band after the floor's trip ate into it again: allowed
        assertNotNull(tick(b, 40, true, false, 100));
        assertTrue(b.active());
    }

    @Test
    public void anEyeThrownMidTopUpDoesNotEndIt() {
        // can't happen in play (the locate task isn't running while we hunt) but the band only gates the start on it
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 0);
        assertNull(tick(b, 42, true, true, 20));
        assertTrue(b.active());
    }

    @Test
    public void aBatchInTheSmokerHoldsEveryExit() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 0);
        // the meat counting in at the smoker screen pushes held over the stop, the bag isn't there yet
        assertNull(cook(b, 80, 50, true, 20));
        assertTrue(b.active());
        // the last animal died and the cap is past: still cooking, still on
        assertNull(cook(b, 80, 50, false, LocateFoodBand.CAP_TICKS + 20));
        assertTrue(b.active());
        // the batch is out: judged on the bag now
        assertNotNull(tick(b, 72, false, false, LocateFoodBand.CAP_TICKS + 40));
        assertFalse(b.active());
    }

    @Test
    public void theCookGraceRunsOutToo() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 0);
        assertNotNull(cook(b, 50, 50, true, LocateFoodBand.CAP_TICKS + LocateFoodBand.COOK_GRACE_TICKS));
        assertFalse(b.active());
    }

    @Test
    public void doneGoesByTheBagNotHeld() {
        LocateFoodBand b = new LocateFoodBand();
        b.tick(40, 40, 24, 56, 70, true, false, false, 0);
        // held says 75 (a far batch still counts there), the bag has 60: keep going
        assertNull(b.tick(75, 60, 24, 56, 70, true, false, false, 20));
        assertTrue(b.active());
        assertNotNull(b.tick(75, 70, 24, 56, 70, true, false, false, 40));
        assertFalse(b.active());
    }

    @Test
    public void startGoesByHeldForTheFloorAndTheBagForTheWant() {
        LocateFoodBand b = new LocateFoodBand();
        // under the floor by held: the floor's
        assertNull(b.tick(20, 20, 24, 56, 70, true, false, false, 0));
        // held over the want only thanks to a far batch: the bag is short, so it starts
        assertNotNull(b.tick(60, 40, 24, 56, 70, true, false, false, 0));
        assertTrue(b.active());
    }

    @Test
    public void aRetryStandsDownButKeepsSpent() {
        LocateFoodBand b = new LocateFoodBand();
        tick(b, 40, true, false, 0);
        b.standDown();
        assertFalse(b.active());
        // not spent yet, so the retry may start it again
        assertNotNull(tick(b, 40, true, false, 100));
        assertNotNull(tick(b, 40, false, false, 200));
        b.standDown();
        // spent before the retry: stays off
        assertNull(tick(b, 40, true, false, 300));
        assertFalse(b.active());
    }
}
