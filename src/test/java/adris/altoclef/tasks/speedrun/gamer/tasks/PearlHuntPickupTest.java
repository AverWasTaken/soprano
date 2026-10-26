package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlHuntPickupTest {
    @Test
    public void aPearlOnTheSameFloorIsClose() {
        assertTrue(PearlHuntTask.pickupInReach(10 * 10));
        assertTrue(PearlHuntTask.pickupInReach(16 * 16));
        assertFalse(PearlHuntTask.pickupInReach(17 * 17));
    }

    @Test
    public void aPearlDownACliffIsNotClose() {
        // 5 blocks across but 20 down: flat distance says yes, the real one says no
        double flat = 5 * 5;
        assertTrue(PearlHuntTask.pickupInReach(flat));
        assertFalse(PearlHuntTask.pickupInReach(flat + 20 * 20));
    }
}
