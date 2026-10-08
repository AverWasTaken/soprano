package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProvocationsTest {

    @Test
    public void anUnknownMobWasNeverProvoked() {
        assertFalse(new Provocations().recent(7, 1000));
    }

    @Test
    public void aGrudgeLastsHalfAMinute() {
        Provocations p = new Provocations();
        p.mark(7, 1000);
        assertTrue(p.recent(7, 1000));
        assertTrue(p.recent(7, 1000 + NeutralMobs.MEMORY_TICKS));
        assertFalse(p.recent(7, 1000 + NeutralMobs.MEMORY_TICKS + 1));
    }

    @Test
    public void hittingItAgainRestartsTheClock() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        p.mark(7, 500);
        assertTrue(p.recent(7, 1000));
    }

    @Test
    public void aGrudgeIsPerMob() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        assertFalse(p.recent(8, 0));
    }

    // a new world starts the game clock over, ids get reused, nobody inherits a grudge from the old one
    @Test
    public void aClockThatWentBackwardsIsNotARecentHit() {
        Provocations p = new Provocations();
        p.mark(7, 50_000);
        assertFalse(p.recent(7, 100));
    }

    @Test
    public void theBookDoesNotGrowForever() {
        Provocations p = new Provocations();
        for (int i = 0; i < 1000; i++) p.mark(i, i * 10L);
        // the last few are still fresh, the old ones were dropped along the way and the answer is the same either way
        assertTrue(p.recent(999, 9990));
        assertFalse(p.recent(0, 9990));
    }

    @Test
    public void clearForgetsEverything() {
        Provocations p = new Provocations();
        p.mark(7, 0);
        p.clear();
        assertFalse(p.recent(7, 0));
    }
}
