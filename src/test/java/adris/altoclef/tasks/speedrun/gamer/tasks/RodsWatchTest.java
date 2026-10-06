package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.RodsWatch.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RodsWatchTest {
    // 6 min camp, a budget too big to get in the way
    private final RodsWatch watch = new RodsWatch(360, 100000);

    @Test
    public void walkingToTheFortressIsNotCamping() {
        assertEquals(Verdict.KEEP_GOING, watch.update(0, 0, false));
        assertEquals(Verdict.KEEP_GOING, watch.update(300, 0, false));
        assertEquals(Verdict.KEEP_GOING, watch.update(600, 0, false));
    }

    @Test
    public void campingWithoutARodGivesUpTheSpawner() {
        watch.update(0, 0, false);
        assertEquals(Verdict.KEEP_GOING, watch.update(100, 0, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(459, 0, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(460, 0, true));
    }

    @Test
    public void aRodRestartsTheCampTimer() {
        watch.update(0, 0, false);
        watch.update(100, 0, true);
        // a rod at t=400 (300 s into the camp)
        assertEquals(Verdict.KEEP_GOING, watch.update(400, 1, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(759, 1, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(760, 1, true));
    }

    @Test
    public void leavingTheSpawnerResetsTheCamp() {
        watch.update(0, 0, false);
        watch.update(100, 0, true);
        // wandered off to kill blazes for a bit
        assertEquals(Verdict.KEEP_GOING, watch.update(300, 0, false));
        // back at the spawner: the clock starts over from here
        assertEquals(Verdict.KEEP_GOING, watch.update(310, 0, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(600, 0, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(670, 0, true));
    }

    @Test
    public void afterGivingUpTheNextSpawnerGetsAFreshCamp() {
        watch.update(0, 0, false);
        watch.update(100, 0, true);
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(460, 0, true));
        watch.spawnerGivenUp(460);
        assertEquals(Verdict.KEEP_GOING, watch.update(470, 0, false));
        assertEquals(Verdict.KEEP_GOING, watch.update(500, 0, true));
        assertEquals(Verdict.KEEP_GOING, watch.update(859, 0, true));
        assertEquals(Verdict.GIVE_UP_SPAWNER, watch.update(860, 0, true));
    }

    @Test
    public void wholeStepBudgetEndsEvenWithRodsComingIn() {
        RodsWatch capped = new RodsWatch(360, 720);
        capped.update(0, 0, false);
        assertEquals(Verdict.KEEP_GOING, capped.update(719, 2, false));
        assertEquals(Verdict.GIVE_UP_RODS, capped.update(720, 3, false));
        assertEquals(Verdict.GIVE_UP_RODS, capped.update(900, 4, true));
    }
}
