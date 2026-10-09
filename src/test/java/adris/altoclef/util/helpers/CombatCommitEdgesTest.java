package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// the corners of the machine the main test does not walk: what a chained fight is allowed to chain to, a run that is not
// going anywhere, and the numbers the user picked. same style, foes are numbers
public class CombatCommitEdgesTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, float health, boolean armed, Foe target, boolean paused, Foe... foes) {
        return new Tick(now, health, armed, 0, 0, 3, List.of(foes), target, paused);
    }

    // a fight against zombie 1 that hit us, started at tick 100
    private static CombatCommit fighting() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 2, 3);
        assertEquals(Event.FIGHT_START, commit.step(tick(100, 20, true, null, false, z)));
        return commit;
    }

    @Test
    public void aDeadTargetDoesNotChainToSomethingWeCannotHit() {
        CombatCommit commit = fighting();
        // the weapon is gone and another zombie is on us: nothing to chain with, the fight is simply over
        Event event = commit.step(tick(110, 20, false, null, false, zombie(2, 2, 3)));
        assertEquals(Event.FIGHT_DEAD, event);
        assertEquals(Mode.NONE, commit.mode());
    }

    @Test
    public void aDeadTargetDoesNotChainToSomethingWeGaveUpOn() {
        CombatCommit commit = new CombatCommit();
        // a skeleton that hit us sits out of reach for ten seconds and is given up on
        Foe far = new Foe(2, 10, true, false, 3);
        assertEquals(Event.FIGHT_START, commit.step(tick(100, 20, true, null, false, far)));
        assertEquals(Event.FIGHT_STALLED, commit.step(tick(100 + CombatCommit.STALL_TICKS, 20, true, far, false, far)));
        // after the cooldown a fresh fight with zombie 1, then it dies while the ignored one stands in contact without
        // having hit us since
        long later = 100 + CombatCommit.STALL_TICKS + CombatCommit.COOLDOWN + 1;
        assertEquals(Event.FIGHT_START, commit.step(tick(later, 20, true, null, false, zombie(1, 2, 3))));
        Event event = commit.step(tick(later + 5, 20, true, null, false, zombie(2, 2, NEVER)));
        assertEquals(Event.FIGHT_DEAD, event);
    }

    @Test
    public void aCorneredFightDoesNotBailOnAWardenWalkingUp() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 1.5, 3);
        assertEquals(Event.RUN_START, commit.step(tick(100, 6, true, null, false, z)));
        // stuck on the spot with it on us for three seconds
        Event event = Event.NONE;
        for (long t = 101; t <= 101 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = commit.step(tick(t, 6, true, null, false, z));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertTrue(commit.cornered());
        // a warden walking up does not undo it, there is nowhere to run to
        Foe warden = new Foe(9, 5, false, false, NEVER, CombatCommit.Kind.UNTOUCHABLE);
        assertEquals(Event.NONE, commit.step(tick(400, 6, true, z, false, z, warden)));
        assertEquals(Mode.FIGHT, commit.mode());
    }

    @Test
    public void aCorneredFightNeverChainsIntoAWardenTouchingUs() {
        CombatCommit commit = new CombatCommit();
        Foe z = zombie(1, 1.5, 3);
        assertEquals(Event.RUN_START, commit.step(tick(100, 6, true, null, false, z)));
        Event event = Event.NONE;
        for (long t = 101; t <= 101 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = commit.step(tick(t, 6, true, null, false, z));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        // the zombie dies with a warden standing on us: the fight is over, nothing is chained to the warden
        Foe warden = new Foe(9, 2, false, false, NEVER, CombatCommit.Kind.UNTOUCHABLE);
        assertEquals(Event.FIGHT_DEAD, commit.step(tick(400, 6, true, null, false, warden)));
        assertEquals(Mode.NONE, commit.mode());
    }

    @Test
    public void aRunWithNobodyNearStillEndsAtTheCapShortOfFiftyBlocks() {
        CombatCommit commit = new CombatCommit();
        assertEquals(Event.RUN_START, commit.step(tick(100, 5, true, null, false, zombie(1, 6, NEVER))));
        // walked ten blocks and then nobody is there any more, and the ground goes on slowly: two blocks every three seconds
        // is nowhere near fifty and nowhere near stuck, so it is the cap that ends it
        Tick far = new Tick(101, 5, true, 10, 0, 3, List.of(), null, false);
        assertEquals(Event.NONE, commit.step(far));
        for (long t = 102; t < 100 + CombatCommit.RUN_CAP; t++) {
            double x = 10 + ((t - 101) / 60) * 2;
            assertEquals("tick " + t, Event.NONE, commit.step(new Tick(t, 5, true, x, 0, 3, List.of(), null, false)));
        }
        assertEquals(Event.RUN_CAP, commit.step(new Tick(100 + CombatCommit.RUN_CAP, 5, true, 40, 0, 3, List.of(), null, false)));
        assertEquals(Mode.NONE, commit.mode());
    }

    @Test
    public void aRunStandingShortOfFiftyBlocksWithNobodyNearIsStuckLongBeforeTheCap() {
        CombatCommit commit = new CombatCommit();
        assertEquals(Event.RUN_START, commit.step(tick(100, 5, true, null, false, zombie(1, 6, NEVER))));
        // the pathfinder has nowhere to take us and nothing is within eight
        for (long t = 101; t <= 101 + CombatCommit.RUN_STUCK_TICKS - 1; t++) {
            assertEquals("tick " + t, Event.NONE, commit.step(new Tick(t, 5, true, 10, 0, 3, List.of(), null, false)));
        }
        assertEquals(Event.RUN_STUCK, commit.step(new Tick(101 + CombatCommit.RUN_STUCK_TICKS, 5, true, 10, 0, 3, List.of(), null, false)));
        assertEquals(Mode.NONE, commit.mode());
        assertTrue(commit.coolingDown(101 + CombatCommit.RUN_STUCK_TICKS + 1));
    }

    @Test
    public void aShooterThatHitUsAtFifteenStartsAFightAtTheStepLevel() {
        CombatCommit commit = new CombatCommit();
        Foe skeleton = new Foe(7, 15, true, false, 4);
        assertEquals(Event.FIGHT_START, commit.step(tick(100, 18, true, null, false, skeleton)));
        assertEquals(Why.HIT, commit.why());
        assertEquals(7, commit.targetId());
        // a hair further is scenery
        CombatCommit other = new CombatCommit();
        assertEquals(Event.NONE, other.step(tick(100, 18, true, null, false, new Foe(7, 15.1, true, false, 4))));
    }

    // ---- a mob we gave up on that hits us again

    // a skeleton on a ledge: a fight against it, ten seconds of no progress, dropped
    private static CombatCommit droppedSkeleton() {
        CombatCommit commit = new CombatCommit();
        Foe skeleton = new Foe(2, 10, true, false, 3);
        assertEquals(Event.FIGHT_START, commit.step(tick(100, 20, true, null, false, skeleton)));
        assertEquals(Event.FIGHT_STALLED, commit.step(tick(100 + CombatCommit.STALL_TICKS, 20, true, skeleton, false, skeleton)));
        return commit;
    }

    private static final long DROPPED_AT = 100 + CombatCommit.STALL_TICKS;

    @Test
    public void theSameMobHittingUsAgainSoonAfterTheDropIsARunNotAFight() {
        CombatCommit commit = droppedSkeleton();
        assertTrue(commit.coolingDown(DROPPED_AT + 1));
        // a fresh arrow from the same skeleton, through the cooldown (a fresh hit is the one thing it listens to)
        assertEquals(Event.RUN_START, commit.step(tick(DROPPED_AT + 50, 20, true, null, false, new Foe(2, 10, true, false, 0))));
        assertEquals(Why.UNREACHABLE, commit.why());
        assertEquals(Mode.RUN, commit.mode());
        assertEquals(DROPPED_AT + 50, commit.startTick());
    }

    @Test
    public void theRunIsTheAnswerRightUpToTwentySecondsAfterTheDrop() {
        CombatCommit commit = droppedSkeleton();
        assertEquals(Event.RUN_START, commit.step(tick(DROPPED_AT + CombatCommit.REHIT_RUN, 20, true, null, false, new Foe(2, 10, true, false, 0))));
        assertEquals(Why.UNREACHABLE, commit.why());
    }

    @Test
    public void aHitMuchLaterIsAFreshStartWithThatMob() {
        CombatCommit commit = droppedSkeleton();
        assertEquals(Event.FIGHT_START, commit.step(tick(DROPPED_AT + CombatCommit.REHIT_RUN + 1, 20, true, null, false, new Foe(2, 10, true, false, 0))));
        assertEquals(Why.HIT, commit.why());
    }

    @Test
    public void aDifferentSkeletonHittingUsIsNotTheDroppedOne() {
        CombatCommit commit = droppedSkeleton();
        assertEquals(Event.FIGHT_START, commit.step(tick(DROPPED_AT + 150, 20, true, null, false, new Foe(9, 10, true, false, 0))));
        assertEquals(Why.HIT, commit.why());
    }

    @Test
    public void thePassingOfTheRunMakesItForgivenForTheNextTime() {
        CombatCommit commit = droppedSkeleton();
        assertEquals(Event.RUN_START, commit.step(tick(DROPPED_AT + 150, 20, true, null, false, new Foe(2, 10, true, false, 0))));
        // the run is over (nothing in sight), and later the same skeleton hits us: a fresh start, a fight
        long over = DROPPED_AT + 150 + CombatCommit.RUN_CAP;
        assertEquals(Event.RUN_CAP, commit.step(new Tick(over, 20, true, 0, 0, 3, List.of(), null, false)));
        assertEquals(Event.FIGHT_START, commit.step(tick(over + CombatCommit.COOLDOWN + 1, 20, true, null, false, new Foe(2, 10, true, false, 0))));
    }

    // ---- cornered, the second tier

    @Test
    public void theSecondTierFightsTheNearestMobWithinEightEvenUnarmed() {
        CombatCommit commit = new CombatCommit();
        Foe near = new Foe(5, 6, true, false, NEVER);
        Foe far = new Foe(6, 7.5, false, false, NEVER);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, false, 0, 0, 3, List.of(near, far), null, false)));
        Event event = Event.NONE;
        long t = 101;
        for (; t <= 101 + CombatCommit.CORNER_FAR_TICKS - 1 && event == Event.NONE; t++) {
            event = commit.step(new Tick(t, 6, false, 0, 0, 3, List.of(near, far), null, false));
        }
        assertEquals(Event.NONE, event);
        assertEquals(Event.RUN_TO_FIGHT, commit.step(new Tick(t, 6, false, 0, 0, 3, List.of(near, far), null, false)));
        assertEquals(5, commit.targetId());
        assertTrue(commit.cornered());
    }

    // hurt, boxed in, a skeleton across the water: the second tier tries a fight, the fight cannot get anywhere, and the
    // skeleton is not picked again every six seconds with a run in between
    @Test
    public void aSkeletonAcrossTheWaterIsOnlyTriedOnce() {
        CombatCommit commit = new CombatCommit();
        Foe skeleton = new Foe(5, 7, true, false, NEVER);
        List<Foe> foes = List.of(skeleton);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, true, 0, 0, 3, foes, null, false)));
        long t = 101;
        Event event = Event.NONE;
        for (; event == Event.NONE; t++) {
            event = commit.step(new Tick(t, 6, true, 0, 0, 3, foes, null, false));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertEquals(5, commit.targetId());
        // ten seconds of getting nowhere
        long fightStart = t - 1;
        Event end = Event.NONE;
        for (; end == Event.NONE; t++) {
            end = commit.step(new Tick(t, 6, true, 0, 0, 3, foes, skeleton, false));
        }
        assertEquals(Event.FIGHT_STALLED, end);
        assertEquals(fightStart + CombatCommit.STALL_TICKS, t - 1);
        assertTrue(commit.isIgnored(5));
        // hurt with it still within 8: back to a run straight away, cooldown or not
        assertEquals(Event.RUN_START, commit.step(new Tick(t, 6, true, 0, 0, 3, foes, null, false)));
        assertEquals(Why.LOW_HP, commit.why());
        // and stuck on the spot for as long as we like, the second tier has nobody left to try
        for (long later = t + 1; later < t + 3 * CombatCommit.CORNER_FAR_TICKS; later++) {
            assertEquals("tick " + later, Event.NONE, commit.step(new Tick(later, 6, true, 0, 0, 3, foes, null, false)));
        }
        assertEquals(Mode.RUN, commit.mode());
    }

    @Test
    public void theSecondTierSkipsCreepersButAZombieOnTopOfUsIsAlwaysFairGame() {
        CombatCommit commit = new CombatCommit();
        Foe creeper = new Foe(5, 6, false, true, NEVER);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, true, 0, 0, 3, List.of(creeper), null, false)));
        for (long t = 101; t < 101 + 2 * CombatCommit.CORNER_FAR_TICKS; t++) {
            assertEquals(Event.NONE, commit.step(new Tick(t, 6, true, 0, 0, 3, List.of(creeper), null, false)));
        }
        // a zombie that walks up to us gets the first tier's fight at once, the window has been open all this time
        Foe zombie = new Foe(6, 2, false, false, NEVER);
        assertEquals(Event.RUN_TO_FIGHT, commit.step(new Tick(700, 6, true, 0, 0, 3, List.of(creeper, zombie), null, false)));
        assertEquals(6, commit.targetId());
    }

    @Test
    public void theFirstTierStillFiresAtThreeSecondsWithSomethingOnTopOfUs() {
        CombatCommit commit = new CombatCommit();
        Foe onUs = new Foe(5, 2, false, false, NEVER);
        assertEquals(Event.RUN_START, commit.step(new Tick(100, 6, true, 0, 0, 3, List.of(onUs), null, false)));
        assertEquals(Event.NONE, commit.step(new Tick(101, 6, true, 0, 0, 3, List.of(onUs), null, false)));
        assertEquals(Event.RUN_TO_FIGHT, commit.step(new Tick(101 + CombatCommit.CORNER_TICKS, 6, true, 0, 0, 3, List.of(onUs), null, false)));
    }

    @Test
    public void theNumbersTheUserPicked() {
        assertEquals(50, CombatCommit.RUN_DISTANCE, 0);
        assertEquals(16, CombatCommit.RUN_CLEAR, 0);
        assertEquals(2 * 20, CombatCommit.RUN_CLEAR_TICKS);
        assertEquals(45 * 20, CombatCommit.RUN_CAP);
        assertEquals(5 * 20, CombatCommit.COOLDOWN);
        assertEquals(10 * 20, CombatCommit.STALL_TICKS);
        assertEquals(3 * 20, CombatCommit.CORNER_TICKS);
        assertEquals(8, CombatCommit.FLEE_HP, 0);
        assertEquals(6, CombatCommit.MELEE_ENGAGE, 0);
        assertEquals(15, CombatCommit.SHOOTER_ENGAGE, 0);
        assertEquals(24, CombatCommit.LOST_RANGE, 0);
        assertEquals(5 * 20, CombatCommit.RUN_STUCK_TICKS);
    }
}
