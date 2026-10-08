package adris.altoclef.tasks.construction;

import org.junit.Test;

import static adris.altoclef.tasks.construction.StationAttempt.Phase.AIM;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.DONE;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.FALLBACK;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.PICK;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.RELOCATE;
import static adris.altoclef.tasks.construction.StationAttempt.Phase.VERIFY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StationAttemptTest {

    private static void tickN(StationAttempt a, int n) {
        for (int i = 0; i < n; i++) {
            a.tick();
        }
    }

    // one whole try that ends with the block not showing up
    private static void loseATry(StationAttempt a) {
        a.picked();
        a.clicked();
        for (int i = 0; i < StationAttempt.VERIFY_TICKS && a.phase() == VERIFY; i++) {
            a.tick();
            a.verify(false);
        }
    }

    @Test
    public void aCleanPlacementIsDoneAfterTheBlockSettles() {
        StationAttempt a = new StationAttempt();
        assertEquals(PICK, a.phase());
        a.picked();
        assertEquals(AIM, a.phase());
        a.clicked();
        assertEquals(VERIFY, a.phase());
        for (int i = 0; i < StationAttempt.SETTLE_TICKS - 1; i++) {
            a.tick();
            a.verify(true);
            assertEquals(VERIFY, a.phase());
        }
        a.tick();
        a.verify(true);
        assertEquals(DONE, a.phase());
    }

    // the client puts the block there the moment we click, the server can take it back
    @Test
    public void aFlickeringBlockDoesNotCount() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.clicked();
        a.tick();
        a.verify(true);
        a.tick();
        a.verify(true);
        a.tick();
        a.verify(false);
        a.tick();
        a.verify(true);
        a.tick();
        a.verify(true);
        assertEquals(VERIFY, a.phase());
        a.tick();
        a.verify(true);
        assertEquals(DONE, a.phase());
    }

    @Test
    public void noBlockInTenTicksLosesTheTry() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.clicked();
        for (int i = 0; i < StationAttempt.VERIFY_TICKS - 1; i++) {
            a.tick();
            a.verify(false);
            assertEquals(VERIFY, a.phase());
        }
        a.tick();
        a.verify(false);
        assertEquals(PICK, a.phase());
        assertEquals(1, a.tries());
    }

    @Test
    public void aimingTimesOut() {
        StationAttempt a = new StationAttempt();
        a.picked();
        tickN(a, StationAttempt.AIM_TICKS - 1);
        assertFalse(a.aimTimedOut());
        a.tick();
        assertTrue(a.aimTimedOut());
        a.failed();
        assertEquals(PICK, a.phase());
        // and the clock restarts with the next spot
        a.picked();
        assertFalse(a.aimTimedOut());
    }

    // the old fail count lived on a task that was thrown away with the spot, so the 4th failure never came. this one lives here
    @Test
    public void failuresAddUpAcrossSpotsAndThirdOneRelocates() {
        StationAttempt a = new StationAttempt();
        loseATry(a);
        assertEquals(PICK, a.phase());
        loseATry(a);
        assertEquals(PICK, a.phase());
        assertEquals(2, a.tries());
        loseATry(a);
        assertEquals(RELOCATE, a.phase());
        assertEquals(1, a.relocations());
        assertEquals(0, a.tries());
    }

    @Test
    public void arrivingStartsAFreshPatch() {
        StationAttempt a = new StationAttempt();
        a.noSpot();
        assertEquals(RELOCATE, a.phase());
        a.arrived();
        assertEquals(PICK, a.phase());
        assertEquals(0, a.tries());
        // a late arrived() from somewhere else is not allowed to yank us out of a placement
        a.picked();
        a.arrived();
        assertEquals(AIM, a.phase());
    }

    @Test
    public void twoRelocationsThenTheOldPlacerGetsIt() {
        StationAttempt a = new StationAttempt();
        a.noSpot();
        assertEquals(RELOCATE, a.phase());
        a.arrived();
        a.noSpot();
        assertEquals(RELOCATE, a.phase());
        assertEquals(2, a.relocations());
        a.arrived();
        a.noSpot();
        assertEquals(FALLBACK, a.phase());
        // the cap holds: losing tries on the last patch goes to the fallback too
        assertEquals(2, a.relocations());
    }

    @Test
    public void threeLostTriesOnTheLastPatchAlsoFallBack() {
        StationAttempt a = new StationAttempt();
        a.noSpot();
        a.arrived();
        a.noSpot();
        a.arrived();
        loseATry(a);
        loseATry(a);
        assertEquals(PICK, a.phase());
        loseATry(a);
        assertEquals(FALLBACK, a.phase());
    }

    @Test
    public void nowhereToStandMeansFallBackNow() {
        StationAttempt a = new StationAttempt();
        a.noSpot();
        assertEquals(RELOCATE, a.phase());
        a.noStandpoint();
        assertEquals(FALLBACK, a.phase());
    }

    @Test
    public void verifyOutsideTheVerifyPhaseDoesNothing() {
        StationAttempt a = new StationAttempt();
        a.verify(true);
        a.verify(true);
        a.verify(true);
        assertEquals(PICK, a.phase());
    }

    @Test
    public void aRefusedClickCountsAsALostTry() {
        StationAttempt a = new StationAttempt();
        a.picked();
        a.failed();
        assertEquals(PICK, a.phase());
        assertEquals(1, a.tries());
    }
}
