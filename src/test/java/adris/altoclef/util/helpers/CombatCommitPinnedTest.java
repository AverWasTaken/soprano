package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// a skeleton that is shooting us is one target: kill it. walking or standing, no pin window, no waiting for a second arrow.
// hurt, or more than one of them, is a run that ends when none of them can see us
public class CombatCommitPinnedTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;

    private static Foe skeleton(int id, double distance, long sinceHit, boolean sight) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, NEVER);
    }

    private static Foe skeleton(double distance, long sinceHit, boolean sight) {
        return skeleton(SKELETON, distance, sinceHit, sight);
    }

    // an arrow a few ticks ago, from twelve blocks with a line
    private static Foe shooter() {
        return skeleton(12, 3, true);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, armed, false);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return tick(now, hp, true, target, foes);
    }

    // a tick a run is walking, half a block a tick so the stuck rule has nothing to say
    private static Tick walking(long now, float hp, Foe... foes) {
        return new Tick(now, hp, (now - NOW) * 0.5, 0, List.of(foes), null, Way.NONE, true, false);
    }

    @Test
    public void oneShootingSkeletonIsAFightAtOnce() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.PINNED, c.why());
        assertEquals(SKELETON, c.targetId());
    }

    @Test
    public void anOldArrowOrNoLineOrTooFarIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, skeleton(12, CombatCommit.SHOT_MEMORY + 1, true))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, skeleton(12, 3, false))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, skeleton(CombatCommit.FOE_RANGE + 1, 3, true))));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, 20, null, skeleton(CombatCommit.FOE_RANGE, 3, true))));
    }

    @Test
    public void hurtItIsARunNotAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, CombatCommit.FLEE_HP, null, shooter())));
        assertEquals(Why.SHOT, c.why());
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, CombatCommit.FLEE_HP + 1, null, shooter())));
    }

    @Test
    public void twoShootingSkeletonsAreARunAtAnyHp() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, shooter(), skeleton(7, 9, 20, true))));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aSecondSkeletonJoiningTurnsTheFightIntoARun() {
        CombatCommit c = new CombatCommit();
        Foe first = shooter();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, first, first)));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 20, first, first)));
        Foe second = skeleton(7, 14, 2, true);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 2, 20, first, first, second)));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aCrowdOnUsStillWins() {
        CombatCommit c = new CombatCommit();
        List<Foe> foes = new ArrayList<>(List.of(shooter()));
        for (int i = 0; i < 3; i++) foes.add(new Foe(10 + i, 2, false, false, NEVER));
        assertEquals(Event.RUN_START, c.step(new Tick(NOW, 20, 0, 0, foes, null, Way.NONE, true, false)));
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void somethingInContactThatHitUsIsTheOrdinaryFightFirst() {
        CombatCommit c = new CombatCommit();
        Foe zombie = new Foe(10, 2, false, false, 2);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter(), zombie)));
        assertEquals(Why.HIT, c.why());
        assertEquals(10, c.targetId());
    }

    @Test
    public void bareHandsDoNotGoAndGetASkeleton() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, false, null, shooter())));
        // hurt it is still the run
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, CombatCommit.FLEE_HP, false, null, shooter())));
        assertEquals(Why.SHOT, c.why());
    }

    @Test
    public void aWitchIsStillTheLobberRuleAndABlazeIsNotASniper() {
        Foe witch = new Foe(5, 12, true, false, 3, Kind.LOBBER, Double.POSITIVE_INFINITY, true, NEVER);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, witch)));
        assertEquals(Why.HIT, c.why());
        Foe blaze = new Foe(7, 12, true, false, 3, Kind.FLYER, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, blaze)));
    }

    @Test
    public void theLineSurvivesTheTripThroughFoeRules() {
        FoeRules.Candidate c = new FoeRules.Candidate("skeleton", SKELETON, 12, () -> false, () -> true, () -> true, 3, 0, true, false,
                Double.POSITIVE_INFINITY, true, NEVER);
        Foe foe = FoeRules.accept(Dimension.OVERWORLD, c);
        assertTrue(foe.sight());
        assertTrue(foe.shooting());
        // the short constructors know nothing about the line, which is the cautious answer
        Foe plain = FoeRules.accept(Dimension.OVERWORLD, new FoeRules.Candidate("skeleton", SKELETON, 12, () -> false, () -> true,
                () -> true, 3, 0, true, false));
        assertTrue(!plain.sight());
    }

    @Test
    public void inTheCooldownOnlyAFreshArrowCounts() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 5, 20, null)));
        assertTrue(c.coolingDown(NOW + 10));
        assertEquals(Event.NONE, c.step(tick(NOW + 20, 20, null, skeleton(12, 15, true))));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW + 30, 20, null, skeleton(12, 1, true))));
    }

    // ---- a fight we cannot win

    // the skeleton sits on a ledge and keeps shooting (an arrow every 25 ticks). the stall rule writes it off
    private static long stallAFight(CombatCommit c) {
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, shooter())));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe s = skeleton(12, (t - NOW) % 25, true);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, s, s)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = skeleton(12, (gaveUp - NOW) % 25, true);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, 20, stale, stale)));
        assertTrue(c.isIgnored(SKELETON));
        return gaveUp;
    }

    @Test
    public void aSniperWeCouldNotGetToIsARunOutOfItsSightNotAnotherFight() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + 40, 20, null, skeleton(12, 2, true))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.SHOT, c.why());
        // and it is the sight run: out of its line for the count ends it, far or not
        long t = gaveUp + 41;
        for (; t < gaveUp + 41 + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 20, skeleton(14, 2, false))));
        }
        assertEquals(Event.RUN_CLEAR, c.step(walking(t, 20, skeleton(14, 2, false))));
    }

    @Test
    public void itStaysGivenUpForLongerThanALobberWould() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + CombatCommit.LOBBER_GIVE_UP_TICKS + 20, 20, null, skeleton(12, 2, true))));
        CombatCommit later = new CombatCommit();
        long again = stallAFight(later) + CombatCommit.SNIPER_GIVE_UP_TICKS + 20;
        assertEquals(Event.FIGHT_START, later.step(tick(again, 20, null, skeleton(12, 2, true))));
    }

    @Test
    public void aSniperWeAreWalkingUpToIsNotLostForAMissedArrow() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, skeleton(16, 3, true))));
        for (long t = NOW + 1; t < NOW + 150; t++) {
            Foe s = skeleton(16 - (t - NOW) * 0.05, t - NOW + 3, true);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, s, s)));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aWitchWeCouldNotReachDoesNotMakeASkeletonARun() {
        CombatCommit c = new CombatCommit();
        Foe witch = new Foe(5, 8, true, false, 3, Kind.LOBBER);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, witch)));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe w = new Foe(5, 8, true, false, (t - NOW) % 25, Kind.LOBBER);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, w, w)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = new Foe(5, 8, true, false, (gaveUp - NOW) % 25, Kind.LOBBER);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, 20, stale, stale)));
        assertEquals(Event.FIGHT_START, c.step(tick(gaveUp + 40, 20, null, skeleton(12, 2, true))));
        assertEquals(Why.PINNED, c.why());
    }

    // ---- the run out of sight

    // sight stays on us, so being far off for a long while is not the end of this run
    @Test
    public void aShooterRunIsNotOverWhileTheyCanSeeUs() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(walking(NOW, 6, skeleton(14, 2, true))));
        for (long t = NOW + 1; t < NOW + 200; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 6, skeleton(30, 2, true))));
        }
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void itEndsOnceNoOneHasALineForAWhile() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(walking(NOW, 6, skeleton(14, 2, true))));
        // the line drops for one tick short of the count, then flickers back and the count starts over
        long t = NOW + 1;
        for (; t < NOW + 1 + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 6, skeleton(14, 2, false))));
        }
        assertEquals(Event.NONE, c.step(walking(t++, 6, skeleton(14, 2, true))));
        long from = t;
        for (; t < from + CombatCommit.LOS_CLEAR_TICKS; t++) {
            assertEquals("tick " + t, Event.NONE, c.step(walking(t, 6, skeleton(14, 2, false))));
        }
        assertEquals(Event.RUN_CLEAR, c.step(walking(t, 6, skeleton(14, 2, false))));
        assertEquals(Mode.NONE, c.mode());
    }
}
