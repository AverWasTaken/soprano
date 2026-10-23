package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomePortalWalkTest {
    @Test
    public void noGraceBeforeWeArrive() {
        assertFalse(HomePortalWalk.graceOver(0, 1_000_000));
    }

    @Test
    public void graceRunsFromTheArrival() {
        assertFalse(HomePortalWalk.graceOver(1000, 5000));
        assertTrue(HomePortalWalk.graceOver(1000, 6001));
    }
}
