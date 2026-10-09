package adris.altoclef.tasks.container;

import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a load that was left with less fuel than it needs: the visit has to keep the time the fuel runs out as the next due time
public class CollectFromFurnaceFuelTest {
    @Test
    public void fuelThatCoversTheInputHasNoDryPoint() {
        // 37 items: one coal lit (8) and four in the slot (32)
        assertEquals(Long.MAX_VALUE, CollectFromFurnaceTask.fuelTicks("furnace", 37, 0, 8, 32));
        // the item in hand is half done and needs half an item of fuel less
        assertEquals(Long.MAX_VALUE, CollectFromFurnaceTask.fuelTicks("furnace", 37, 0.5, 8, 28.5));
    }

    @Test
    public void fuelThatFallsShortRunsDryAtTheFuelsTime() {
        // four items of fire left: 200 ticks each in a furnace, 100 in a smoker or a blast furnace
        assertEquals(800, CollectFromFurnaceTask.fuelTicks("furnace", 37, 0, 4, 0));
        assertEquals(400, CollectFromFurnaceTask.fuelTicks("smoker", 37, 0, 4, 0));
        assertEquals(400, CollectFromFurnaceTask.fuelTicks("blast_furnace", 37, 0, 4, 0));
        // what is in the slot burns after what is lit
        assertEquals(2400, CollectFromFurnaceTask.fuelTicks("furnace", 37, 0, 4, 8));
    }

    @Test
    public void anEarlyVisitKeepsTheTimeTheFuelRunsOut() {
        // 37 iron loaded at tick 0 with 9 items of fuel: due at 1800, not at 7400
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(1, 2, 3), "OVERWORLD", "furnace", "raw_iron", 37, "iron_ingot", 0,
                FurnaceJobs.doneTick("furnace", 0, AsyncSmelting.cookable(9, 0, 0, 37)));
        assertEquals(1800, job.doneTick);
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(job));
        // a visit that comes early for some other reason (the planner passes by) at tick 1000: 32 left, 4 items of fire
        long untilDry = CollectFromFurnaceTask.fuelTicks("furnace", 32, 0, 4, 0);
        long remaining = FurnaceJobs.remainingTicks("furnace", 32, 0);
        FurnaceJobs.afterVisit(jobs, job, 32, Math.min(remaining, untilDry), 1000);
        // the timer is still the dry point, so the next trip is for the refuel and not minutes after the fire went out
        assertEquals(1800, job.doneTick);
        assertFalse(FurnaceJobs.anyDue(jobs, 1001, 200));
        assertTrue(FurnaceJobs.anyDue(jobs, 1800, 200));
        // without the cap the re-stamp assumed the fire kept going, minutes past the dry point
        assertTrue(1000 + remaining > 1800);
    }

    @Test
    public void aFireAboutToGoOutIsWaitedOutNotRevisited() {
        // lit with 10 s or less of fuel left, in a mode that stays: stand by
        assertTrue(CollectFromFurnaceTask.dryingSoon(true, Mode.NORMAL, 150, 200));
        assertTrue(CollectFromFurnaceTask.dryingSoon(true, Mode.WAIT_ALL, 200, 200));
        // plenty left, or covered (no dry point at all)
        assertFalse(CollectFromFurnaceTask.dryingSoon(true, Mode.NORMAL, 800, 200));
        assertFalse(CollectFromFurnaceTask.dryingSoon(true, Mode.NORMAL, Long.MAX_VALUE, 200));
        // already cold is the stalled path, not this one
        assertFalse(CollectFromFurnaceTask.dryingSoon(false, Mode.NORMAL, 0, 200));
        // leaving takes the input back out instead of standing there
        assertFalse(CollectFromFurnaceTask.dryingSoon(true, Mode.TAKE_ALL, 150, 200));
    }
}
