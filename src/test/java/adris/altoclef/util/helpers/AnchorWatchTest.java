package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AnchorWatchTest {

    @Test
    public void standingStillForSixtyTicksIsAnchored() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 0; t < AnchorWatch.TICKS; t++) {
            assertFalse("tick " + t, w.update(t, 10, 64, 10));
        }
        assertTrue(w.update(AnchorWatch.TICKS, 10, 64, 10));
        assertTrue(w.update(AnchorWatch.TICKS + 1, 10, 64, 10));
    }

    @Test
    public void walkingNeverGetsThere() {
        AnchorWatch w = new AnchorWatch();
        // a bot that is walking covers more than two blocks long before a second is up
        for (long t = 0; t < 400; t++) {
            assertFalse("tick " + t, w.update(t, t * 0.2, 64, 0));
        }
    }

    @Test
    public void aShoveOfOneBlockDoesNotClearIt() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 0; t <= AnchorWatch.TICKS; t++) w.update(t, 0, 64, 0);
        assertTrue(w.update(AnchorWatch.TICKS + 1, 1, 64, 0));
        assertTrue(w.update(AnchorWatch.TICKS + 2, 0, 64, 1));
    }

    @Test
    public void aMoveOfThreeBlocksStartsOver() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 0; t <= AnchorWatch.TICKS; t++) w.update(t, 0, 64, 0);
        long moved = AnchorWatch.TICKS + 1;
        assertFalse(w.update(moved, 3, 64, 0));
        // and it takes the full sixty again from the new spot
        for (long t = moved + 1; t < moved + AnchorWatch.TICKS; t++) {
            assertFalse("tick " + t, w.update(t, 3, 64, 0));
        }
        assertTrue(w.update(moved + AnchorWatch.TICKS, 3, 64, 0));
    }

    // height counts: pillaring up out of a hole is not standing about
    @Test
    public void goingUpAndDownCountsToo() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 0; t <= AnchorWatch.TICKS; t++) w.update(t, 0, 64, 0);
        assertFalse(w.update(AnchorWatch.TICKS + 1, 0, 67, 0));
    }

    @Test
    public void notBeingFedForAWhileLosesTrack() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 0; t <= AnchorWatch.TICKS; t++) w.update(t, 0, 64, 0);
        // a gap (the game was paused, or the chain was not ticking): we do not know where we were in between
        assertFalse(w.update(AnchorWatch.TICKS + 100, 0, 64, 0));
    }

    @Test
    public void aClockThatWentBackwardsStartsOver() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 5000; t <= 5000 + AnchorWatch.TICKS; t++) w.update(t, 0, 64, 0);
        assertFalse(w.update(100, 0, 64, 0));
    }

    @Test
    public void resetForgetsIt() {
        AnchorWatch w = new AnchorWatch();
        for (long t = 0; t <= AnchorWatch.TICKS; t++) w.update(t, 0, 64, 0);
        w.reset();
        assertFalse(w.update(AnchorWatch.TICKS + 1, 0, 64, 0));
    }
}
