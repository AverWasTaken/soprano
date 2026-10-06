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
