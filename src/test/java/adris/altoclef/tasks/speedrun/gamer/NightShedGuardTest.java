package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.NightSleep.ShedGuard;
import org.junit.Test;

// the night bed's throws for a full bag stay bounded: a few different stacks, and one that never goes out ends it
public class NightShedGuardTest {

    @Test
    public void theSameThrowIsAllowedWhileItIsStillGoingOut() {
        ShedGuard g = new ShedGuard();
        assertTrue(g.isNew("cobble"));
        assertTrue(g.allow("cobble", 0));
        assertFalse(g.isNew("cobble"));
        assertTrue(g.allow("cobble", ShedGuard.PATIENCE_TICKS));
        assertEquals(1, g.throwsAsked());
        assertFalse(g.gaveUp());
    }

    @Test
    public void aThrowThatNeverWentOutEndsIt() {
        ShedGuard g = new ShedGuard();
        assertTrue(g.allow("cobble", 0));
        assertFalse(g.allow("cobble", ShedGuard.PATIENCE_TICKS + 1));
        assertTrue(g.gaveUp());
        // and stays ended, a fresh stack doesn't get another go
        assertFalse(g.allow("dirt", ShedGuard.PATIENCE_TICKS + 2));
        assertFalse(g.isNew("dirt"));
    }

    @Test
    public void onlyCapDifferentThrows() {
        ShedGuard g = new ShedGuard();
        for (int i = 0; i < ShedGuard.CAP; i++) {
            assertTrue(g.allow("stack" + i, i));
        }
        assertFalse(g.allow("one more", ShedGuard.CAP));
        assertTrue(g.gaveUp());
        assertEquals(ShedGuard.CAP, g.throwsAsked());
    }
}
