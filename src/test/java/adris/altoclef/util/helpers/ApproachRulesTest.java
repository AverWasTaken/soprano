package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ApproachRules.Plan;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ApproachRulesTest {
    private static final double REACH = 4;
    private static final double MAINTAIN = REACH - 1;

    // one run of the kill task against a mob that does not move: we walk in at 0.5 a tick, the child counts as started
    // every time it goes from not in the tree to in the tree. `gates` says, per tick, whether swinging is allowed yet
    private static final class Run {
        double dist;
        boolean live;
        int starts;
        int stops;

        Plan tick(boolean ready, boolean safe) {
            boolean inRange = dist <= REACH;
            boolean tooClose = dist < MAINTAIN;
            Plan plan = ApproachRules.plan(live, inRange, inRange, ready, safe, tooClose);
            boolean next = plan == Plan.APPROACH || plan == Plan.KEEP_CHILD;
            if (next && !live) starts++;
            if (!next && live) stops++;
            live = next;
            // the child walks us in while it is in the tree
            if (live) dist = Math.max(0.5, dist - 0.5);
            return plan;
        }
    }

    @Test
    public void inReachWithAClearShotAndAllGatesOpenIsTheSwing() {
        assertEquals(Plan.INTERACT, ApproachRules.plan(true, true, true, true, true, false));
        assertEquals(Plan.INTERACT, ApproachRules.plan(false, true, true, true, true, true));
    }

    @Test
    public void outOfReachWalksAndKeepsTheSameChild() {
        assertEquals(Plan.APPROACH, ApproachRules.plan(false, false, false, true, true, false));
        assertEquals(Plan.APPROACH, ApproachRules.plan(true, false, false, true, false, false));
    }

    @Test
    public void halfwayThroughAStepTheWalkingChildIsNotCutOff() {
        // in reach and close, but baritone is mid step: this used to return null and stop the child
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, true));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, true, false, false));
        // and when nothing is walking there is nothing to start
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, true, false, true));
    }

    @Test
    public void inReachWithAGateClosedStandsStillInsteadOfStartingTheChild() {
        // eating, a fall, the bucket: not a reason to walk. the old rule started the child on every such tick past the line
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, false, true, false));
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, true, false, true, true));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, true, false, true, false));
    }

    @Test
    public void noShotInReachStillApproachesToFindOneOrDigTowardsIt() {
        assertEquals(Plan.APPROACH, ApproachRules.plan(false, true, false, true, true, false));
        // too close with no shot is the old shuffle, unless a step is running
        assertEquals(Plan.WANDER, ApproachRules.plan(false, true, false, true, true, true));
        assertEquals(Plan.WAIT, ApproachRules.plan(false, true, false, true, false, true));
        assertEquals(Plan.KEEP_CHILD, ApproachRules.plan(true, true, false, true, false, true));
    }

    @Test
    public void aMobThatStaysPutIsApproachedOnceNoMatterHowTheGatesFlicker() {
        Run run = new Run();
        run.dist = 12;
        // walking in, with the path unsafe to cancel every other tick and a gate closing now and then
        for (int tick = 0; tick < 40; tick++) {
            run.tick(tick % 7 != 3, tick % 2 == 0);
        }
        // and standing next to it for a long while with everything flickering
        for (int tick = 0; tick < 200; tick++) {
            run.tick(tick % 3 != 0, tick % 5 != 0);
        }
        assertEquals(1, run.starts);
        assertEquals(1, run.stops);
        assertEquals(false, run.live);
    }

    @Test
    public void everyTimeTheMobLeavesReachIsOneMoreApproach() {
        Run run = new Run();
        run.dist = 10;
        for (int tick = 0; tick < 30; tick++) {
            run.tick(true, tick % 2 == 0);
        }
        assertEquals(1, run.starts);
        for (int leave = 2; leave <= 4; leave++) {
            // knocked back, or wandered off, past reach
            run.dist = REACH + 1.5;
            for (int tick = 0; tick < 30; tick++) {
                run.tick(tick % 4 != 1, tick % 3 != 0);
            }
            assertEquals(leave, run.starts);
        }
        assertEquals(4, run.stops);
    }

    @Test
    public void aMobDriftingInsideReachNeverRestartsTheApproach() {
        // 3.2 to 4.0 and back: in reach the whole time, the child is not wanted for any of it
        Run run = new Run();
        run.dist = 3.5;
        for (int tick = 0; tick < 100; tick++) {
            run.dist = 3.2 + 0.8 * (tick % 2);
            double was = run.dist;
            run.tick(tick % 3 != 0, tick % 4 != 0);
            run.dist = was;
        }
        assertEquals(0, run.starts);
    }
}
