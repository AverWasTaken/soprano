package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the lava hop latch: flicker ticks keep it, dry ticks release it, 50 ticks and it is done for good
public class HopLatchTest {

    @Test
    public void beginOnlyTakesTheLatchOnce() {
        HopLatch l = new HopLatch();
        assertTrue(l.begin());
        assertTrue(l.active());
        assertFalse(l.begin());
    }

    @Test
    public void flickerTicksKeepTheLatch() {
        HopLatch l = new HopLatch();
        l.begin();
        // bob out for a few ticks at a time, never ten in a row
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 5; i++) {
                l.tick(true);
            }
            l.tick(false);
            assertTrue(l.active());
        }
    }

    @Test
    public void tenClearTicksRelease() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.CLEAR_TICKS - 1; i++) {
            l.tick(true);
            assertTrue(l.active());
        }
        l.tick(true);
        assertFalse(l.active());
        assertFalse(l.spent());
        // a clean escape can hop again later
        assertTrue(l.begin());
    }

    @Test
    public void fiftyTicksGiveUpForGood() {
        HopLatch l = new HopLatch();
        l.begin();
        for (int i = 0; i < HopLatch.GIVE_UP_TICKS - 1; i++) {
            l.tick(false);
            assertTrue(l.active());
        }
        l.tick(false);
        assertFalse(l.active());
        assertTrue(l.spent());
        assertFalse(l.begin());
        assertFalse(l.active());
    }

    @Test
    public void ticksWhileIdleDoNothing() {
        HopLatch l = new HopLatch();
        for (int i = 0; i < 100; i++) {
            l.tick(false);
        }
        assertFalse(l.spent());
        assertTrue(l.begin());
    }
}
