package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AngryClockTest {

    @Test
    public void alternatingTargetsStillRunOut() {
        PearlHuntTask.AngryClock clock = new PearlHuntTask.AngryClock();
        boolean over = false;
        // two endermen swapping places as the nearest every 10 ticks: 20 s of limit is 400 ticks on each
        for (int i = 0; i < 1000 && !over; i++) {
            over = clock.tick((i / 10) % 2, 20);
        }
        assertTrue(over);
    }

    @Test
    public void eachIdHasItsOwnClock() {
        PearlHuntTask.AngryClock clock = new PearlHuntTask.AngryClock();
        for (int i = 0; i < 400; i++) {
            assertFalse(clock.tick(1, 20));
        }
        assertFalse(clock.tick(2, 20));
        assertTrue(clock.tick(1, 20));
    }
}
