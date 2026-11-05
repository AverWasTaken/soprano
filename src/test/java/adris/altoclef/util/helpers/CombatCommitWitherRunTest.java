package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// a run from wither skeletons is not over until they have stopped following, 12 blocks is well inside their range
public class CombatCommitWitherRunTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe wither(double distance) {
        return new Foe(1, distance, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER).withWither(true);
    }

    // a hoglin in the path is the easy way to get a run going at full hp
    private static CombatCommit running() {
        CombatCommit c = new CombatCommit();
        Foe hoglin = new Foe(7, 1.5, false, false, NEVER, Kind.HEAVY);
        assertEquals(Event.RUN_START, c.step(new Tick(0, 20, 0, 0, List.of(hoglin), null, new Way(Set.of(7), true, 64))));
        return c;
    }

    // jogs on with these foes until the run ends or the clock runs out, and says when. 0.5 a tick is well past 24 blocks in time
    private static long endTick(CombatCommit c, List<Foe> foes, Event expected) {
        for (long t = 1; t <= CombatCommit.RUN_CAP; t++) {
            Event event = c.step(new Tick(t, 20, t * 0.5, 0, foes, null, Way.NONE));
            if (event != Event.NONE) {
                assertEquals(expected, event);
                return t;
            }
        }
        throw new AssertionError("the run never ended");
    }

    // the run the brain feeds: hp flat, nobody on the foe list, one skeleton that never noticed us this far off
    private static Event unawareEnd(float hp, double unaware, long[] at) {
        CombatCommit c = running();
        for (long t = 1; t <= CombatCommit.RUN_CAP; t++) {
            c.angryWither(CombatRules.runWither(hp, Double.POSITIVE_INFINITY, unaware));
            Event event = c.step(new Tick(t, hp, t * 0.5, 0, List.of(), null, Way.NONE));
            if (event != Event.NONE) {
                at[0] = t;
                return event;
            }
        }
        throw new AssertionError("the run never ended");
    }

    @Test
    public void atTheFleeLineAnUnawareSkeletonKeepsTheRunGoingToTheCap() {
        long[] at = new long[1];
        assertEquals(Event.RUN_CAP, unawareEnd(CombatRules.FLEE_HEALTH, 10, at));
        assertEquals(CombatCommit.RUN_CAP, at[0]);
    }

    @Test
    public void aboveTheFleeLineAnUnawareSkeletonIsNoReason() {
        long[] at = new long[1];
        assertEquals(Event.RUN_CLEAR, unawareEnd(CombatRules.FLEE_HEALTH + 0.5f, 10, at));
        assertEquals(true, at[0] < CombatCommit.RUN_CAP);
    }

    @Test
    public void atTheFleeLineOnePastTheAggroLineIsClearAnyway() {
        long[] at = new long[1];
        assertEquals(Event.RUN_CLEAR, unawareEnd(CombatRules.FLEE_HEALTH, CombatCommit.WITHER_AGGRO + 1, at));
        assertEquals(true, at[0] < CombatCommit.RUN_CAP);
    }

    @Test
    public void oneAt15OnlyEndsAtTheCap() {
        CombatCommit c = running();
        assertEquals(CombatCommit.RUN_CAP, endTick(c, List.of(wither(15)), Event.RUN_CAP));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void oneOnTheLineStillHolds() {
        assertEquals(CombatCommit.RUN_CAP, endTick(running(), List.of(wither(CombatCommit.WITHER_AGGRO)), Event.RUN_CAP));
    }

    @Test
    public void oneAt21IsClear() {
        CombatCommit c = running();
        long end = endTick(c, List.of(wither(21)), Event.RUN_CLEAR);
        assertEquals(true, end < CombatCommit.RUN_CAP);
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void anAngryOnePastTheFoeListHoldsToo() {
        CombatCommit c = running();
        // the foe list stops at FOE_RANGE, this one only comes in by the side door
        c.angryWither(18);
        assertEquals(CombatCommit.RUN_CAP, endTick(c, List.of(), Event.RUN_CAP));
    }

    @Test
    public void theClockOnlyStartsOnceItIsBehindTheLine() {
        CombatCommit c = running();
        c.angryWither(18);
        for (long t = 1; t <= 100; t++) {
            assertEquals(Event.NONE, c.step(new Tick(t, 20, t * 0.5, 0, List.of(), null, Way.NONE)));
        }
        c.angryWither(Double.POSITIVE_INFINITY);
        for (long t = 101; t < 101 + CombatCommit.RUN_CLEAR_TICKS; t++) {
            assertEquals(Event.NONE, c.step(new Tick(t, 20, t * 0.5, 0, List.of(), null, Way.NONE)));
        }
        assertEquals(Event.RUN_CLEAR, c.step(new Tick(101 + CombatCommit.RUN_CLEAR_TICKS, 20, 200, 0, List.of(), null, Way.NONE)));
    }

    @Test
    public void anythingElseAt15IsStillClear() {
        Foe zombie = new Foe(3, 15, false, false, NEVER);
        long end = endTick(running(), List.of(zombie), Event.RUN_CLEAR);
        assertEquals(true, end < CombatCommit.RUN_CAP);
    }
}
