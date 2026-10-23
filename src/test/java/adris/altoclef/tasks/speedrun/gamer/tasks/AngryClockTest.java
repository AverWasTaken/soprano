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

    @Test
    public void anIdThatRanOutStaysGivenUp() {
        PearlHuntTask.AngryClock clock = new PearlHuntTask.AngryClock();
        assertFalse(clock.givenUp(7));
        for (int i = 0; i < 1200; i++) {
            assertFalse(clock.tick(7, 60));
        }
        assertFalse(clock.givenUp(7));
        assertTrue(clock.tick(7, 60));
        assertTrue(clock.givenUp(7));
        assertFalse(clock.givenUp(8));
    }

    @Test
    public void theSameClockAcrossTwoNooksKeepsCounting() {
        // the clock belongs to the hunt, so a second nook picks up where the first one left off
        PearlHuntTask.AngryClock clock = new PearlHuntTask.AngryClock();
        for (int i = 0; i < 700; i++) {
            clock.tick(3, 60);
        }
        for (int i = 0; i < 500; i++) {
            assertFalse(clock.tick(3, 60));
        }
        assertTrue(clock.tick(3, 60));
        assertTrue(clock.givenUp(3));
    }

    @Test
    public void nookStampGoesStale() {
        assertTrue(PearlHuntTask.nookFresh(1000, 1000));
        assertTrue(PearlHuntTask.nookFresh(1000, 1200));
        assertFalse(PearlHuntTask.nookFresh(1000, 1201));
        assertFalse(PearlHuntTask.nookFresh(Long.MIN_VALUE, 5000));
    }
}
