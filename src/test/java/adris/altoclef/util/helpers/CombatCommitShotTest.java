package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

// hurt, and a shooter with a line to us is landing arrows: at hp 5 an arrow is an emergency whether we stand or walk, and
// whether it is 6 blocks off or 14. the low hp run used to need the foe within 8
public class CombatCommitShotTest {

    private static final long NOW = 1000;
    private static final int SKELETON = 6;

    private static Foe skeleton(double distance, long sinceHit, boolean sight) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, CombatCommit.NEVER);
    }

    private static Tick tick(long now, float hp, double x, boolean anchored, Foe... foes) {
        return new Tick(now, hp, x, 0, List.of(foes), null, Way.NONE, true, false, anchored);
    }

    private static Tick tick(float hp, Foe... foes) {
        return tick(NOW, hp, 0, false, foes);
    }

    @Test
    public void hurtAndShotAtFromTwelveBlocksIsARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(6, skeleton(12, 40, true))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(CombatCommit.Why.SHOT, c.why());
        assertEquals("combat: [overworld] RUN from 1 skeleton (hp 6, it is shooting)",
                CombatLog.line(Dimension.OVERWORLD, Event.RUN_START, c.why(), false, "skeleton", "it", "1 skeleton", 6, 0));
    }

    @Test
    public void thatIsTrueWalkingOrStandingStill() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 5, 0, true, skeleton(12, 40, true))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 5, 0, false, skeleton(12, 40, true))));
    }

    @Test
    public void anArrowThatLandedTwoHundredTicksAgoIsNotShooting() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(12, 200, true))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(6, skeleton(12, CombatCommit.SHOT_MEMORY, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(12, CombatCommit.SHOT_MEMORY + 1, true))));
    }

    @Test
    public void withoutALineOfSightItIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(12, 40, false))));
    }

    @Test
    public void beyondTheFoeRangeItIsNothing() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(6, skeleton(CombatCommit.FOE_RANGE, 40, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, skeleton(CombatCommit.FOE_RANGE + 1, 40, true))));
    }

    @Test
    public void aboveTheBailLineThisRuleHasNothingToSay() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(CombatCommit.FLEE_HP, skeleton(12, 40, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(CombatCommit.FLEE_HP + 1, skeleton(12, 40, true))));
    }

    // only things that shoot: a zombie that hit us a while ago and stands at twelve blocks is the old rule, scenery
    @Test
    public void aMeleeMobFarAwayIsStillNotNear() {
        Foe zombie = new Foe(9, 12, false, false, 40, Kind.NORMAL, Double.POSITIVE_INFINITY, true, CombatCommit.NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, zombie)));
        Foe creeper = new Foe(9, 12, true, true, 40, Kind.NORMAL, Double.POSITIVE_INFINITY, true, CombatCommit.NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(6, creeper)));
    }

    // something within 8 keeps its own reason
    @Test
    public void aCloserFoeIsStillTheOrdinaryLowHpRun() {
        CombatCommit c = new CombatCommit();
        Foe zombie = new Foe(9, 5, false, false, CombatCommit.NEVER);
        assertEquals(Event.RUN_START, c.step(tick(6, skeleton(12, 40, true), zombie)));
        assertEquals(CombatCommit.Why.LOW_HP, c.why());
    }

    @Test
    public void inTheCooldownOnlyAFreshArrowCounts() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, 0, false, skeleton(12, 2, true))));
        // the run ends (nothing within 12 and 24 blocks from the start)
        long t = NOW + 1;
        Event ended = Event.NONE;
        for (; t < NOW + CombatCommit.RUN_CAP + 10 && ended == Event.NONE; t++) {
            ended = c.step(tick(t, 6, (t - NOW) * 0.5, false, skeleton(14, 60, true)));
        }
        assertNotEquals(Event.NONE, ended);
        assertEquals(Mode.NONE, c.mode());
        // still shooting a while ago: the cooldown does not start another run for old news
        assertEquals(Event.NONE, c.step(tick(t + 1, 6, 100, false, skeleton(12, 30, true))));
        // a new arrow is news
        assertEquals(Event.RUN_START, c.step(tick(t + 2, 6, 100, false, skeleton(12, 1, true))));
    }

    // the shooter follows and keeps a line the whole way. the run cannot get 12 clear, so it ends at the cap, and the next arrow
    // starts a new run from wherever we are by then (not from the old origin, where it would be over on the first tick)
    @Test
    public void aShooterThatFollowsEndsTheRunAtTheCapAndTheNextRunStartsFromHere() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 6, 0, false, skeleton(10, 2, true))));
        assertEquals(0, c.originX(), 0);
        Event event = Event.NONE;
        long t = NOW + 1;
        for (; t < NOW + 2 * CombatCommit.RUN_CAP && event == Event.NONE; t++) {
            event = c.step(tick(t, 6, (t - NOW) * 0.25, false, skeleton(10, (t - NOW) % 50, true)));
        }
        long at = t - 1;
        // the cap, and on the very same step the next arrow (it is fresh) starts the next run
        assertEquals(CombatCommit.RUN_CAP, at - NOW);
        assertEquals(Event.RUN_START, event);
        assertEquals(Event.RUN_CAP, c.endedFirst());
        assertEquals(CombatCommit.Why.SHOT, c.why());
        // the origin is where we were when it started, so the new run has 24 blocks of its own to make
        assertEquals((at - NOW) * 0.25, c.originX(), 1e-9);
        assertEquals(at, c.startTick());
    }
}
