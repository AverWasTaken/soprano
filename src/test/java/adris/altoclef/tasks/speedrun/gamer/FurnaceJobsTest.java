package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FurnaceJobsTest {
    private static RunState.FurnaceJob job(int x, int count, long start, String kind) {
        return new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", kind, "raw_iron", count, "iron_ingot", start,
                FurnaceJobs.doneTick(kind, start, count));
    }

    @Test
    public void furnaceIsTenSecondsAnItemBlastFurnaceFive() {
        assertEquals(200, FurnaceJobs.ticksPerItem("furnace"));
        assertEquals(100, FurnaceJobs.ticksPerItem("blast_furnace"));
        assertEquals(100, FurnaceJobs.ticksPerItem("smoker"));
        assertEquals(1000 + 39 * 200L, FurnaceJobs.doneTick("furnace", 1000, 39));
        assertEquals(1000 + 12 * 100L, FurnaceJobs.doneTick("blast_furnace", 1000, 12));
    }

    @Test
    public void aNewJobForTheSameSpotReplacesTheOldOne() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        FurnaceJobs.record(jobs, job(1, 10, 0, "furnace"));
        FurnaceJobs.record(jobs, job(2, 5, 0, "furnace"));
        FurnaceJobs.record(jobs, job(1, 20, 500, "furnace"));
        assertEquals(2, jobs.size());
        assertEquals(25, FurnaceJobs.pending(jobs, "iron_ingot"));
    }

    @Test
    public void pendingOnlyCountsTheRightOutput() {
        List<RunState.FurnaceJob> jobs = List.of(job(1, 10, 0, "furnace"));
        assertEquals(10, FurnaceJobs.pending(jobs, "iron_ingot"));
        assertEquals(0, FurnaceJobs.pending(jobs, "gold_ingot"));
        assertEquals(0, FurnaceJobs.pending(List.of(), "iron_ingot"));
    }

    @Test
    public void busyMeansSomeJobStandsOnThatBlock() {
        RunState s = new RunState();
        s.furnaceJobs.add(job(7, 3, 0, "furnace"));
        assertTrue(FurnaceJobs.isBusy(s, new RunState.Pos(7, 64, 0)));
        assertTrue(FurnaceJobs.isBusy(s, 7, 64, 0));
        assertFalse(FurnaceJobs.isBusy(s, 8, 64, 0));
        assertFalse(FurnaceJobs.isBusy(new RunState(), 7, 64, 0));
    }

    @Test
    public void dueWithSlack() {
        List<RunState.FurnaceJob> jobs = List.of(job(1, 10, 0, "furnace"));
        assertFalse(FurnaceJobs.anyDue(jobs, 1000, 0));
        assertTrue(FurnaceJobs.anyDue(jobs, 2000, 0));
        assertTrue(FurnaceJobs.anyDue(jobs, 1900, 200));
        assertFalse(FurnaceJobs.anyDue(List.of(), 5000, 200));
    }

    @Test
    public void soonestAndTicksLeft() {
        RunState.FurnaceJob slow = job(1, 30, 0, "furnace");
        RunState.FurnaceJob quick = job(2, 5, 0, "blast_furnace");
        List<RunState.FurnaceJob> jobs = List.of(slow, quick);
        assertSame(quick, FurnaceJobs.soonest(jobs));
        assertNull(FurnaceJobs.soonest(List.of()));
        assertEquals(6000 - 100, FurnaceJobs.ticksLeft(jobs, 100));
        assertEquals(0, FurnaceJobs.ticksLeft(jobs, 99999));
    }

    @Test
    public void staleJobsAreDropped() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(job(1, 10, 0, "furnace"), job(2, 10, 30_000, "furnace")));
        List<RunState.FurnaceJob> gone = FurnaceJobs.dropStale(jobs, 40_000, 600);
        assertEquals(1, gone.size());
        assertEquals(1, jobs.size());
        assertEquals(2, jobs.get(0).pos.x);
    }

    private static RunState.FurnaceJob meat(int x, int count, long start) {
        RunState.FurnaceJob j = new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", "smoker", "mutton", count,
                "cooked_mutton", start, FurnaceJobs.doneTick("smoker", start, count));
        j.unitsEach = 6;
        return j;
    }

    @Test
    public void sevenMuttonInASmokerIsThirtyFiveSeconds() {
        RunState.FurnaceJob j = meat(1, 7, 1000);
        assertEquals(1000 + 7 * 100L, j.doneTick);
        assertEquals(35, (j.doneTick - j.startTick) / 20);
    }

    @Test
    public void cookingFoodIsPendingNutritionAndIronIsNot() {
        List<RunState.FurnaceJob> jobs = List.of(meat(1, 7, 0), job(2, 10, 0, "furnace"));
        assertEquals(42, FurnaceJobs.pendingUnits(jobs));
        assertEquals(0, FurnaceJobs.pendingUnits(List.of()));
        assertEquals(0, FurnaceJobs.pendingUnits(List.of(job(2, 10, 0, "furnace"))));
    }

    @Test
    public void aVisitShrinksThePendingFoodWithTheInputLeft() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = meat(1, 7, 0);
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 3, 400);
        assertEquals(18, FurnaceJobs.pendingUnits(jobs));
        assertEquals(400 + 300L, j.doneTick);
        FurnaceJobs.afterVisit(jobs, j, 0, 800);
        assertEquals(0, FurnaceJobs.pendingUnits(jobs));
    }

    @Test
    public void onlyOurOwnEmptySmokersAndFurnacesComeBack() {
        RunState s = new RunState();
        RunState.Pos spot = new RunState.Pos(4, 64, 4);
        s.placedSmokers.add(spot);
        assertTrue(FurnaceJobs.mayTakeBack(s, "smoker", spot, 0, false));
        // a smoker is not in the furnace list and the other way round
        assertFalse(FurnaceJobs.mayTakeBack(s, "furnace", spot, 0, false));
        s.placedFurnaces.add(new RunState.Pos(9, 64, 9));
        assertTrue(FurnaceJobs.mayTakeBack(s, "furnace", new RunState.Pos(9, 64, 9), 0, false));
        // a village's smoker (never recorded) and a blast furnace of any kind stay where they are
        assertFalse(FurnaceJobs.mayTakeBack(s, "smoker", new RunState.Pos(5, 64, 5), 0, false));
        assertFalse(FurnaceJobs.mayTakeBack(s, "blast_furnace", spot, 0, false));
        // a spare in the bag means this one is not worth a trip
        assertTrue(!FurnaceJobs.mayTakeBack(s, "smoker", spot, 0, true));
    }

    @Test
    public void neverBreakASmokerThatStillHasFood() {
        RunState s = new RunState();
        RunState.Pos spot = new RunState.Pos(4, 64, 0);
        s.placedSmokers.add(spot);
        // the collect trip left input cooking in there
        assertFalse(FurnaceJobs.mayTakeBack(s, "smoker", spot, 3, false));
        // or another job still points at the spot
        s.furnaceJobs.add(meat(4, 3, 0));
        assertFalse(FurnaceJobs.mayTakeBack(s, "smoker", spot, 0, false));
        s.furnaceJobs.clear();
        assertTrue(FurnaceJobs.mayTakeBack(s, "smoker", spot, 0, false));
    }

    @Test
    public void remainingTimeCountsTheItemsAndHowFarTheCurrentOneIs() {
        // 4 items in a furnace, the one cooking is halfway: 3.5 items left at 10 s
        assertEquals(700, FurnaceJobs.remainingTicks("furnace", 4, 0.5));
        // a smoker is twice as quick
        assertEquals(700, FurnaceJobs.remainingTicks("smoker", 7, 0.0));
        assertEquals(350, FurnaceJobs.remainingTicks("blast_furnace", 4, 0.5));
        // a fresh item has not started, an arrow past full or a negative one does not make time up
        assertEquals(800, FurnaceJobs.remainingTicks("furnace", 4, 0.0));
        assertEquals(600, FurnaceJobs.remainingTicks("furnace", 4, 1.7));
        assertEquals(800, FurnaceJobs.remainingTicks("furnace", 4, -3));
    }

    @Test
    public void leavingMoreThanTenSecondsOfWorkMeansComingBackLater() {
        // the rule CollectFromFurnaceTask applies on arrival: 2 items in a furnace is 20 s, not nearly done
        double wait = new adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig().furnaceWaitSeconds * 20;
        assertTrue(FurnaceJobs.remainingTicks("furnace", 2, 0.0) > wait);
        // one item halfway is, and a smoker is a lot quicker: 3 items is 15 s, 1 is 5
        assertTrue(FurnaceJobs.remainingTicks("furnace", 1, 0.5) <= wait);
        assertTrue(FurnaceJobs.remainingTicks("smoker", 3, 0.0) > wait);
        assertTrue(FurnaceJobs.remainingTicks("smoker", 1, 0.0) <= wait);
    }

    @Test
    public void aVisitRestampsTheTimerFromWhatIsReallyLeft() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        // we thought it was done at 800 (the chunk was unloaded for most of that), the furnace says 4 items and one just started
        RunState.FurnaceJob j = job(1, 10, 0, "furnace");
        jobs.add(j);
        long now = 5000;
        FurnaceJobs.afterVisit(jobs, j, 4, FurnaceJobs.remainingTicks("furnace", 4, 0.25), now);
        assertEquals(4, j.count);
        assertEquals(now, j.startTick);
        assertEquals(now + 750, j.doneTick);
        // not due while it cooks, due once the estimate runs out
        assertFalse(FurnaceJobs.anyDue(jobs, now + 100, 200));
        assertTrue(FurnaceJobs.anyDue(jobs, now + 750, 0));
        // an honest estimate never says "now": a zero or negative remainder still gives the job a tick
        FurnaceJobs.afterVisit(jobs, j, 1, 0, now);
        assertEquals(now + 1, j.doneTick);
    }

    @Test
    public void aVisitRewritesTheJobOrEndsIt() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        RunState.FurnaceJob j = job(1, 10, 0, "furnace");
        jobs.add(j);
        FurnaceJobs.afterVisit(jobs, j, 4, 1000);
        assertEquals(4, j.count);
        assertEquals(1000 + 800L, j.doneTick);
        FurnaceJobs.afterVisit(jobs, j, 0, 2000);
        assertTrue(jobs.isEmpty());
    }
}
