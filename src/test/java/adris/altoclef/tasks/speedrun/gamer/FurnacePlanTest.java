package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Call;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Leaving;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Mode;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Moment;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Plan;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Verdict;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan.Why;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the trip layer of FurnacePlan: one call per job from the job's memory, the clock it runs on, and the cook / climb / early load
// timers that used to live in their own classes. the visit layer (at the station) is FurnacePlanVisitTest
public class FurnacePlanTest {
    // a furnace job done at `done`, loaded at 0 and never visited
    private static RunState.FurnaceJob furnace(long done) {
        return new RunState.FurnaceJob(new RunState.Pos(0, 64, 0), "OVERWORLD", "furnace", "raw_iron", 10, "iron_ingot", 0, done);
    }

    private static RunState.FurnaceJob smoker(long done) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(5, 64, 0), "OVERWORLD", "smoker", "mutton", 8, "cooked_mutton", 0, done);
        job.unitsEach = 6;
        return job;
    }

    // fillerLeft, atBoundary, interrupt, stockUp, mayStandBy
    private static final Moment MID_NEED = new Moment(true, false, false, false, true);
    private static final Moment BOUNDARY = new Moment(true, true, false, false, true);
    private static final Moment NOTHING_TO_DO = new Moment(false, true, false, false, true);

    private static Verdict one(Plan plan) {
        assertEquals(1, plan.verdicts().size());
        return plan.verdicts().get(0);
    }

    private static Plan plan(RunState.FurnaceJob job, Moment m, long now) {
        return FurnacePlan.plan(List.of(job), m, now, null);
    }

    // ---- due

    @Test
    public void anUnvisitedJobIsDueTenSecondsEarlyAndAVisitedOneWhenItsDone() {
        RunState.FurnaceJob guess = furnace(1000);
        assertFalse(FurnacePlan.due(guess, 1000 - FurnacePlan.NEARLY_TICKS - 1));
        assertTrue(FurnacePlan.due(guess, 1000 - FurnacePlan.NEARLY_TICKS));
        assertTrue(FurnacePlan.due(guess, 1000));
        // the timer of a visited job is the furnace's own, so going back inside the slack of it is how the bot ping-ponged
        RunState.FurnaceJob honest = furnace(1000);
        honest.visited = true;
        assertFalse(FurnacePlan.due(honest, 999));
        assertTrue(FurnacePlan.due(honest, 1000));
        assertFalse(FurnacePlan.anyDue(List.of(), 5000));
        assertTrue(FurnacePlan.anyDue(List.of(honest, guess), 900));
    }

    @Test
    public void aJobThatIsNotDueIsLeftAlone() {
        Verdict v = one(plan(furnace(5000), BOUNDARY, 0));
        assertEquals(Call.LEAVE, v.call());
        assertEquals(Why.COOKING, v.why());
        assertNull(plan(furnace(5000), BOUNDARY, 0).pick());
    }

    // ---- when a due job is fetched

    @Test
    public void aDueJobWaitsForTheNeedToEndUnlessSomethingIsWorthCuttingShort() {
        RunState.FurnaceJob job = furnace(1000);
        Verdict mid = one(plan(job, MID_NEED, 2000));
        assertEquals(Call.LEAVE, mid.call());
        assertEquals(Why.MID_NEED, mid.why());
        Verdict boundary = one(plan(furnace(1000), BOUNDARY, 2000));
        assertEquals(Call.COLLECT_NOW, boundary.call());
        assertEquals(Why.BOUNDARY, boundary.why());
        assertEquals(Mode.NORMAL, boundary.mode());
        // the early pick wants its ingots, or the food is stuck behind its own smoker
        assertEquals(Why.INTERRUPT, one(plan(furnace(1000), new Moment(true, false, true, false, true), 2000)).why());
        // nothing waits on a stock-up, so a due job cuts it short
        assertEquals(Why.STOCK_UP, one(plan(furnace(1000), new Moment(true, false, false, true, true), 2000)).why());
        // an interrupt for a job that is not done cooking yet changes nothing
        assertEquals(Call.LEAVE, one(plan(furnace(9000), new Moment(true, false, true, true, true), 0)).call());
    }

    @Test
    public void nearlyDoneCountsAsDoneAtTheBoundary() {
        long edge = 1000 - FurnacePlan.NEARLY_TICKS;
        assertEquals(Call.COLLECT_NOW, one(plan(furnace(1000), BOUNDARY, edge)).call());
        assertEquals(Call.LEAVE, one(plan(furnace(1000), BOUNDARY, edge - 1)).call());
    }

    @Test
    public void nothingToDoMeansStandingByUntilItIsDone() {
        Verdict wait = one(plan(furnace(5000), NOTHING_TO_DO, 0));
        assertEquals(Call.STAND_BY, wait.call());
        assertEquals(Why.IDLE_WAIT, wait.why());
        assertEquals(Mode.WAIT_ALL, wait.mode());
        Verdict due = one(plan(furnace(5000), NOTHING_TO_DO, 5000));
        assertEquals(Call.COLLECT_NOW, due.call());
        assertEquals(Why.IDLE_DUE, due.why());
        // and the filler coming back is what makes the same job wait for nobody
        assertEquals(Call.LEAVE, one(plan(furnace(5000), MID_NEED, 0)).call());
    }

    @Test
    public void aVisitedFurnaceIsNotRevisitedBeforeItsNewTimer() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(furnace(300)));
        RunState.FurnaceJob j = jobs.get(0);
        long now = 5000;
        // we got there with 3 items and 11 s of input left: the old slack would call this due after a second and turn us around
        FurnaceJobs.afterVisit(jobs, j, 3, FurnacePlan.NEARLY_TICKS + 20, now);
        assertEquals(Call.LEAVE, FurnacePlan.plan(jobs, BOUNDARY, now, null).verdicts().get(0).call());
        assertEquals(Call.LEAVE, FurnacePlan.plan(jobs, BOUNDARY, now + 20, null).verdicts().get(0).call());
        assertEquals(Call.LEAVE, FurnacePlan.plan(jobs, new Moment(true, true, true, false, true), now + FurnacePlan.NEARLY_TICKS + 19, null)
                .verdicts().get(0).call());
        assertEquals(Call.COLLECT_NOW, FurnacePlan.plan(jobs, BOUNDARY, now + FurnacePlan.NEARLY_TICKS + 20, null).verdicts().get(0).call());
    }

    @Test
    public void thePickIsTheOneThatIsReadyFirstAmongThoseThatWantATrip() {
        RunState.FurnaceJob late = furnace(3000);
        RunState.FurnaceJob soon = furnace(1000);
        soon.pos = new RunState.Pos(9, 64, 9);
        RunState.FurnaceJob notDue = furnace(90000);
        Plan p = FurnacePlan.plan(List.of(notDue, late, soon), BOUNDARY, 5000, null);
        assertSame(soon, p.pick().job());
        // a trip already under way for one of them means no new pick
        assertNull(FurnacePlan.plan(List.of(notDue, late, soon), BOUNDARY, 5000, soon).pick());
    }

    // ---- standing by a quick smoker

    @Test
    public void aSmokerThatIsOutInUnderTwentySecondsIsStoodByAndAFurnaceIsNot() {
        Verdict s = one(plan(smoker(300), MID_NEED, 0));
        assertEquals(Call.STAND_BY, s.call());
        assertEquals(Why.QUICK, s.why());
        assertEquals(Mode.WAIT_ALL, s.mode());
        assertTrue(plan(smoker(300), MID_NEED, 0).standingBy());
        // the furnace is 10 s an item and keeps its filler
        Plan f = plan(furnace(300), MID_NEED, 0);
        assertEquals(Call.LEAVE, one(f).call());
        assertFalse(f.standingBy());
        // GATHER's work is right there anyway
        assertEquals(Call.LEAVE, one(plan(smoker(300), new Moment(true, false, false, false, false), 0)).call());
    }

    @Test
    public void aBatchWithTwentySecondsOrLessLeftIsQuickAndOneTickMoreIsNot() {
        assertEquals(Why.QUICK, one(plan(smoker(FurnacePlan.STAND_BY_MAX_TICKS), MID_NEED, 0)).why());
        Verdict slow = one(plan(smoker(FurnacePlan.STAND_BY_MAX_TICKS + 1), MID_NEED, 0));
        assertEquals(Call.LEAVE, slow.call());
        // not due and too long a batch to stand at: the log says so instead of "not due yet"
        assertEquals(Why.TOO_LONG, slow.why());
    }

    // a stack of 64 is five minutes: worked like a furnace, until it is down to the last 20 s like any small batch
    @Test
    public void aBigBatchIsLeftUntilItIsDownToTwentySecondsThenStoodBy() {
        RunState.FurnaceJob big = smoker(6400);
        Verdict first = one(plan(big, MID_NEED, 0));
        assertEquals(Call.LEAVE, first.call());
        assertEquals(Why.TOO_LONG, first.why());
        long mark = 6400 - FurnacePlan.STAND_BY_MAX_TICKS;
        assertEquals(Why.TOO_LONG, one(plan(big, MID_NEED, mark - 1)).why());
        assertEquals(Why.QUICK, one(plan(big, MID_NEED, mark)).why());
        // the budget is from the look that started it: 20 s to done and 10 s of the patience
        assertEquals(Why.QUICK, one(plan(big, MID_NEED, mark + FurnacePlan.STAND_BY_CAP_TICKS)).why());
        assertNotEquals(Why.QUICK, one(plan(big, MID_NEED, mark + FurnacePlan.STAND_BY_CAP_TICKS + 1)).why());
        // and once it ended, the time still being short does not start another one
        assertNotEquals(Why.QUICK, one(plan(big, MID_NEED, mark + FurnacePlan.STAND_BY_CAP_TICKS + 2)).why());
    }

    // a look that skipped the stand-by rules (GATHER, mayStandBy off) does not use up the chance of one later
    @Test
    public void aLookWithoutStandByDoesNotDecideTheLaterOnes() {
        RunState.FurnaceJob job = smoker(1000);
        assertEquals(Call.LEAVE, one(plan(job, new Moment(true, false, false, false, false), 700)).call());
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, 700)).why());
    }

    @Test
    public void aSmokerThatWasQuickAtFirstSightStaysQuickUntilItsBudgetEnds() {
        RunState.FurnaceJob job = smoker(300);
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, 0)).why());
        // done at 300, and the patience past that is cut off by the cap: 30 s from first sight, not 45
        long until = FurnacePlan.STAND_BY_CAP_TICKS;
        assertTrue(until < 300 + FurnacePlan.PATIENCE_TICKS);
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, 300)).why());
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, until)).why());
        // a smoker that never finishes does not hold the run
        assertNotEquals(Why.QUICK, one(plan(job, MID_NEED, until + 1)).why());
    }

    @Test
    public void theBudgetIsNotRestartedByARestampOrByTheTimeComingBack() {
        RunState.FurnaceJob job = smoker(FurnacePlan.STAND_BY_MAX_TICKS);
        plan(job, MID_NEED, 0);
        long past = FurnacePlan.STAND_BY_CAP_TICKS + 1;
        assertNotEquals(Why.QUICK, one(plan(job, MID_NEED, past)).why());
        // a visit re-stamped it (the job object is the same one) and it is again under 20 s from done
        job.doneTick = past + 300;
        job.visited = true;
        assertNotEquals("a smoker that keeps coming up short cannot restart the clock", Why.QUICK, one(plan(job, MID_NEED, past + 1)).why());
        // a new load at the same spot is a new job object, a new batch, a fresh budget
        RunState.FurnaceJob fresh = smoker(past + 300);
        assertEquals(Why.QUICK, one(plan(fresh, MID_NEED, past + 1)).why());
    }

    @Test
    public void aJobAlreadyPastDueGetsItsPatienceFromNowNotFromThen() {
        RunState.FurnaceJob job = smoker(1000);
        // first seen 500 ticks late: the patience runs from here
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, 1500)).why());
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, 1500 + FurnacePlan.PATIENCE_TICKS)).why());
        assertNotEquals(Why.QUICK, one(plan(job, MID_NEED, 1500 + FurnacePlan.PATIENCE_TICKS + 1)).why());
    }

    // meat left cold in a station is a pickup, not a cook: standing by it was a wait for "0 s" that took the raw meat back out
    @Test
    public void meatLeftColdInASmokerIsNotStoodBy() {
        RunState.FurnaceJob cold = smoker(0);
        cold.stranded = true;
        Verdict v = one(plan(cold, MID_NEED, 0));
        assertNotEquals(Why.QUICK, v.why());
        // it is due the moment it is made, so the next boundary goes and gets it
        assertEquals(Call.COLLECT_NOW, one(plan(cold, BOUNDARY, 0)).call());
        // lit, it is a smoker like any other
        RunState.FurnaceJob lit = smoker(300);
        assertEquals(Why.QUICK, one(plan(lit, MID_NEED, 0)).why());
    }

    @Test
    public void aQuickSmokerOutranksADueFurnaceInThePick() {
        RunState.FurnaceJob iron = furnace(100);
        RunState.FurnaceJob meat = smoker(400);
        Plan p = FurnacePlan.plan(List.of(iron, meat), BOUNDARY, 200, null);
        assertSame(meat, p.pick().job());
        assertEquals(Why.QUICK, p.pick().why());
        assertTrue(p.standingBy());
        // once the smoker is out the furnace alone gets its collect back
        Plan alone = FurnacePlan.plan(List.of(iron), BOUNDARY, 200, null);
        assertSame(iron, alone.pick().job());
        assertFalse(alone.standingBy());
    }

    // ---- stuck

    @Test
    public void twoWaitsThatSawNothingComeOutMeanTakingTheInputBack() {
        RunState.FurnaceJob job = furnace(1000);
        job.stalls = FurnacePlan.STALL_LIMIT - 1;
        assertEquals(Call.COLLECT_NOW, one(plan(job, BOUNDARY, 2000)).call());
        job.stalls = FurnacePlan.STALL_LIMIT;
        Verdict v = one(plan(job, BOUNDARY, 2000));
        assertEquals(Call.TAKE_ALL, v.call());
        assertEquals(Why.STUCK, v.why());
        assertEquals(Mode.TAKE_ALL, v.mode());
        // standing by it or waiting for nothing is the same: it is not cooking
        RunState.FurnaceJob idle = furnace(5000);
        idle.stalls = FurnacePlan.STALL_LIMIT;
        assertEquals(Call.TAKE_ALL, one(plan(idle, NOTHING_TO_DO, 0)).call());
        RunState.FurnaceJob quick = smoker(300);
        quick.stalls = FurnacePlan.STALL_LIMIT;
        assertEquals(Call.TAKE_ALL, one(plan(quick, MID_NEED, 0)).call());
        // a stuck job that nothing wants a trip for yet is still just left alone
        RunState.FurnaceJob later = furnace(90000);
        later.stalls = FurnacePlan.STALL_LIMIT;
        assertEquals(Call.LEAVE, one(plan(later, BOUNDARY, 0)).call());
    }

    // ---- leaving

    @Test
    public void leavingTheDimensionTakesItAllAndWaitsOnlyTheUsualNearly() {
        RunState.FurnaceJob job = furnace(9000);
        Verdict v = FurnacePlan.leaving(job, Leaving.DIMENSION, 0);
        assertEquals(Call.TAKE_ALL, v.call());
        assertEquals(Why.LEAVING_DIMENSION, v.why());
        assertEquals(Mode.TAKE_ALL, v.mode());
        assertEquals(FurnacePlan.NEARLY_TICKS, v.nearly());
    }

    @Test
    public void leavingTheMineWaitsAsLongAsTheClimbCostsAnyway() {
        RunState.FurnaceJob job = furnace(9000);
        assertEquals(Why.LEAVING_AREA, FurnacePlan.leaving(job, Leaving.AREA, 30).why());
        assertEquals(FurnacePlan.NEARLY_TICKS, FurnacePlan.leaving(job, Leaving.AREA, 1).nearly());
        assertEquals(30 * FurnacePlan.LEAVE_TICKS_PER_DEPTH, FurnacePlan.leaving(job, Leaving.AREA, 30).nearly());
        assertEquals(FurnacePlan.PATIENCE_TICKS, FurnacePlan.leaving(job, Leaving.AREA, 500).nearly());
        // a bit deeper is a bit longer, and it never goes under ten seconds or over the patience
        assertTrue(FurnacePlan.leaveWindow(25) > FurnacePlan.leaveWindow(15));
        assertEquals(FurnacePlan.NEARLY_TICKS, FurnacePlan.leaveWindow(0));
    }

    // ---- no flapping

    // the loop the phase runs: plan, and if the plan picked a trip it starts and is the active one until it ends
    @Test
    public void aTripThatStartedKeepsItsCallWhateverTheFillerDoes() {
        RunState.FurnaceJob job = furnace(6000);
        List<String> lines = new ArrayList<>();
        RunState.FurnaceJob active = null;
        for (int tick = 0; tick < 40; tick++) {
            // the runnable list empties and refills every other tick, which is all a noisy plan has to do to flap
            Moment m = new Moment(tick % 2 == 0 ? false : true, tick % 2 == 1, false, false, true);
            Plan p = FurnacePlan.plan(List.of(job), m, tick, active);
            lines.addAll(p.changes());
            if (active == null && p.pick() != null) {
                active = p.pick().job();
            }
        }
        // one call for the whole wait, not one per flicker
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0).startsWith("furnace: furnace at 0, 64, 0: STAND_BY"));
    }

    @Test
    public void aQuickStandByHoldsThroughFlickersAndEndsOnlyWithItsBudget() {
        RunState.FurnaceJob job = smoker(FurnacePlan.STAND_BY_MAX_TICKS);
        List<String> lines = new ArrayList<>();
        RunState.FurnaceJob active = null;
        for (long tick = 0; tick <= FurnacePlan.STAND_BY_CAP_TICKS; tick += 10) {
            Moment m = new Moment(tick % 20 == 0, tick % 30 == 0, tick % 40 == 0, false, true);
            Plan p = FurnacePlan.plan(List.of(job), m, tick, active);
            lines.addAll(p.changes());
            assertTrue("tick " + tick, p.standingBy());
            if (active == null && p.pick() != null) {
                active = p.pick().job();
            }
        }
        assertEquals(lines.toString(), 1, lines.size());
        // past the budget the trip under way is no longer a stand-by, it logs the change once
        Plan over = FurnacePlan.plan(List.of(job), MID_NEED, FurnacePlan.STAND_BY_CAP_TICKS + 1, active);
        assertFalse(over.standingBy());
        assertEquals(1, over.changes().size());
    }

    // each boundary is crossed once and stays crossed as the clock runs on: no flipping back for the same job
    @Test
    public void everyBoundaryIsCrossedOnceAsTheClockRuns() {
        RunState.FurnaceJob job = smoker(5000);
        job.visited = true;
        Call last = null;
        int changes = 0;
        for (long tick = 4700; tick <= 7000; tick++) {
            Call now = one(FurnacePlan.plan(List.of(job), BOUNDARY, tick, null)).call();
            if (now != last) {
                changes++;
                last = now;
            }
        }
        // under 20 s to go it stands by, the budget runs out at the cap and it is due, and that is all
        assertEquals(2, changes);
        assertEquals(Call.COLLECT_NOW, last);
    }

    // ---- the soft hold: LEAVE -> an idle STAND_BY follows a flickering filler list

    @Test
    public void aFlickeringFillerChangesNothing() {
        RunState.FurnaceJob job = furnace(90000);
        List<String> lines = new ArrayList<>();
        for (long tick = 0; tick < 400; tick++) {
            Moment m = new Moment(tick % 2 == 0, tick % 2 == 1, false, false, true);
            Plan p = FurnacePlan.plan(List.of(job), m, tick, null);
            lines.addAll(p.changes());
            assertNull("tick " + tick, tick == 0 ? null : p.pick());
        }
        // the first call and that is all: the idle answer never lasted long enough to be believed
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("LEAVE"));
    }

    @Test
    public void anIdleWaitHasToBeTheAnswerForTheWholeHoldBeforeALeavingJobChanges() {
        RunState.FurnaceJob job = furnace(90000);
        assertEquals(Call.LEAVE, one(FurnacePlan.plan(List.of(job), MID_NEED, 100, null)).call());
        assertEquals(Call.LEAVE, one(FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 101, null)).call());
        assertEquals(Call.LEAVE, one(FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 101 + FurnacePlan.SOFT_HOLD_TICKS - 1, null)).call());
        Plan through = FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 101 + FurnacePlan.SOFT_HOLD_TICKS, null);
        assertEquals(Call.STAND_BY, one(through).call());
        assertSame(job, through.pick().job());
        // the way back is not held: the filler has work again, so the wait is off at once
        assertEquals(Call.LEAVE, one(FurnacePlan.plan(List.of(job), MID_NEED, 101 + FurnacePlan.SOFT_HOLD_TICKS + 1, null)).call());
    }

    // one tick of nothing runnable in the middle is not "held": the hold counts again from the next idle answer
    @Test
    public void aBlipOfWorkInTheMiddleStartsTheHoldOver() {
        RunState.FurnaceJob job = furnace(90000);
        FurnacePlan.plan(List.of(job), MID_NEED, 0, null);
        FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 1, null);
        FurnacePlan.plan(List.of(job), MID_NEED, 30, null);
        FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 31, null);
        assertEquals(Call.LEAVE, one(FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 31 + FurnacePlan.SOFT_HOLD_TICKS - 1, null)).call());
        assertEquals(Call.STAND_BY, one(FurnacePlan.plan(List.of(job), NOTHING_TO_DO, 31 + FurnacePlan.SOFT_HOLD_TICKS, null)).call());
    }

    // another job's trip ends inside the window: the one that only had a flicker of "nothing to do" is not the pick
    @Test
    public void aFlickerWhileAnotherJobsTripRunsDoesNotStartAWaitWhenThatTripEnds() {
        RunState.FurnaceJob a = furnace(100);
        RunState.FurnaceJob b = furnace(95000);
        b.pos = new RunState.Pos(3, 62, -4);
        List<RunState.FurnaceJob> jobs = List.of(a, b);
        Plan start = FurnacePlan.plan(jobs, BOUNDARY, 0, null);
        assertSame(a, start.pick().job());
        // a's trip runs, and the filler list is empty for one tick
        Plan flicker = FurnacePlan.plan(jobs, NOTHING_TO_DO, 10, a);
        assertNull(flicker.pick());
        assertEquals(Call.LEAVE, flicker.verdicts().get(1).call());
        // a's trip ends and the filler is back: nothing starts
        Plan ended = FurnacePlan.plan(jobs, MID_NEED, 11, null);
        assertNull(ended.pick());
        assertEquals(Call.LEAVE, ended.verdicts().get(1).call());
    }

    @Test
    public void aFirstCallADueJobAQuickSmokerAndALeavingOneNeverWait() {
        // the first call of a job is not a change
        assertEquals(Call.STAND_BY, one(plan(furnace(90000), NOTHING_TO_DO, 0)).call());
        // due inside the hold: straight to collecting, from either soft call
        RunState.FurnaceJob waiting = furnace(300);
        assertEquals(Call.STAND_BY, one(FurnacePlan.plan(List.of(waiting), NOTHING_TO_DO, 0, null)).call());
        assertEquals(Call.COLLECT_NOW, one(FurnacePlan.plan(List.of(waiting), NOTHING_TO_DO, 100, null)).call());
        RunState.FurnaceJob leaving = furnace(300);
        assertEquals(Call.LEAVE, one(FurnacePlan.plan(List.of(leaving), MID_NEED, 0, null)).call());
        assertEquals(Call.COLLECT_NOW, one(FurnacePlan.plan(List.of(leaving), BOUNDARY, 100, null)).call());
        // the phase starts standing by smokers: the quick call does not wait out the hold of the idle one
        RunState.FurnaceJob meat = smoker(300);
        assertEquals(Why.IDLE_WAIT, one(FurnacePlan.plan(List.of(meat), new Moment(false, true, false, false, false), 0, null)).why());
        assertEquals(Why.QUICK, one(FurnacePlan.plan(List.of(meat), NOTHING_TO_DO, 5, null)).why());
        // stuck is not soft either
        RunState.FurnaceJob stuck = furnace(90000);
        assertEquals(Call.STAND_BY, one(FurnacePlan.plan(List.of(stuck), NOTHING_TO_DO, 0, null)).call());
        stuck.stalls = FurnacePlan.STALL_LIMIT;
        assertEquals(Call.TAKE_ALL, one(FurnacePlan.plan(List.of(stuck), NOTHING_TO_DO, 5, null)).call());
    }

    // a job with no trip of its own has no active trip to hold it, so it is the hold above that keeps it quiet while another job's trip runs
    @Test
    public void aJobWithoutATripDoesNotFlapWhileAnotherJobsTripRuns() {
        // (b's first call lands on a tick with nothing runnable, which is immediate, and the next tick takes it back)
        RunState.FurnaceJob a = furnace(90000);
        RunState.FurnaceJob b = furnace(95000);
        b.pos = new RunState.Pos(3, 62, -4);
        List<RunState.FurnaceJob> jobs = List.of(a, b);
        List<String> lines = new ArrayList<>();
        RunState.FurnaceJob active = null;
        for (long tick = 0; tick < 400; tick++) {
            Moment m = new Moment(tick % 2 == 1, tick % 2 == 0, false, false, true);
            Plan p = FurnacePlan.plan(jobs, m, tick, active);
            lines.addAll(p.changes());
            if (active == null && p.pick() != null) {
                active = p.pick().job();
            }
        }
        long forA = lines.stream().filter(l -> l.contains("at 0, 64, 0")).count();
        long forB = lines.stream().filter(l -> l.contains("3, 62, -4")).count();
        assertEquals(lines.toString(), 1, forA);
        assertEquals(lines.toString(), 2, forB);
    }

    // ---- the log

    @Test
    public void oneLinePerChangeOfCallPerJob() {
        RunState.FurnaceJob a = furnace(5000);
        RunState.FurnaceJob b = furnace(9000);
        b.pos = new RunState.Pos(3, 62, -4);
        List<RunState.FurnaceJob> jobs = List.of(a, b);
        Plan first = FurnacePlan.plan(jobs, MID_NEED, 0, null);
        assertEquals(2, first.changes().size());
        assertTrue(first.changes().get(0).startsWith("furnace: furnace at 0, 64, 0: LEAVE ("));
        assertTrue(first.changes().get(1).contains("3, 62, -4"));
        // the same call again, even for another reason, is not news
        assertTrue(FurnacePlan.plan(jobs, MID_NEED, 1, null).changes().isEmpty());
        assertTrue(FurnacePlan.plan(jobs, MID_NEED, 4900, null).changes().isEmpty());
        // a new call is, for that job only
        Plan due = FurnacePlan.plan(jobs, BOUNDARY, 5000, null);
        assertEquals(1, due.changes().size());
        assertTrue(due.changes().get(0).contains("COLLECT_NOW"));
        // and a call that comes back later is announced again
        FurnaceJobs.afterVisit(new ArrayList<>(jobs), a, 3, 100000, 5100);
        Plan again = FurnacePlan.plan(jobs, BOUNDARY, 5101, null);
        assertEquals(1, again.changes().size());
        assertTrue(again.changes().get(0).contains("LEAVE"));
    }

    @Test
    public void theLineSaysWhatItIsAndHowLongItHas() {
        RunState.FurnaceJob job = furnace(2000);
        String waiting = FurnacePlan.say(job, Call.STAND_BY, "waiting", 0);
        assertEquals("furnace: furnace at 0, 64, 0: STAND_BY (waiting; 10 raw_iron, ~100 s to go)", waiting);
        String late = FurnacePlan.say(job, Call.COLLECT_NOW, "due", 2400);
        assertTrue(late, late.endsWith("due 20 s ago)"));
    }

    // the visit says what it does in the same vocabulary, and the two layers share one "last call" so there is one line per change
    @Test
    public void theVisitAndTheTripShareOneLastCall() {
        RunState.FurnaceJob job = furnace(1000);
        assertNotNull(FurnacePlan.say(job, Call.COLLECT_NOW, "due", 1000));
        assertNull(FurnacePlan.say(job, Call.COLLECT_NOW, "due", 1001));
        assertNotNull(FurnacePlan.say(job, Call.STAND_BY, "nearly done", 1002));
        assertNull(FurnacePlan.say(job, Call.STAND_BY, "nearly done", 1003));
        assertNotNull(FurnacePlan.say(job, Call.DONE, "empty", 1004));
    }

    @Test
    public void aJobThatIsOverWithoutAVisitSaysWhy() {
        RunState.FurnaceJob job = furnace(1000);
        String stale = FurnacePlan.done(job, Why.STALE, 5000);
        assertTrue(stale, stale.contains("DONE") && stale.contains(Why.STALE.text));
        String gone = FurnacePlan.done(furnace(1000), Why.GONE, 5000);
        assertTrue(gone, gone.contains(Why.GONE.text));
    }

    // ---- progress credit and the cap

    @Test
    public void waitingByAFurnaceOnlyCountsUntilItsDuePlusThePatience() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>();
        // 5 mutton in a smoker from tick 1000: due at 1500
        RunState.FurnaceJob meat = smoker(1500);
        jobs.add(meat);
        assertTrue(FurnacePlan.waitIsHonest(jobs, 1200));
        assertTrue(FurnacePlan.waitIsHonest(jobs, 1500 + FurnacePlan.PATIENCE_TICKS));
        assertFalse(FurnacePlan.waitIsHonest(jobs, 1500 + FurnacePlan.PATIENCE_TICKS + 1));
        // another job that is still cooking keeps the wait honest
        jobs.add(furnace(4000));
        assertTrue(FurnacePlan.waitIsHonest(jobs, 3000));
        assertFalse(FurnacePlan.waitIsHonest(new ArrayList<>(), 0));
    }

    // the stall timer hears about a wait only while the bot stands at the station for a job that can still finish: a walk that got stuck
    // on the way there, or a wait on a job that never finishes (while another job is still cooking), must be allowed to look stuck
    @Test
    public void waitingCreditsTheStallTimerOnlyAtTheStationAndOnlyForTheTripsOwnJob() {
        RunState.FurnaceJob job = furnace(1000);
        assertTrue(FurnacePlan.creditsWait(true, job, 0));
        assertTrue(FurnacePlan.creditsWait(true, job, 1000 + FurnacePlan.PATIENCE_TICKS));
        assertFalse(FurnacePlan.creditsWait(true, job, 1000 + FurnacePlan.PATIENCE_TICKS + 1));
        assertFalse("still walking there", FurnacePlan.creditsWait(false, job, 0));
        assertFalse("no trip", FurnacePlan.creditsWait(true, null, 0));
        // another job still cooking says nothing about this one: waitIsHonest over both would have kept the stuck one looking busy
        RunState.FurnaceJob other = furnace(90000);
        assertTrue(FurnacePlan.waitIsHonest(List.of(job, other), 5000));
        assertFalse(FurnacePlan.creditsWait(true, job, 5000));
    }

    @Test
    public void aVisitMayStandAtTheScreenForWhatIsLeftPlusThePatience() {
        RunState.FurnaceJob job = furnace(1000);
        assertEquals(1000 + FurnacePlan.PATIENCE_TICKS, FurnacePlan.waitCap(job, 0));
        // we got there late: a job past its estimate still gets the patience
        assertEquals(FurnacePlan.PATIENCE_TICKS, FurnacePlan.waitCap(job, 5000));
    }

    @Test
    public void aQuickVisitNeverStandsPastTheStandByBudget() {
        RunState.FurnaceJob job = smoker(300);
        assertEquals(Why.QUICK, one(plan(job, MID_NEED, 0)).why());
        // the estimate plus the patience would be 45 s, the budget is 30 s from first sight
        assertEquals(300 + FurnacePlan.PATIENCE_TICKS, FurnacePlan.waitCap(job, 0));
        assertEquals(FurnacePlan.STAND_BY_CAP_TICKS, FurnacePlan.waitCap(job, 0, true));
        // a trip that starts later gets what is left of it, and none once it is gone
        assertEquals(FurnacePlan.STAND_BY_CAP_TICKS - 100, FurnacePlan.waitCap(job, 100, true));
        assertEquals(0, FurnacePlan.waitCap(job, FurnacePlan.STAND_BY_CAP_TICKS + 50, true));
        // anything that is not a quick stand-by keeps the old cap
        assertEquals(FurnacePlan.waitCap(job, 100), FurnacePlan.waitCap(job, 100, false));
        RunState.FurnaceJob unseen = furnace(1000);
        assertEquals(FurnacePlan.waitCap(unseen, 0), FurnacePlan.waitCap(unseen, 0, true));
    }

    // ---- stale

    @Test
    public void aJobLoadedHalfAnHourAgoIsNotWorthAWalk() {
        RunState.FurnaceJob old = furnace(100);
        RunState.FurnaceJob recent = furnace(100);
        recent.startTick = 30_000;
        recent.pos = new RunState.Pos(1, 64, 1);
        assertFalse(FurnacePlan.stale(old, FurnacePlan.STALE_TICKS));
        assertTrue(FurnacePlan.stale(old, FurnacePlan.STALE_TICKS + 1));
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(old, recent));
        List<RunState.FurnaceJob> gone = FurnacePlan.dropStale(jobs, 40_000);
        assertEquals(List.of(old), gone);
        assertEquals(List.of(recent), jobs);
    }

    // a visit re-stamps startTick, so a job we keep going back to never goes stale
    @Test
    public void aVisitedJobIsAsOldAsItsLastVisit() {
        List<RunState.FurnaceJob> jobs = new ArrayList<>(List.of(furnace(100)));
        RunState.FurnaceJob j = jobs.get(0);
        FurnaceJobs.afterVisit(jobs, j, 4, 800, 35_000);
        assertFalse(FurnacePlan.stale(j, 35_000 + FurnacePlan.STALE_TICKS));
        assertTrue(FurnacePlan.stale(j, 35_000 + FurnacePlan.STALE_TICKS + 1));
    }

    // ---- the cook

    @Test
    public void theCookBackoffEndsAtItsTickNotBefore() {
        RunState run = new RunState();
        assertFalse(FurnacePlan.cookSuspended(run.cook, 0));
        FurnacePlan.cookSuspend(run.cook, 1000);
        assertTrue(FurnacePlan.cookSuspended(run.cook, 1001));
        assertTrue(FurnacePlan.cookSuspended(run.cook, 1000 + FurnacePlan.COOK_BACKOFF_TICKS - 1));
        assertFalse(FurnacePlan.cookSuspended(run.cook, 1000 + FurnacePlan.COOK_BACKOFF_TICKS));
    }

    @Test
    public void givingUpTheCookAlsoLetsGoOfItsStation() {
        RunState run = new RunState();
        FurnacePlan.cookCommit(run.cook, true, 100);
        assertEquals("smoker", FurnacePlan.cookStation(run.cook, 100));
        FurnacePlan.cookSuspend(run.cook, 150);
        assertNull(FurnacePlan.cookStation(run.cook, 150));
    }

    @Test
    public void theCooksStationGoesStaleWhenTheTaskStopsSayingSo() {
        RunState run = new RunState();
        assertNull(FurnacePlan.cookStation(run.cook, 0));
        FurnacePlan.cookCommit(run.cook, false, 100);
        assertEquals("furnace", FurnacePlan.cookStation(run.cook, 100 + FurnacePlan.COOK_STATION_STALE_TICKS));
        // a task that stopped being ticked without a stop must not pin the planner to a smoker for ever
        assertNull(FurnacePlan.cookStation(run.cook, 100 + FurnacePlan.COOK_STATION_STALE_TICKS + 1));
        FurnacePlan.cookCommit(run.cook, true, 400);
        assertEquals("smoker", FurnacePlan.cookStation(run.cook, 410));
        FurnacePlan.cookRelease(run.cook);
        assertNull(FurnacePlan.cookStation(run.cook, 410));
    }

    @Test
    public void theCookGivesUpAfterTwoAndAHalfMinutesWithNothingChanging() {
        assertFalse(FurnacePlan.cookGaveUp(100, 100 + FurnacePlan.COOK_GIVE_UP_TICKS));
        assertTrue(FurnacePlan.cookGaveUp(100, 100 + FurnacePlan.COOK_GIVE_UP_TICKS + 1));
        assertEquals(150 * 20, FurnacePlan.COOK_GIVE_UP_TICKS);
    }

    @Test
    public void theCookBeforeThePickupHasHalfAMinute() {
        assertFalse(FurnacePlan.cookFirstExpired(100, 100 + FurnacePlan.COOK_FIRST_TICKS));
        assertTrue(FurnacePlan.cookFirstExpired(100, 100 + FurnacePlan.COOK_FIRST_TICKS + 1));
    }

    // a fresh run state is a fresh cook: that is what "a run reset clears it" means now that nothing is static
    @Test
    public void aFreshRunStateStartsWithAFreshCook() {
        RunState first = new RunState();
        CookTrip.bind(first.cook);
        try {
            CookTrip.suspend(1000);
            CookTrip.commit(true, 1000);
            assertTrue(FurnacePlan.cookSuspended(first.cook, 1001));
            RunState second = new RunState();
            CookTrip.bind(second.cook);
            assertFalse(FurnacePlan.cookSuspended(second.cook, 1001));
            assertNull(FurnacePlan.cookStation(second.cook, 1001));
            // what the new run's task says lands in the new run's state, not the old one's
            CookTrip.suspend(2000);
            assertTrue(FurnacePlan.cookSuspended(second.cook, 2001));
            assertEquals(1000 + FurnacePlan.COOK_BACKOFF_TICKS, first.cook.until);
        } finally {
            CookTrip.unbind();
        }
    }

    @Test
    public void aCookWithNoRunBoundHasNobodyToTell() {
        CookTrip.unbind();
        CookTrip.suspend(5);
        CookTrip.commit(false, 5);
        CookTrip.release();
        assertFalse(FurnacePlan.cookSuspended(new RunState().cook, 5));
    }

    // ---- the climb and the early load

    @Test
    public void theClimbToTheSurfaceGivesUpAfterNinetySeconds() {
        assertFalse(FurnacePlan.climbGaveUp(100, 100 + FurnacePlan.CLIMB_GIVE_UP_TICKS));
        assertTrue(FurnacePlan.climbGaveUp(100, 100 + FurnacePlan.CLIMB_GIVE_UP_TICKS + 1));
        assertEquals(90 * 20, FurnacePlan.CLIMB_GIVE_UP_TICKS);
    }

    // ---- the clock itself

    // what each number is, in seconds, so a change to one has to be a deliberate one
    @Test
    public void theClockIsInHumanNumbers() {
        assertEquals(10 * 20, FurnacePlan.NEARLY_TICKS);
        assertEquals(30 * 20, FurnacePlan.PATIENCE_TICKS);
        assertEquals(20 * 20, FurnacePlan.STAND_BY_MAX_TICKS);
        assertEquals(30 * 20, FurnacePlan.STAND_BY_CAP_TICKS);
        assertEquals(30 * 60 * 20, FurnacePlan.STALE_TICKS);
        assertEquals(2, FurnacePlan.STALL_LIMIT);
        assertEquals(4 * 60 * 20, FurnacePlan.COOK_BACKOFF_TICKS);
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        org.junit.Assert.assertNotEquals(unexpected, actual);
    }

    private static void assertNotEquals(String message, Object unexpected, Object actual) {
        org.junit.Assert.assertNotEquals(message, unexpected, actual);
    }
}
