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
import java.util.Set;
import org.junit.Test;

// a run that turned into a fight latches the runs off: the fight ending with a cube bouncing out past reach used to hand the
// same step to the low hp rule, which started a fresh run with a fresh origin and grace, which turned back into a fight...
public class CombatCommitLatchTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final long CAP = CombatCommit.RUN_CAP;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe cube(int id, double distance, long sinceHit) {
        return zombie(id, distance, sinceHit).withFast(true);
    }

    private static Foe warden(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.UNTOUCHABLE);
    }

    private static Tick tick(long now, float health, Foe... foes) {
        return new Tick(now, health, 0, 0, List.of(foes), null);
    }

    // two blocks back and forth every half second: moving, so never cornered, never stuck, never far
    private static Tick shuffle(long now, Foe... foes) {
        double x = ((now - NOW) / 10) % 2 == 0 ? 0 : 2;
        return new Tick(now, 7, x, 0, List.of(foes), null);
    }

    // a low hp run that never gets away from the zombie behind it for the whole cap, then turns round: a chased fight on zombie 1
    private static CombatCommit chasedFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, zombie(1, 5, NEVER))));
        for (long now = NOW + 1; now < NOW + CAP; now++) {
            assertEquals(Event.NONE, c.step(shuffle(now, zombie(1, 5, NEVER))));
        }
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + CAP, 7, zombie(1, 5, NEVER))));
        return c;
    }

    @Test
    public void theRunCapIsTwentyFiveSecondsAndNothingStretchesIt() {
        assertEquals(500, CombatCommit.RUN_CAP);
        chasedFight();
    }

    @Test
    public void aTargetDyingAtHpEightWithACubeFiveBlocksOutDoesNotStartANewRun() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        // the cube hit us a moment ago and bounced out to five blocks: not who the cornered pick (three blocks) chains to
        Foe bouncer = cube(2, 5, 20);
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8, bouncer)));
        assertEquals(Mode.NONE, c.mode());
        // and not a tick later: the cooldown sits it out. once it is over the latch lets its one low hp run through
        for (long now = dead + 1; now < dead + CombatCommit.COOLDOWN; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 8, bouncer)));
        }
        assertEquals(Event.RUN_START, c.step(tick(dead + CombatCommit.COOLDOWN, 8, bouncer)));
    }

    @Test
    public void theLatchLetsGoAfterTwoSecondsOfNothingWithinTwelve() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8)));
        long quiet = dead + 1;
        for (long now = quiet; now <= quiet + CombatCommit.RUN_CLEAR_TICKS; now++) {
            assertEquals(Event.NONE, c.step(tick(now, 8)));
        }
        long back = quiet + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(back, 8, zombie(3, 5, NEVER))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void somethingWithinTwelveRestartsTheClearClock() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8)));
        long start = dead + 1;
        for (long now = start; now < start + CombatCommit.RUN_CLEAR_TICKS - 1; now++) {
            c.step(tick(now, 8));
        }
        // a mob walks in at ten blocks just before the two seconds are up: the clock starts over
        long mid = start + CombatCommit.RUN_CLEAR_TICKS - 1;
        assertEquals(Event.NONE, c.step(tick(mid, 8, zombie(3, 10, NEVER))));
        // (the latch is still on, so this is its one break and not a fresh run)
        long after = start + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(after, 8, zombie(3, 5, NEVER))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aFullClearGivesTheBreakBack() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 7)));
        long again = dead + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(again, 7, zombie(3, 5, NEVER))));
        // out of sight and 24 away: the run ends clear, and the latch lets go with nothing within 12 for two seconds
        long now = again + 1;
        while (c.mode() == Mode.RUN && now < again + 300) {
            c.step(new Tick(now++, 7, 24, 0, List.of(), null));
        }
        assertEquals(Mode.NONE, c.mode());
        now += CombatCommit.COOLDOWN;
        // the same story again: a run that never shakes the zombie, the cap turns round, and the break is back
        long start = now;
        assertEquals(Event.RUN_START, c.step(tick(start, 7, zombie(1, 5, NEVER))));
        for (now = start + 1; now < start + CAP; now++) {
            assertEquals(Event.NONE, c.step(shuffle(now, zombie(1, 5, NEVER))));
        }
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(start + CAP, 7, zombie(1, 5, NEVER))));
        Foe target = zombie(1, 4, 3);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(start + CAP + 1, 6, 0, 0, List.of(target, zombie(2, 6, NEVER)), target)));
    }

    @Test
    public void aFastMobOnUsNeverBreaksTheLatch() {
        CombatCommit c = chasedFight();
        Foe target = zombie(1, 2, 3);
        // hp 5 with a cube that bit us three blocks away: it comes along on a run, so the fight stays
        assertEquals(Event.NONE, c.step(new Tick(NOW + CAP + 1, 5, 0, 0, List.of(target, cube(2, 3, 3)), target)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.NONE, c.step(new Tick(NOW + CAP + 2, 5, 0, 0, List.of(target, cube(2, 3, 4)), target)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aWardenGetsPastTheLatch() {
        CombatCommit c = chasedFight();
        long dead = NOW + CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(dead, 8)));
        assertEquals(Event.RUN_START, c.step(tick(dead + 1, 8, warden(4, 6))));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void resetClearsTheLatch() {
        CombatCommit c = chasedFight();
        c.reset();
        assertEquals(Event.RUN_START, c.step(tick(NOW + CAP + 50, 7, zombie(1, 5, NEVER))));
    }

    // ---- the hurt pair is gone

    @Test
    public void twoAttackersAtTenHpAreNotACrowd() {
        assertFalse(CombatCommit.crowded(10, List.of(zombie(1, 2, 3), zombie(2, 2.5, 3)), false));
        assertTrue(CombatCommit.crowded(10, List.of(zombie(1, 2, 3), zombie(2, 2.5, 3), zombie(3, 2, 3)), false));
    }

    // ---- fast mobs at the run decision

    @Test
    public void aCubeThatHitUsAtLowHpIsTheCorneredFightNotARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, cube(1, 3, 3))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CORNERED, c.why());
        assertTrue(c.cornered());
        assertEquals(1, c.targetId());
    }

    @Test
    public void aWardenWalkingUpOnAFastFightIsStillARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_TO_FIGHT, c.step(tick(NOW, 7, cube(1, 3, 3))));
        Foe target = cube(1, 3, 3);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + 1, 7, 0, 0, List.of(target, warden(2, 6)), target)));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aSlowZombieInTheSameSpotIsStillARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, zombie(1, 3, 3))));
    }

    @Test
    public void aFastOneThatNeverHitUsOrIsFarOrWeHaveNoWeaponStaysARun() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, cube(1, 3, NEVER))));
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 7, cube(1, CombatCommit.FAST_FIGHT_RANGE + 1, 3))));
        Tick bare = new Tick(NOW, 7, 0, 0, List.of(cube(1, 3, 3)), null, Way.NONE, false);
        assertEquals(Event.RUN_START, new CombatCommit().step(bare));
    }

    @Test
    public void aFightLowOnHpWithAFastOneOnUsKeepsFightingAndLatches() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, cube(1, 2, 3))));
        Foe target = cube(1, 2, 3);
        assertEquals(Event.NONE, c.step(new Tick(NOW + 1, 7, 0, 0, List.of(target), target)));
        assertEquals(Mode.FIGHT, c.mode());
        assertTrue(c.cornered());
        // it dies, another bounces out: no run
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 2, 7, cube(2, 5, 20))));
        assertEquals(Mode.NONE, c.mode());
    }

    // ---- the fight we pick wants a real weapon

    @Test
    public void anInTheWayFightNeedsASwordOrAnAxe() {
        Foe z = zombie(1, 2, NEVER);
        Tick pickOnly = new Tick(NOW, 20, 0, 0, List.of(z), null, new Way(Set.of(1), true, 0, false), true);
        assertEquals(Event.NONE, new CombatCommit().step(pickOnly));
        Tick sword = new Tick(NOW, 20, 0, 0, List.of(z), null, new Way(Set.of(1), true, 0, true), true);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(sword));
        assertEquals(Why.IN_WAY, c.why());
    }

    @Test
    public void aMobThatHitsUsIsStillAFightWithOnlyAPick() {
        Foe z = zombie(1, 2, 3);
        Tick pickOnly = new Tick(NOW, 20, 0, 0, List.of(z), null, new Way(Set.of(1), true, 0, false), true);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(pickOnly));
        assertEquals(Why.HIT, c.why());
    }
}
