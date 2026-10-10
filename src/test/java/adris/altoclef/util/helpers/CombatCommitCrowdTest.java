package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// crowds, bare hands and a run that something keeps biting through. the survival rules around the commitment, walked as
// plain sequences of ticks
public class CombatCommitCrowdTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe skeleton(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, false, true, NEVER);
    }

    private static Tick tick(long now, float hp, double x, boolean armed, Foe target, Foe... foes) {
        return new Tick(now, hp, x, 0, List.of(foes), target, Way.NONE, armed);
    }

    private static Tick tick(long now, float hp, Foe target, Foe... foes) {
        return tick(now, hp, 0, true, target, foes);
    }

    // ---- crowds

    @Test
    public void threeThatHitUsAreARunAtFullHealth() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, zombie(1, 2, 3), zombie(2, 4, 3), skeleton(3, 6, 3))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void threeInContactAreACrowdEvenBeforeTheFirstSwing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, zombie(1, 1, NEVER), zombie(2, 2, NEVER), zombie(3, 2.9, NEVER))));
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void bystandersNeverMakeACrowd() {
        // five of them standing about, none has touched us and only one is in contact: scenery, as ever
        CombatCommit c = new CombatCommit();
        for (long now = NOW; now < NOW + 200; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 20, null, zombie(1, 2, NEVER), zombie(2, 4, NEVER), zombie(3, 5.5, NEVER),
                    skeleton(4, 9, NEVER), zombie(5, 7, NEVER))));
        }
        // one swings: that one is a fight, the rest are still watching
        assertEquals(Event.FIGHT_START, c.step(tick(NOW + 200, 20, null, zombie(1, 2, 0), zombie(2, 4, NEVER), zombie(3, 5.5, NEVER),
                skeleton(4, 9, NEVER), zombie(5, 7, NEVER))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aHitFurtherThanTheSwarmRangeDoesNotCount() {
        assertFalse(CombatCommit.crowded(20, List.of(skeleton(1, 6.5, 3), skeleton(2, 7, 3), skeleton(3, 9, 3)), false));
        assertTrue(CombatCommit.crowded(20, List.of(skeleton(1, 6, 3), skeleton(2, 5, 3), skeleton(3, 4, 3)), false));
    }

    @Test
    public void creepersAreNotPartOfACrowd() {
        assertFalse(CombatCommit.crowded(20, List.of(zombie(1, 1, NEVER), zombie(2, 2, NEVER), creeper(3, 1)), false));
        assertFalse(CombatCommit.crowded(13, List.of(zombie(1, 1, NEVER), creeper(3, 1)), false));
    }

    @Test
    public void aCrowdJoiningAFightBailsItToARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, zombie(7, 2, 0), zombie(7, 2, 0))));
        Foe target = zombie(7, 2, 5);
        // two on us at full health is a fight
        assertEquals(Event.NONE, c.step(tick(NOW + 5, 20, target, target, zombie(8, 2.5, 5))));
        // the third one is a crowd
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 6, 20, 15, true, target, target, zombie(8, 2.5, 6), zombie(9, 5, 6))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.CROWD, c.why());
        assertEquals(15, c.originX(), 0);
    }

    @Test
    public void twoAttackersWhileHurtAreACrowd() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, zombie(7, 2, 0), zombie(7, 2, 0))));
        Foe target = zombie(7, 2, 5);
        assertEquals(Event.NONE, c.step(tick(NOW + 5, 12.5f, target, target, zombie(8, 2.5, 5))));
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 6, 12f, target, target, zombie(8, 2.5, 6))));
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void twoAttackersAtTenHpRunFromTheStart() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, null, skeleton(1, 5, 2), skeleton(2, 4, 2))));
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void threeAttackersAtTwentyHpRunFromTheStart() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, zombie(1, 2, 2), zombie(2, 3, 2), zombie(3, 2.5, 2))));
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void aStaleCrowdDoesNotCountWhileCoolingDown() {
        // after a commitment ends the cooldown only listens to news: hits of a few seconds ago are not a crowd, fresh ones are
        assertFalse(CombatCommit.crowded(20, List.of(skeleton(1, 5, 50), skeleton(2, 5, 50), skeleton(3, 5, 50)), true));
        assertTrue(CombatCommit.crowded(20, List.of(skeleton(1, 5, 2), skeleton(2, 5, 3), skeleton(3, 5, 4)), true));
    }

    @Test
    public void aRunThatIsStillSwarmedAtTheCapTurnsAroundAfterOneMoreWindow() {
        // the crowd follows us for the whole run, so it cannot flee forever: two cap windows, then the boxed in fight
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, zombie(1, 2, NEVER), zombie(2, 2.5, NEVER), zombie(3, 2.8, NEVER))));
        Event seen = Event.NONE;
        long first = -1;
        long second = -1;
        for (long now = NOW + 1; now <= NOW + 2 * CombatCommit.RUN_CAP + 5 && c.mode() == Mode.RUN; now++) {
            seen = c.step(tick(now, 20, now * 0.5, true, null, zombie(1, 2, NEVER), zombie(2, 2.5, NEVER), zombie(3, 2.8, NEVER)));
            if (seen == Event.RUN_EXTENDED) first = now;
            if (seen == Event.RUN_CHASED_FIGHT) second = now;
        }
        assertEquals(NOW + CombatCommit.RUN_CAP, first);
        assertEquals(NOW + 2 * CombatCommit.RUN_CAP, second);
        assertEquals(Mode.FIGHT, c.mode());
        assertTrue(c.cornered());
    }

    @Test
    public void aBoxedInFightInACrowdNeverBails() {
        CombatCommit c = new CombatCommit();
        c.step(tick(NOW, 20, null, zombie(1, 2, NEVER), zombie(2, 2.5, NEVER), zombie(3, 2.8, NEVER)));
        // stand on the spot for the corner window: boxed in, it is a fight, and nothing bails a boxed in fight
        Event seen = Event.NONE;
        long now = NOW + 1;
        while (c.mode() == Mode.RUN && now < NOW + 200) {
            seen = c.step(tick(now++, 20, null, zombie(1, 2, NEVER), zombie(2, 2.5, NEVER), zombie(3, 2.8, NEVER)));
        }
        assertEquals(Event.RUN_TO_FIGHT, seen);
        assertTrue(c.cornered());
        for (long t = now; t < now + 100; t++) {
            Foe target = zombie(c.targetId(), 2, 1);
            assertEquals(Event.NONE, c.step(tick(t, 20, target, target, zombie(2, 2.5, 1), zombie(3, 2.8, 1))));
        }
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aCrowdThatIsBackOnUsAfterTheCooldownIsAnotherRun() {
        // known and on purpose: a clean getaway ends the run, and a crowd standing right where we need to be is a crowd again
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, zombie(1, 2, 3), zombie(2, 2, 3), zombie(3, 2, 3))));
        long now = NOW + 1;
        Event end = Event.NONE;
        while (end == Event.NONE && now < NOW + 200) {
            end = c.step(tick(now++, 20, 30, true, null));
        }
        assertEquals(Event.RUN_CLEAR, end);
        assertTrue(c.coolingDown(now));
        // the cooldown only listens to news, three of them in contact is news
        assertEquals(Event.RUN_START, c.step(tick(now + 20, 20, 30, true, null, zombie(1, 2, NEVER), zombie(2, 2, NEVER), zombie(3, 2, NEVER))));
        assertEquals(Why.CROWD, c.why());
        // but two in contact and one at five blocks is not
        CombatCommit again = new CombatCommit();
        again.step(tick(NOW, 20, null, zombie(1, 2, 3), zombie(2, 2, 3), zombie(3, 2, 3)));
        long later = NOW + 1;
        Event over = Event.NONE;
        while (over == Event.NONE && later < NOW + 200) {
            over = again.step(tick(later++, 20, 30, true, null));
        }
        assertEquals(Event.NONE, again.step(tick(later + 20, 20, 30, true, null, zombie(1, 2, NEVER), zombie(2, 2, NEVER), zombie(3, 5, NEVER))));
    }

    @Test
    public void unarmedDoesNotFistFightWhatIsInThePathEither() {
        Foe z = zombie(1, 2, NEVER);
        Way inWay = new Way(java.util.Set.of(1), true, 0);
        CombatCommit armed = new CombatCommit();
        assertEquals(Event.FIGHT_START, armed.step(new Tick(NOW, 20, 0, 0, List.of(z), null, inWay, true)));
        assertEquals(Why.IN_WAY, armed.why());
        CombatCommit bare = new CombatCommit();
        for (long now = NOW; now < NOW + 100; now++) {
            assertEquals(Event.NONE, bare.step(new Tick(now, 20, 0, 0, List.of(z), null, inWay, false)));
        }
        // it swings: now it is the unarmed run
        assertEquals(Event.RUN_START, bare.step(new Tick(NOW + 100, 20, 0, 0, List.of(zombie(1, 2, 0)), null, inWay, false)));
        assertEquals(Why.UNARMED, bare.why());
    }

    // ---- bare hands

    @Test
    public void unarmedAndHitRunsInsteadOfOpeningAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 17, 0, false, null, zombie(1, 2, 3))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.UNARMED, c.why());
    }

    @Test
    public void armedAndHitStillFights() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 17, 0, true, null, zombie(1, 2, 3))));
    }

    @Test
    public void unarmedAndMerelyNearbyChangesNothing() {
        // not hit, so nothing is asked of anybody: this is not the old unarmed run, a mob that has not touched us is scenery
        CombatCommit c = new CombatCommit();
        for (long now = NOW; now < NOW + 200; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 20, 0, false, null, zombie(1, 2, NEVER), skeleton(2, 9, NEVER))));
        }
    }

    @Test
    public void unarmedStillFightsWhenTheRunGoesNowhere() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 17, 0, false, null, zombie(1, 2, 3))));
        Event seen = Event.NONE;
        for (long now = NOW + 1; now < NOW + 200 && c.mode() == Mode.RUN; now++) {
            seen = c.step(tick(now, 17, 0, false, null, zombie(1, 2, now - NOW + 3)));
        }
        assertEquals(Event.RUN_TO_FIGHT, seen);
        assertEquals(Why.CORNERED, c.why());
    }

    // ---- a run that something keeps biting through

    // a run from a hurt bot, then a zombie in contact that keeps hitting while we move 0.3 blocks a tick
    private static Event bittenRun(CombatCommit c, boolean armed, long from, long to, float hp) {
        Event seen = Event.NONE;
        for (long now = from; now <= to; now++) {
            Foe z = zombie(1, 2, now - (NOW + 1));
            seen = c.step(tick(now, hp, (now - NOW) * 0.3, armed, null, z));
            if (seen != Event.NONE) return seen;
        }
        return seen;
    }

    @Test
    public void armedAndBittenForASecondWhileMovingTurnsToFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 8, null, zombie(1, 5, NEVER))));
        // first bite after the run began is on NOW + 1, the clock starts there and a second later it is a fight
        assertEquals(Event.NONE, bittenRun(c, true, NOW + 1, NOW + CombatCommit.BITTEN_TICKS, 8));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 1 + CombatCommit.BITTEN_TICKS, 8, 7, true, null,
                zombie(1, 2, CombatCommit.BITTEN_TICKS))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CHASED, c.why());
        assertEquals(1, c.targetId());
    }

    @Test
    public void theFightWeTurnedForCannotFlipBackToARunNextTick() {
        CombatCommit c = new CombatCommit();
        c.step(tick(NOW, 8, null, zombie(1, 5, NEVER)));
        assertEquals(Event.RUN_CHASED_FIGHT, bittenRun(c, true, NOW + 1, NOW + 100, 8));
        // at hp 5 with the zombie on us and a second one joining, still the same fight: it holds until the target is dead
        // (the turn was at NOW + 21, so this starts on the very next tick)
        long start = NOW + 22;
        for (long now = start; now < start + 300; now++) {
            Foe target = zombie(1, 2, 1);
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 5, target, target, zombie(2, 2.5, 1))));
        }
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_NEXT, c.step(tick(start + 300, 5, null, zombie(2, 2.5, 1))));
    }

    @Test
    public void unarmedAndBittenKeepsRunning() {
        CombatCommit c = new CombatCommit();
        c.step(tick(NOW, 8, 0, false, null, zombie(1, 5, NEVER)));
        assertEquals(Event.NONE, bittenRun(c, false, NOW + 1, NOW + 150, 8));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aHitFromBeforeTheRunDoesNotCountAsBeingBittenWhileRunning() {
        CombatCommit c = new CombatCommit();
        c.step(tick(NOW, 8, null, zombie(1, 2, 1)));
        // it hit us before we ran and has not since (sinceHit grows with the clock)
        for (long now = NOW + 1; now < NOW + 100; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 8, (now - NOW) * 0.3, true, null, zombie(1, 2, 1 + now - NOW))));
        }
    }

    @Test
    public void somethingBitingFromFurtherThanContactIsNotChasingUs() {
        CombatCommit c = new CombatCommit();
        c.step(tick(NOW, 8, null, zombie(1, 5, NEVER)));
        for (long now = NOW + 1; now < NOW + 100; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 8, (now - NOW) * 0.3, true, null, zombie(1, 3.5, now - (NOW + 1)))));
        }
    }

    @Test
    public void aHeavyHitterIsNeverTurnedOn() {
        CombatCommit c = new CombatCommit();
        Foe far = new Foe(1, 5, false, false, NEVER, Kind.HEAVY);
        c.step(tick(NOW, 8, null, far));
        for (long now = NOW + 1; now < NOW + 100; now++) {
            Foe heavy = new Foe(1, 2, false, false, now - (NOW + 1), Kind.HEAVY);
            assertEquals(Event.NONE, c.step(tick(now, 8, (now - NOW) * 0.3, true, null, heavy)));
        }
        assertEquals(Mode.RUN, c.mode());
    }

    // ---- longer walks

    @Test
    public void hurtByShootersThenCaughtByAFastBiterWhileRunning() {
        CombatCommit c = new CombatCommit();
        // two shooters have hit us and we are down to 10: that is a crowd while hurt, a run from the first tick
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, null, skeleton(1, 5, 2), skeleton(2, 4, 2))));
        assertEquals(Why.CROWD, c.why());
        // something that keeps pace catches up, hits us, and stays on us
        Event seen = Event.NONE;
        for (long now = NOW + 1; now < NOW + 100 && seen == Event.NONE; now++) {
            Foe spider = zombie(3, 2, 0);
            seen = c.step(tick(now, 7, (now - NOW) * 0.3, true, null, spider, skeleton(1, 12, NEVER)));
        }
        assertEquals(Event.RUN_CHASED_FIGHT, seen);
        assertEquals(3, c.targetId());
    }

    @Test
    public void emptyHandedAndHitAtSeventeenRunsAndThenCannotFistFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 17, 0, false, null, zombie(1, 2, 3))));
        assertEquals(Why.UNARMED, c.why());
        // it follows and bites all the way: moving, unarmed, the run holds rather than a fist fight
        assertEquals(Event.NONE, bittenRun(c, false, NOW + 1, NOW + 60, 12));
        assertEquals(Mode.RUN, c.mode());
    }
}
