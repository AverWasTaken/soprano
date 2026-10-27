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
    public void theThirdLavaBackOffOnTheSameEndermanGivesUpOnIt() {
        PearlHuntTask.LavaStrikes strikes = new PearlHuntTask.LavaStrikes();
        PearlHuntTask.AngryClock clock = new PearlHuntTask.AngryClock();
        // nothing pursued yet: no strike can land on nobody
        assertFalse(strikes.strike());
        strikes.pursue(9);
        assertFalse(strikes.strike());
        assertFalse(strikes.strike());
        assertTrue(strikes.strike());
        clock.giveUp(strikes.id());
        assertTrue(clock.givenUp(9));
        assertFalse(PearlHuntTask.lootable(clock.givenUp(9), () -> false));
    }

    @Test
    public void aNewTargetStartsTheLavaCountOver() {
        PearlHuntTask.LavaStrikes strikes = new PearlHuntTask.LavaStrikes();
        strikes.pursue(1);
        assertFalse(strikes.strike());
        assertFalse(strikes.strike());
        strikes.pursue(2);
        assertFalse(strikes.strike());
        assertFalse(strikes.strike());
        // looking at the same one again every tick does not reset it
        strikes.pursue(2);
        assertTrue(strikes.strike());
    }

    @Test
    public void nookStampGoesStale() {
        assertTrue(PearlHuntTask.nookFresh(1000, 1000));
        assertTrue(PearlHuntTask.nookFresh(1000, 1200));
        assertFalse(PearlHuntTask.nookFresh(1000, 1201));
        assertFalse(PearlHuntTask.nookFresh(Long.MIN_VALUE, 5000));
    }

    @Test
    public void lavaShoreAndGivenUpEndermenAreNotChased() {
        assertTrue(PearlHuntTask.lootable(false, () -> false));
        assertFalse(PearlHuntTask.lootable(false, () -> true));
        // the angry fight spent its 20 s: the loot walk does not hand it a fresh kill task
        assertFalse(PearlHuntTask.lootable(true, () -> false));
        // and the angry fight skips the shore ones too, whatever else is true of them
        assertTrue(PearlHuntTask.fightable(true, false, () -> false));
        assertFalse(PearlHuntTask.fightable(true, false, () -> true));
        assertFalse(PearlHuntTask.fightable(false, false, () -> false));
        assertFalse(PearlHuntTask.fightable(true, true, () -> false));
    }

    @Test
    public void theLavaCheckIsNotAskedForOnesAlreadyOut() {
        boolean[] asked = {false};
        PearlHuntTask.lootable(true, () -> asked[0] = true);
        assertFalse(asked[0]);
    }

    @Test
    public void oneGiveUpTurnsOffTheLootTargetAndTheLatch() {
        PearlHuntTask.AngryClock nook = new PearlHuntTask.AngryClock();
        PearlHuntTask.AngryClock angry = new PearlHuntTask.AngryClock();
        assertFalse(PearlHuntTask.givenUpOn(5, nook, angry));
        for (int i = 0; i < 401; i++) {
            angry.tick(5, 20);
        }
        assertTrue(PearlHuntTask.givenUpOn(5, nook, angry));
        assertFalse(PearlHuntTask.givenUpOn(6, nook, angry));
        // the nook clock alone counts too, and a missing clock is just no opinion
        for (int i = 0; i < 401; i++) {
            nook.tick(6, 20);
        }
        assertTrue(PearlHuntTask.givenUpOn(6, nook, angry));
        assertFalse(PearlHuntTask.givenUpOn(7, null, null));
    }
}
