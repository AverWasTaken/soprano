package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// standing at a smoker while a skeleton plinks from across the field. the walking premise (a moving target is a bad target)
// does not hold, so a sniper that lands two arrows close together, with a line to us, is a fight. the pinned walking
// cases stay where they were (CombatCommitTest, CombatCommitEdgesTest, WitchHitTest)
public class CombatCommitPinnedTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;

    // a skeleton: sinceHit is its last arrow, before the one before that
    private static Foe skeleton(double distance, long sinceHit, long before, boolean sight) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, before);
    }

    // two arrows sixty ticks apart, the last one a few ticks ago, from twelve blocks with a line
    private static Foe pinner() {
        return skeleton(12, 3, 63, true);
    }

    private static Tick tick(long now, float hp, boolean anchored, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, true, false, anchored);
    }

    private static Tick still(Foe... foes) {
        return tick(NOW, 20, true, null, foes);
    }

    @Test
    public void twoArrowsWhileWeStoodStillFromTwelveBlocksIsAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(still(pinner())));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(CombatCommit.Why.PINNED, c.why());
        assertEquals(SKELETON, c.targetId());
        assertEquals("combat: [overworld] FIGHT skeleton (shot us twice while we stood still, hp 20)",
                CombatLog.line(Dimension.OVERWORLD, Event.FIGHT_START, c.why(), false, "skeleton", "it", "1 skeleton", 20, 0));
    }

    @Test
    public void oneArrowIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(still(skeleton(12, 3, NEVER, true))));
    }

    @Test
    public void twoArrowsTwoHundredTicksApartIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(still(skeleton(12, 3, 203, true))));
        // right on the edge of the window it still is one
        assertEquals(Event.FIGHT_START, new CombatCommit().step(still(skeleton(12, 3, CombatCommit.PIN_WINDOW, true))));
        assertEquals(Event.NONE, new CombatCommit().step(still(skeleton(12, 3, CombatCommit.PIN_WINDOW + 1, true))));
    }

    // the walking premise holds for a bot that is going somewhere: that is what the pinned tests elsewhere are about
    @Test
    public void notAnchoredTwoArrowsAreStillScenery() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, false, null, pinner())));
    }

    @Test
    public void withoutALineOfSightItIsNothing() {
        assertEquals(Event.NONE, new CombatCommit().step(still(skeleton(12, 3, 63, false))));
    }

    @Test
    public void beyondTheFoeRangeItIsNothing() {
        assertEquals(Event.FIGHT_START, new CombatCommit().step(still(skeleton(CombatCommit.FOE_RANGE, 3, 63, true))));
        assertEquals(Event.NONE, new CombatCommit().step(still(skeleton(CombatCommit.FOE_RANGE + 1, 3, 63, true))));
    }

    // at or under the bail line the low hp rule decides, and that one is a run, never a fight
    @Test
    public void atTheBailLineThePinnedRuleStepsAside() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, CombatCommit.FLEE_HP, true, null, pinner())));
        assertEquals(CombatCommit.Why.SHOT, c.why());
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, CombatCommit.FLEE_HP + 1, true, null, pinner())));
        // and with no line (so nothing for the low hp rule either) the pinned rule stays out of it by itself
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, CombatCommit.FLEE_HP, true, null, skeleton(12, 3, 63, false))));
    }

    @Test
    public void aCrowdOnUsStillWins() {
        CombatCommit c = new CombatCommit();
        List<Foe> foes = new ArrayList<>(List.of(pinner()));
        for (int i = 0; i < 3; i++) foes.add(new Foe(10 + i, 2, false, false, NEVER));
        assertEquals(Event.RUN_START, c.step(new Tick(NOW, 20, 0, 0, foes, null, Way.NONE, true, false, true)));
        assertEquals(CombatCommit.Why.CROWD, c.why());
    }

    @Test
    public void somethingInContactThatHitUsIsTheOrdinaryFightFirst() {
        CombatCommit c = new CombatCommit();
        Foe zombie = new Foe(10, 2, false, false, 2);
        assertEquals(Event.FIGHT_START, c.step(still(pinner(), zombie)));
        assertEquals(CombatCommit.Why.HIT, c.why());
        assertEquals(10, c.targetId());
    }

    @Test
    public void bareHandsDoNotGoAndGetASkeleton() {
        Tick unarmed = new Tick(NOW, 20, 0, 0, List.of(pinner()), null, Way.NONE, false, false, true);
        assertEquals(Event.NONE, new CombatCommit().step(unarmed));
    }

    @Test
    public void aLobberKeepsItsOwnRuleAndAWitchIsNotASniper() {
        Foe witch = new Foe(5, 12, true, false, 3, Kind.LOBBER, Double.POSITIVE_INFINITY, true, 63);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(still(witch)));
        // the lobber rule was the one that fired, not this one (not anchored, same answer)
        c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, false, null, witch)));
        assertEquals(CombatCommit.Why.HIT, c.why());
    }

    @Test
    public void theLineAndTheHitBeforeSurviveTheTripThroughFoeRules() {
        FoeRules.Candidate c = new FoeRules.Candidate("skeleton", SKELETON, 12, () -> false, () -> true, () -> true, 3, 0, true, false,
                Double.POSITIVE_INFINITY, true, 63);
        Foe foe = FoeRules.accept(Dimension.OVERWORLD, c);
        assertTrue(foe.sight());
        assertEquals(63, foe.sinceHitBefore());
        assertTrue(foe.pinning(false));
        // the short constructors know nothing about either, which is the cautious answer
        Foe plain = FoeRules.accept(Dimension.OVERWORLD, new FoeRules.Candidate("skeleton", SKELETON, 12, () -> false, () -> true,
                () -> true, 3, 0, true, false));
        assertEquals(NEVER, plain.sinceHitBefore());
        assertTrue(!plain.pinning(false));
    }

    @Test
    public void aBlazeOverLavaIsNotSomethingToWalkUpTo() {
        Foe blaze = new Foe(7, 12, true, false, 3, Kind.FLYER, Double.POSITIVE_INFINITY, true, 63);
        assertEquals(Event.NONE, new CombatCommit().step(still(blaze)));
    }

    @Test
    public void inTheCooldownOnlyAFreshArrowCounts() {
        CombatCommit c = new CombatCommit();
        // a fight that ended: the cooldown is on
        assertEquals(Event.FIGHT_START, c.step(still(pinner())));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 5, 20, true, null)));
        assertTrue(c.coolingDown(NOW + 10));
        // still shooting but not news
        assertEquals(Event.NONE, c.step(tick(NOW + 20, 20, true, null, skeleton(12, 15, 75, true))));
        // a new arrow is news
        assertEquals(Event.FIGHT_START, c.step(tick(NOW + 30, 20, true, null, skeleton(12, 1, 16, true))));
    }

    // ---- a fight we cannot win

    // the skeleton sits on a ledge and keeps shooting (an arrow every 25 ticks). the stall rule writes it off
    private static long stallAFight(CombatCommit c) {
        assertEquals(Event.FIGHT_START, c.step(still(pinner())));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe s = skeleton(12, (t - NOW) % 25, (t - NOW) % 25 + 25, true);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, true, s, s)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = skeleton(12, (gaveUp - NOW) % 25, (gaveUp - NOW) % 25 + 25, true);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, 20, true, stale, stale)));
        assertTrue(c.isIgnored(SKELETON));
        return gaveUp;
    }

    @Test
    public void aSniperWeCouldNotGetToIsARunTheNextTimeItPinsUsNotAnotherFight() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        // back at the furnace and shot twice again, inside the window
        long again = gaveUp + 40;
        assertEquals(Event.RUN_START, c.step(tick(again, 20, true, null, skeleton(12, 2, 27, true))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(CombatCommit.Why.UNREACHABLE, c.why());
        assertEquals("combat: [overworld] RUN from 1 skeleton (can't reach it, hp 20)",
                CombatLog.line(Dimension.OVERWORLD, Event.RUN_START, c.why(), false, "skeleton", "it", "1 skeleton", 20, 0));
    }

    // a miss, or an arrow still in the air, is not a sniper lost: we are closing in, and only a real stall writes it off
    @Test
    public void aSniperWeAreWalkingUpToIsNotLostForAMissedArrow() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(still(skeleton(16, 3, 63, true))));
        for (long t = NOW + 1; t < NOW + 150; t++) {
            // closing at a block a second, no more arrows: the old lost rule would have fired at 60 ticks
            Foe s = skeleton(16 - (t - NOW) * 0.05, t - NOW + 3, t - NOW + 63, true);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, true, s, s)));
        }
        assertEquals(Mode.FIGHT, c.mode());
        // and once it is in contact the fight is the ordinary one
        Foe close = new Foe(SKELETON, 2, true, false, 90);
        assertEquals(Event.NONE, c.step(tick(NOW + 150, 20, true, close, close)));
    }

    @Test
    public void aSniperThatStaysQuietWhileIgnoredIsStillIgnored() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        // the arrow that was already in the book when we gave up is no news
        assertEquals(Event.NONE, c.step(tick(gaveUp + 50, 20, true, null, skeleton(12, 50, 75, true))));
    }

    @Test
    public void afterHalfAMinuteItIsAFightAgain() {
        CombatCommit c = new CombatCommit();
        long gaveUp = stallAFight(c);
        long later = gaveUp + CombatCommit.LOBBER_GIVE_UP_TICKS + 20;
        assertEquals(Event.FIGHT_START, c.step(tick(later, 20, true, null, skeleton(12, 2, 27, true))));
        assertEquals(CombatCommit.Why.PINNED, c.why());
    }

    // one clock per kind of ranged foe: failing to reach a witch says nothing about a skeleton
    @Test
    public void aWitchWeCouldNotReachDoesNotMakeASkeletonARun() {
        CombatCommit c = new CombatCommit();
        Foe witch = new Foe(5, 8, true, false, 3, Kind.LOBBER);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, true, null, witch)));
        for (long t = NOW + 1; t < NOW + CombatCommit.STALL_TICKS; t++) {
            Foe w = new Foe(5, 8, true, false, (t - NOW) % 25, Kind.LOBBER);
            assertEquals("tick " + t, Event.NONE, c.step(tick(t, 20, true, w, w)));
        }
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        Foe stale = new Foe(5, 8, true, false, (gaveUp - NOW) % 25, Kind.LOBBER);
        assertEquals(Event.FIGHT_STALLED, c.step(tick(gaveUp, 20, true, stale, stale)));
        assertEquals(Event.FIGHT_START, c.step(tick(gaveUp + 40, 20, true, null, skeleton(12, 2, 27, true))));
        assertEquals(CombatCommit.Why.PINNED, c.why());
    }

    // a skeleton that walked into contact and then stalled is the ordinary fight's business, not a sniper we could not reach
    @Test
    public void aFightThatStartedInContactDoesNotWriteSnipersOff() {
        CombatCommit c = new CombatCommit();
        Foe close = new Foe(SKELETON, 2, true, false, 2);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, false, null, close)));
        assertEquals(CombatCommit.Why.HIT, c.why());
        Foe far = new Foe(SKELETON, 9, true, false, 2);
        Event ended = Event.NONE;
        long t = NOW + 1;
        for (; t < NOW + 400 && ended == Event.NONE; t++) {
            Foe s = new Foe(SKELETON, 9, true, false, (t - NOW) % 25);
            ended = c.step(tick(t, 20, true, s, s));
        }
        assertTrue(ended == Event.FIGHT_STALLED || ended == Event.FIGHT_LOST);
        assertEquals(Event.FIGHT_START, c.step(tick(t + 200, 20, true, null, skeleton(12, 2, 27, true))));
        assertEquals(far.id(), c.targetId());
    }
}
