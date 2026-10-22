package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the block over our head: when it applies, and how long it gets before the shield has to take over
public class CreeperCapTest {

    @Test
    public void aCreeperUpTheShaftWithNoWayOutGetsACap() {
        assertTrue(CreeperCap.creeperAbove(true, 3.5, 0.7));
    }

    @Test
    public void withAWayOutThereIsNoCap() {
        assertFalse(CreeperCap.creeperAbove(false, 3.5, 0.7));
    }

    @Test
    public void levelOrBelowIsNotAbove() {
        assertFalse(CreeperCap.creeperAbove(true, 0, 0.5));
        assertFalse(CreeperCap.creeperAbove(true, 1, 0.5));
        assertFalse(CreeperCap.creeperAbove(true, -3, 0.5));
    }

    @Test
    public void besideTheShaftIsNotAbove() {
        assertFalse(CreeperCap.creeperAbove(true, 3, 2.5));
        assertTrue(CreeperCap.creeperAbove(true, 3, 1.4));
    }

    @Test
    public void theCapLeavesRoomForTheShield() {
        assertTrue(CreeperCap.inWindow(0));
        assertTrue(CreeperCap.inWindow(CreeperCap.CAP_TICKS - 1));
        assertFalse(CreeperCap.inWindow(CreeperCap.CAP_TICKS));
        // the clock going backwards (a new world) is not a window
        assertFalse(CreeperCap.inWindow(-5));
        // and the shield still has time to raise inside the 30 tick fuse
        assertTrue(CreeperCap.CAP_TICKS + 5 < 30);
    }
}
