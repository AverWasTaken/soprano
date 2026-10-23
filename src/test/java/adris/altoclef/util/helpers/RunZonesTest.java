package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RunZonesTest {

    private static final double NO_GOAL = Double.NaN;

    @Test
    public void aCellInsideTheRadiusIsAWallAndOutsideIsNot() {
        RunZones z = new RunZones();
        z.add(100, 100, 1000);
        assertTrue(z.blocks(110, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertTrue(z.blocks(100, 123, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(100, 124, 1000, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(130, 100, 1000, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void aZoneExpiresAfterThreeMinutes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000);
        assertTrue(z.blocks(0, 0, 1000 + RunZones.EXPIRY_TICKS - 1, 500, 500, NO_GOAL, NO_GOAL));
        assertFalse(z.blocks(0, 0, 1000 + RunZones.EXPIRY_TICKS, 500, 500, NO_GOAL, NO_GOAL));
        assertEquals(0, z.active(1000 + RunZones.EXPIRY_TICKS));
    }

    @Test
    public void aZoneWeStandInIsNoWallSoWeCanWalkOut() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000);
        assertFalse(z.blocks(10, 10, 1000, 5, 5, NO_GOAL, NO_GOAL));
    }

    @Test
    public void aZoneTheGoalIsInIsNoWallSoThePathExists() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000);
        assertFalse(z.blocks(10, 10, 1000, 500, 500, 3, 3));
        assertTrue(z.blocks(10, 10, 1000, 500, 500, 90, 90));
    }

    @Test
    public void theClockGoingBackwardsIsAnotherWorld() {
        RunZones z = new RunZones();
        z.add(0, 0, 50_000);
        assertFalse(z.blocks(0, 0, 100, 500, 500, NO_GOAL, NO_GOAL));
        z.add(900, 900, 100);
        assertEquals(1, z.active(100));
    }

    @Test
    public void twoRunsFromTheSameSpotAreOneZoneAndTheClockRefreshes() {
        RunZones z = new RunZones();
        z.add(0, 0, 1000);
        z.add(1, 1, 5000);
        assertEquals(1, z.active(5000));
        assertTrue(z.blocks(0, 0, 5000 + RunZones.EXPIRY_TICKS - 10, 500, 500, NO_GOAL, NO_GOAL));
    }

    @Test
    public void theListStaysSmall() {
        RunZones z = new RunZones();
        for (int i = 0; i < 40; i++) {
            z.add(i * 100, 0, 1000 + i);
        }
        assertEquals(16, z.active(1100));
    }
}
