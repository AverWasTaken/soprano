package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Trigger;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

// the whole commitment machine without a world: foes are numbers, ticks are records, nothing here needs minecraft
public class CombatCommitTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    // ten seconds is right on the edge of HIT_MEMORY: still remembered, and nobody's idea of news
    private static final long TEN_SECONDS = 200;
    // a hit this old has been forgotten, and a far target that hit us this long ago is not hitting us now
    private static final long LONG_AGO = 500;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe zombie(int id, double distance) {
        return zombie(id, distance, NEVER);
    }

    private static Foe skeleton(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit);
    }

    private static Foe skeleton(int id, double distance) {
        return skeleton(id, distance, NEVER);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, false, true, NEVER);
    }

    // armed, standing at the origin, three angry mobs make a crowd. target is the foe we are fighting looked up on its own
    private static Tick tick(long now, float health, Foe target, Foe... foes) {
        return new Tick(now, health, true, 0, 0, 3, List.of(foes), target, false);
    }

    private static Tick unarmed(Tick t) {
        return new Tick(t.now(), t.health(), false, t.x(), t.z(), t.crowdSize(), t.foes(), t.target(), t.paused());
    }

    private static Tick at(Tick t, double x, double z) {
        return new Tick(t.now(), t.health(), t.armed(), x, z, t.crowdSize(), t.foes(), t.target(), t.paused());
    }

    private static Tick paused(Tick t) {
        return new Tick(t.now(), t.health(), t.armed(), t.x(), t.z(), t.crowdSize(), t.foes(), t.target(), true);
    }

    private static Tick crowdOf(Tick t, int size) {
        return new Tick(t.now(), t.health(), t.armed(), t.x(), t.z(), size, t.foes(), t.target(), t.paused());
    }

    private static Foe heavy(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.HEAVY);
    }

    private static Foe heavy(int id, double distance) {
        return heavy(id, distance, NEVER);
    }

    private static Foe warden(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.UNTOUCHABLE);
    }

    private static Foe blaze(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit, Kind.FLYER);
    }

    // a fight tick where the target is the only thing there is
    private static Tick facing(long now, Foe foe) {
        return tick(now, 20, foe, foe);
    }

    // a run tick: healthy, nobody targeted, standing at x, z
    // two blocks to the side every half second: always somewhere new, never far from the start
    private static double shuffle(long now) {
        return ((now - NOW) / 10) % 2 == 0 ? 0 : 2;
    }

    private static Tick runTick(long now, double x, double z, Foe... foes) {
        return at(tick(now, 20, null, foes), x, z);
    }

    private static long sinceHit(long now, long hitAt) {
        return hitAt == NEVER ? NEVER : now - hitAt;
    }

    private static void assertTrigger(Why why, int id, Trigger actual) {
        assertNotNull(actual);
        assertEquals(why, actual.why());
        assertEquals(id, actual.foe().id());
    }

    // a fight that began at `now` on this foe, healthy and armed, standing at the origin
    private static CombatCommit fightOn(long now, Foe foe) {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(facing(now, foe)));
        return c;
    }

    private static CombatCommit fightOn(Foe foe) {
        return fightOn(NOW, foe);
    }

    // a run that began at `now` from x, z. hurt with a zombie at eight blocks is the cheapest way in, nothing else about it
    // matters (the zombie is gone from every tick after)
    private static CombatCommit runFrom(long now, double x, double z) {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(at(tick(now, 8, null, zombie(900, 8)), x, z)));
        return c;
    }

    private static final long WON_AT = NOW + 10;

    // a fight on zombie 1 that was won ten ticks in, nobody else around. the cooldown runs from WON_AT
    private static CombatCommit wonFight() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(WON_AT, 20, null)));
        return c;
    }

    // every tick from..to must be a quiet one
    private static void holds(CombatCommit c, long from, long to, LongFunction<Tick> scene) {
        for (long now = from; now <= to; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(scene.apply(now)));
        }
    }

    // the target stands at this distance until the stall clock gives up on it (it hit us at hitAt, or never)
    private static void standUntilStalled(CombatCommit c, long start, double distance, long hitAt) {
        holds(c, start + 1, start + CombatCommit.STALL_TICKS - 1, now -> facing(now, zombie(1, distance, sinceHit(now, hitAt))));
        long last = start + CombatCommit.STALL_TICKS;
        assertEquals(Event.FIGHT_STALLED, c.step(facing(last, zombie(1, distance, sinceHit(last, hitAt)))));
    }

    // ---- the numbers

    @Test
    public void theNumbersTheUserAskedForDidNotDrift() {
        assertEquals(6, CombatCommit.MELEE_ENGAGE, 0);
        assertEquals(15, CombatCommit.SHOOTER_ENGAGE, 0);
        assertEquals(24, CombatCommit.RUN_DISTANCE, 0);
        assertEquals(12, CombatCommit.RUN_CLEAR, 0);
        assertEquals(2 * 20, CombatCommit.RUN_CLEAR_TICKS);
        assertEquals(25 * 20, CombatCommit.RUN_CAP);
        assertEquals(8f, CombatCommit.FLEE_HP, 0f);
        assertEquals(3, CombatCommit.CONTACT, 0);
        assertEquals(5 * 20, CombatCommit.COOLDOWN);
        // "it hit us" is remembered ten seconds, which is what the ten-second-old hits further down lean on
        assertEquals(10 * 20, CombatCommit.HIT_MEMORY);
    }

    // ---- the trigger rule

    @Test
    public void aSkeletonThatNeverHitUsTriggersNothing() {
        for (double d : new double[] {12, 5, 2}) {
            assertNull("at " + d, CombatCommit.trigger(20, List.of(skeleton(1, d)), false));
        }
    }

    @Test
    public void aZombieThatNeverHitUsIsScenery() {
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 5)), false));
    }

    @Test
    public void aZombieInContactIsAContactTrigger() {
        assertTrigger(Why.CONTACT, 1, CombatCommit.trigger(20, List.of(zombie(1, 3.0)), false));
    }

    @Test
    public void aZombieJustOutsideContactIsScenery() {
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 3.1)), false));
    }

    @Test
    public void aZombieThatHitUsCountsUpToSixBlocks() {
        assertTrigger(Why.HIT, 1, CombatCommit.trigger(20, List.of(zombie(1, 6.0, 10)), false));
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 6.1, 10)), false));
    }

    @Test
    public void aShooterThatHitUsCountsUpToFifteenBlocks() {
        assertTrigger(Why.HIT, 1, CombatCommit.trigger(20, List.of(skeleton(1, 15.0, 10)), false));
        assertNull(CombatCommit.trigger(20, List.of(skeleton(1, 15.1, 10)), false));
    }

    @Test
    public void aHitIsRememberedForExactlyTheHitMemory() {
        assertTrigger(Why.HIT, 1, CombatCommit.trigger(20, List.of(zombie(1, 4, CombatCommit.HIT_MEMORY)), false));
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 4, CombatCommit.HIT_MEMORY + 1)), false));
    }

    @Test
    public void aCreeperInContactThatNeverHitUsIsNotAContactTrigger() {
        assertNull(CombatCommit.trigger(20, List.of(creeper(1, 2)), false));
    }

    @Test
    public void aShooterInContactThatNeverHitUsIsNotAContactTrigger() {
        assertNull(CombatCommit.trigger(20, List.of(skeleton(1, 2)), false));
    }

    @Test
    public void beingHurtWithAnythingWithinEightIsAReasonToLeave() {
        // a skeleton, so it is the hp rule talking and not contact
        assertTrigger(Why.LOW_HP, 1, CombatCommit.trigger(8f, List.of(skeleton(1, 7)), false));
        assertTrigger(Why.LOW_HP, 1, CombatCommit.trigger(8f, List.of(skeleton(1, 8.0)), false));
    }

    @Test
    public void justAboveTheFleeLineIsNotHurt() {
        assertNull(CombatCommit.trigger(8.5f, List.of(skeleton(1, 7)), false));
    }

    @Test
    public void hurtButEverythingIsFurtherThanEightIsNotAReasonToLeave() {
        assertNull(CombatCommit.trigger(8f, List.of(skeleton(1, 8.1)), false));
    }

    @Test
    public void theSameFoeAtFullHealthTriggersNothing() {
        assertNull(CombatCommit.trigger(20, List.of(skeleton(1, 7)), false));
    }

    @Test
    public void theFoeThatHitUsBeatsANearerOneThatDidNot() {
        Trigger t = CombatCommit.trigger(20, List.of(zombie(2, 2), zombie(1, 5, 10)), false);
        assertTrigger(Why.HIT, 1, t);
    }

    @Test
    public void amongSeveralThatHitUsTheNearestWins() {
        // nearest is last on purpose, so a "first one wins" loop cannot pass by luck
        Trigger t = CombatCommit.trigger(20, List.of(zombie(1, 5, 10), zombie(3, 6, 10), zombie(2, 4, 10)), false);
        assertTrigger(Why.HIT, 2, t);
    }

    @Test
    public void amongSeveralInContactTheNearestWins() {
        Trigger t = CombatCommit.trigger(20, List.of(zombie(1, 2.5), zombie(2, 1), zombie(3, 2)), false);
        assertTrigger(Why.CONTACT, 2, t);
    }

    @Test
    public void theCooldownOnlyListensToAHitFromJustNow() {
        assertTrigger(Why.HIT, 1, CombatCommit.trigger(20, List.of(zombie(1, 4, CombatCommit.FRESH)), true));
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 4, CombatCommit.FRESH + 1)), true));
    }

    @Test
    public void theCooldownIgnoresContact() {
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 2)), true));
    }

    // being hurt with something close is never old news, the cooldown does not mute it
    @Test
    public void theCooldownDoesNotMuteLowHp() {
        assertTrigger(Why.LOW_HP, 1, CombatCommit.trigger(5f, List.of(skeleton(1, 5)), true));
        assertNull(CombatCommit.trigger(9f, List.of(skeleton(1, 5)), true));
        assertNull(CombatCommit.trigger(5f, List.of(skeleton(1, 8.1)), true));
    }

    @Test
    public void aFreshHitFromOutOfReachIsNotNewsEither() {
        assertNull(CombatCommit.trigger(20, List.of(zombie(1, 6.1, 0)), true));
        assertNull(CombatCommit.trigger(20, List.of(skeleton(1, 15.1, 0)), true));
    }

    @Test
    public void aCrowdIsEveryoneWithinSixIncludingShootersAndCreepers() {
        assertEquals(3, CombatCommit.crowd(List.of(zombie(1, 6), skeleton(2, 4), creeper(3, 1), zombie(4, 6.1))));
    }

    // ---- NONE to FIGHT

    @Test
    public void scenerySitsThereForeverAndNothingHappens() {
        CombatCommit c = new CombatCommit();
        holds(c, NOW, NOW + 300, now -> tick(now, 20, null, skeleton(1, 5), zombie(2, 5), creeper(3, 4)));
        assertEquals(Mode.NONE, c.mode());
        assertFalse(c.coolingDown(NOW + 300));
    }

    @Test
    public void aZombieThatHitUsStartsAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 18, null, zombie(7, 5, 10))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(7, c.targetId());
        assertEquals(Why.HIT, c.why());
        assertFalse(c.cornered());
        assertEquals(NOW, c.startTick());
    }

    @Test
    public void aZombieInContactStartsAFightForContact() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 18, null, zombie(7, 2))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(7, c.targetId());
        assertEquals(Why.CONTACT, c.why());
    }

    // ---- NONE to RUN

    @Test
    public void anEmptyHandRunsInsteadOfFighting() {
        CombatCommit c = new CombatCommit();
        Tick t = at(unarmed(tick(NOW, 20, null, zombie(7, 5, 10))), 12.5, -40.5);
        assertEquals(Event.RUN_START, c.step(t));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.UNARMED, c.why());
        assertEquals(-1, c.targetId());
        assertEquals(12.5, c.originX(), 0);
        assertEquals(-40.5, c.originZ(), 0);
        assertEquals(NOW, c.startTick());
    }

    @Test
    public void beingHurtAndHitRuns() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(at(tick(NOW, 8f, null, zombie(7, 5, 10)), 3, 4)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
        assertEquals(3, c.originX(), 0);
        assertEquals(4, c.originZ(), 0);
    }

    @Test
    public void beingHurtWithAMobNearbyRunsEvenIfNothingHitUs() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 8f, null, skeleton(7, 7))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aCrowdOfThreeWithinSixRuns() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, zombie(1, 4, 10), zombie(2, 5), zombie(3, 5.5))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.CROWD, c.why());
    }

    @Test
    public void twoWithinSixAndAThirdAtSevenIsStillAFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, zombie(1, 4, 10), zombie(2, 5), zombie(3, 7))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theCrowdSizeIsASetting() {
        CombatCommit c = new CombatCommit();
        Tick t = crowdOf(tick(NOW, 20, null, zombie(1, 4, 10), zombie(2, 5), zombie(3, 5.5)), 4);
        assertEquals(Event.FIGHT_START, c.step(t));
    }

    @Test
    public void theWorstReasonIsTheOneThatGetsReported() {
        Foe[] mob = {zombie(1, 4, 10), zombie(2, 5), zombie(3, 5.5)};
        CombatCommit hurtInACrowd = new CombatCommit();
        assertEquals(Event.RUN_START, hurtInACrowd.step(tick(NOW, 8f, null, mob)));
        assertEquals(Why.LOW_HP, hurtInACrowd.why());

        // a heavy hitter in the pile is the worst of it, it names the run before the hp does
        CombatCommit nasty = new CombatCommit();
        assertEquals(Event.RUN_START, nasty.step(tick(NOW, 8f, null, mob[0], mob[1], heavy(4, 5))));
        assertEquals(Why.HEAVY, nasty.why());
    }

    // ---- FIGHT

    @Test
    public void aFightHoldsThroughAnHpWobbleAboveTheLine() {
        CombatCommit c = fightOn(zombie(7, 2, 0));
        float[] wobble = {20, 9, 12, 9};
        // the target is in contact the whole time, so it is not a stall either
        holds(c, NOW + 1, NOW + 400, now -> {
            Foe z = zombie(7, 2, now - NOW);
            return tick(now, wobble[(int) (now % 4)], z, z);
        });
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(7, c.targetId());
    }

    @Test
    public void aFightBailsToARunAtExactlyTheFleeLine() {
        CombatCommit c = fightOn(zombie(7, 2, 0));
        Foe z = zombie(7, 2, 5);
        assertEquals(Event.NONE, c.step(at(tick(NOW + 5, 8.5f, z, z), 15, -20)));
        assertEquals(Event.FIGHT_TO_RUN, c.step(at(tick(NOW + 6, 8f, z, z), 15, -20)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
        assertEquals(15, c.originX(), 0);
        assertEquals(-20, c.originZ(), 0);
        assertEquals(NOW + 6, c.startTick());
        assertEquals(-1, c.targetId());
    }

    @Test
    public void aFightBailsToARunWhenAThirdMobJoins() {
        CombatCommit c = fightOn(zombie(7, 2, 0));
        Foe z = zombie(7, 2, 5);
        assertEquals(Event.FIGHT_TO_RUN, c.step(at(tick(NOW + 5, 20, z, z, zombie(8, 4), zombie(9, 5)), 6, 7)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.CROWD, c.why());
        assertEquals(6, c.originX(), 0);
        assertEquals(7, c.originZ(), 0);
    }

    @Test
    public void twoMobsAndAThirdAtSevenDoNotBailAFight() {
        CombatCommit c = fightOn(zombie(7, 2, 0));
        Foe z = zombie(7, 2, 5);
        assertEquals(Event.NONE, c.step(tick(NOW + 5, 20, z, z, zombie(8, 4), zombie(9, 7))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aHeavyHitterWalkingUpBailsAFightAtTenHp() {
        CombatCommit c = fightOn(zombie(7, 2, 0));
        Foe z = zombie(7, 2, 5);
        // at 11 the zombie fight carries on with a hoglin at five blocks
        assertEquals(Event.NONE, c.step(tick(NOW + 4, 11, z, z, heavy(8, 5))));
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 5, 10, z, z, heavy(8, 5))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aFightNeverFlipsToANearerFoeThatAlsoHitUs() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        // the nearer one hits us over and over, the target stays the target
        holds(c, NOW + 1, NOW + 40, now -> {
            Foe target = zombie(1, 5, now - NOW);
            return tick(now, 20, target, target, zombie(2, 2, 0));
        });
        assertEquals(1, c.targetId());
        assertEquals(Mode.FIGHT, c.mode());
    }

    // ---- the end of a fight

    @Test
    public void aDeadTargetWithNobodyNearIsAWonFightWithACooldown() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(WON_AT, 20, null)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals(Why.NONE, c.why());
        assertEquals(-1, c.targetId());
        assertTrue(c.coolingDown(WON_AT));
        assertTrue(c.coolingDown(WON_AT + CombatCommit.COOLDOWN - 1));
        assertFalse(c.coolingDown(WON_AT + CombatCommit.COOLDOWN));
    }

    @Test
    public void aDeadTargetWhileHurtButAloneIsStillAWonFight() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(WON_AT, 6f, null, zombie(9, 20))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aDeadTargetWithHpOnTheLineAndAFoeJustPastEightIsAWonFight() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(WON_AT, 8f, null, skeleton(9, 8.1))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aDeadTargetWithAZombieInContactCarriesStraightOn() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(WON_AT, 20, null, zombie(2, 2))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(2, c.targetId());
        assertEquals(Why.CONTACT, c.why());
        assertFalse(c.cornered());
        assertEquals(WON_AT, c.startTick());
        // no gap in between means no cooldown either
        assertFalse(c.coolingDown(WON_AT));
    }

    @Test
    public void aDeadTargetWithAZombieThatHitUsCarriesStraightOnAsAHit() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_NEXT, c.step(tick(WON_AT, 20, null, zombie(2, 5, 30))));
        assertEquals(2, c.targetId());
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void aDeadTargetStillListedAmongTheFoesIsNotItsOwnNextTarget() {
        CombatCommit c = fightOn(zombie(1, 2, 0));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(WON_AT, 20, null, zombie(1, 2, 3))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aDeadTargetWhileHurtWithAFoeWithinEightIsARun() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        assertEquals(Event.FIGHT_TO_RUN, c.step(at(tick(WON_AT, 8f, null, zombie(2, 6)), 9, 9)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
        assertEquals(9, c.originX(), 0);
        assertEquals(9, c.originZ(), 0);
    }

    // ---- stalls

    @Test
    public void aTargetThatNeverGetsCloserStallsAfterTenSeconds() {
        CombatCommit c = fightOn(skeleton(3, 10, 0));
        holds(c, NOW + 1, NOW + CombatCommit.STALL_TICKS - 1, now -> facing(now, skeleton(3, 10, LONG_AGO)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + CombatCommit.STALL_TICKS, skeleton(3, 10, LONG_AGO))));
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.isIgnored(3));
        assertTrue(c.coolingDown(NOW + CombatCommit.STALL_TICKS));
    }

    @Test
    public void closingInResetsTheStallClock() {
        CombatCommit c = fightOn(skeleton(3, 10, 0));
        holds(c, NOW + 1, NOW + 149, now -> facing(now, skeleton(3, 10, LONG_AGO)));
        // a whole block closer, that is progress
        holds(c, NOW + 150, NOW + 150 + CombatCommit.STALL_TICKS - 1, now -> facing(now, skeleton(3, 9, LONG_AGO)));
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + 150 + CombatCommit.STALL_TICKS, skeleton(3, 9, LONG_AGO))));
    }

    @Test
    public void closingInByExactlyTheProgressStepIsNotProgress() {
        CombatCommit c = fightOn(skeleton(3, 10, 0));
        // 10 to 9.5 is PROGRESS exactly, and it has to be more than that
        holds(c, NOW + 1, NOW + CombatCommit.STALL_TICKS - 1, now -> facing(now, skeleton(3, 10 - CombatCommit.PROGRESS, LONG_AGO)));
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + CombatCommit.STALL_TICKS, skeleton(3, 10 - CombatCommit.PROGRESS, LONG_AGO))));
    }

    @Test
    public void aTargetInContactNeverStalls() {
        CombatCommit c = fightOn(zombie(1, 2, 0));
        holds(c, NOW + 1, NOW + 5 * CombatCommit.STALL_TICKS, now -> facing(now, zombie(1, 3.0, 5)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aTargetJustPastContactStalls() {
        CombatCommit c = fightOn(zombie(1, 2, 0));
        holds(c, NOW + 1, NOW + CombatCommit.STALL_TICKS - 1, now -> facing(now, zombie(1, 3.1, 5)));
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + CombatCommit.STALL_TICKS, zombie(1, 3.1, 5))));
    }

    @Test
    public void aTargetThatRunsOffIsNotCreditedAsProgress() {
        CombatCommit c = fightOn(zombie(1, 4, 0));
        holds(c, NOW + 1, NOW + 99, now -> facing(now, zombie(1, 4, 5)));
        // it ran off, which re-baselines the distance and does not buy it any time
        holds(c, NOW + 100, NOW + CombatCommit.STALL_TICKS - 1, now -> facing(now, zombie(1, 10, 5)));
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + CombatCommit.STALL_TICKS, zombie(1, 10, 5))));
    }

    @Test
    public void afterARunOffTheNewBaselineIsWhatItHasToBeat() {
        CombatCommit c = fightOn(zombie(1, 4, 0));
        holds(c, NOW + 1, NOW + 49, now -> facing(now, zombie(1, 4, 5)));
        holds(c, NOW + 50, NOW + 59, now -> facing(now, zombie(1, 10, 5)));
        // closing from 10 to 9 is progress against the new baseline (against the old one it would be nothing)
        holds(c, NOW + 60, NOW + 60 + CombatCommit.STALL_TICKS - 1, now -> facing(now, zombie(1, 9, 5)));
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + 60 + CombatCommit.STALL_TICKS, zombie(1, 9, 5))));
    }

    @Test
    public void aStalledZombieThatHitUsStaysIgnoredUntilItHitsAgain() {
        CombatCommit c = fightOn(zombie(1, 6, 0));
        standUntilStalled(c, NOW, 6, NOW);
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        assertTrue(c.isIgnored(1));
        // it walks into reach with the same old hit, through the cooldown and well after it
        holds(c, gaveUp + 1, gaveUp + 149, now -> tick(now, 20, null, zombie(1, 2, now - NOW)));
        assertEquals(Mode.NONE, c.mode());
        // a hit on the very tick we gave up is not news
        assertEquals(Event.NONE, c.step(tick(gaveUp + 150, 20, null, zombie(1, 2, 150))));
        // a hit after it is, and it is out of reach and still hitting: that is a run, not another try
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + 150, 20, null, zombie(1, 2, 149))));
        assertEquals(Why.UNREACHABLE, c.why());
        assertFalse(c.isIgnored(1));
    }

    @Test
    public void aStalledShooterWithAnArrowStillRememberedStaysIgnoredToo() {
        CombatCommit c = fightOn(skeleton(3, 10, 0));
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        long arrowAt = gaveUp - 5;
        holds(c, NOW + 1, gaveUp - 1, now -> facing(now, skeleton(3, 10, LONG_AGO)));
        assertEquals(Event.FIGHT_STALLED, c.step(facing(gaveUp, skeleton(3, 10, 5))));
        // that arrow is still inside the hit memory at ten blocks, which trigger() alone would call a hit
        assertTrigger(Why.HIT, 3, CombatCommit.trigger(20, List.of(skeleton(3, 10, 105)), false));
        holds(c, gaveUp + 1, gaveUp + 150, now -> tick(now, 20, null, skeleton(3, 10, now - arrowAt)));
        assertEquals(Mode.NONE, c.mode());
        // a new arrow after we wrote it off is news, and the same skeleton again is a run
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + 151, 20, null, skeleton(3, 10, 0))));
        assertEquals(Why.UNREACHABLE, c.why());
    }

    @Test
    public void aStalledZombieThatNeverHitUsStaysIgnoredInContact() {
        CombatCommit c = fightOn(zombie(1, 3.0));
        standUntilStalled(c, NOW, 5, NEVER);
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        holds(c, gaveUp + 1, gaveUp + 300, now -> tick(now, 20, null, zombie(1, 2)));
        assertEquals(Mode.NONE, c.mode());
        // and a hit that lands now is news (and a run, it is the one we could not get to)
        assertEquals(Event.RUN_START, c.step(tick(gaveUp + 301, 20, null, zombie(1, 2, 0))));
    }

    @Test
    public void anIgnoredMobIsForgivenEventually() {
        CombatCommit c = fightOn(zombie(1, 3.0));
        standUntilStalled(c, NOW, 5, NEVER);
        long gaveUp = NOW + CombatCommit.STALL_TICKS;
        assertEquals(Event.NONE, c.step(tick(gaveUp + 5000, 20, null, zombie(1, 2))));
        assertEquals(Event.FIGHT_START, c.step(tick(gaveUp + 6001, 20, null, zombie(1, 2))));
        assertEquals(Why.CONTACT, c.why());
    }

    @Test
    public void anIgnoredMobStillCountsForLowHp() {
        CombatCommit c = fightOn(zombie(1, 3.0));
        standUntilStalled(c, NOW, 5, NEVER);
        long later = NOW + CombatCommit.STALL_TICKS + CombatCommit.COOLDOWN + 50;
        // leaving is not a fight with that one mob, so it does not matter that we wrote it off
        assertEquals(Event.RUN_START, c.step(tick(later, 8f, null, zombie(1, 2))));
        assertEquals(Why.LOW_HP, c.why());
    }

    // ---- lost targets

    @Test
    public void aFarTargetThatStaysQuietIsLostAfterThreeSeconds() {
        CombatCommit c = fightOn(skeleton(3, 15, 0));
        long farFrom = NOW + 1;
        holds(c, farFrom, farFrom + CombatCommit.LOST_TICKS - 1, now -> facing(now, skeleton(3, 30, LONG_AGO)));
        assertEquals(Event.FIGHT_LOST, c.step(facing(farFrom + CombatCommit.LOST_TICKS, skeleton(3, 30, LONG_AGO))));
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.coolingDown(farFrom + CombatCommit.LOST_TICKS));
        assertFalse(c.isIgnored(3));
    }

    @Test
    public void aFarTargetThatKeepsHittingUsHoldsTheClock() {
        CombatCommit c = fightOn(skeleton(3, 15, 0));
        // an arrow exactly LOST_TICKS ago is still "lately"
        holds(c, NOW + 1, NOW + 120, now -> facing(now, skeleton(3, 30, CombatCommit.LOST_TICKS)));
        assertEquals(Mode.FIGHT, c.mode());
        // one more tick of quiet and the clock that was waiting all along fires at once
        assertEquals(Event.FIGHT_LOST, c.step(facing(NOW + 121, skeleton(3, 30, CombatCommit.LOST_TICKS + 1))));
    }

    @Test
    public void aTargetThatComesBackRestartsTheLostClock() {
        CombatCommit c = fightOn(skeleton(3, 15, 0));
        holds(c, NOW + 1, NOW + 50, now -> facing(now, skeleton(3, 30, LONG_AGO)));
        holds(c, NOW + 51, NOW + 51, now -> facing(now, skeleton(3, 20, LONG_AGO)));
        // the old clock would have fired at NOW + 61, the new one starts over
        holds(c, NOW + 52, NOW + 52 + CombatCommit.LOST_TICKS - 1, now -> facing(now, skeleton(3, 30, LONG_AGO)));
        assertEquals(Event.FIGHT_LOST, c.step(facing(NOW + 52 + CombatCommit.LOST_TICKS, skeleton(3, 30, LONG_AGO))));
    }

    @Test
    public void aTargetExactlyAtTheLostRangeIsNotFar() {
        CombatCommit c = fightOn(skeleton(3, 15, 0));
        holds(c, NOW + 1, NOW + 150, now -> facing(now, skeleton(3, CombatCommit.LOST_RANGE, LONG_AGO)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    // ---- paused ticks

    @Test
    public void pausedTicksDoNotRunTheStallClock() {
        CombatCommit c = fightOn(skeleton(3, 10, 0));
        holds(c, NOW + 1, NOW + 150, now -> facing(now, skeleton(3, 10, LONG_AGO)));
        // somebody else was driving for a hundred ticks, none of which count
        holds(c, NOW + 151, NOW + 250, now -> paused(facing(now, skeleton(3, 10, LONG_AGO))));
        holds(c, NOW + 251, NOW + 299, now -> facing(now, skeleton(3, 10, LONG_AGO)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_STALLED, c.step(facing(NOW + 300, skeleton(3, 10, LONG_AGO))));
    }

    @Test
    public void pausedTicksDoNotRunTheLostClockEither() {
        CombatCommit c = fightOn(skeleton(3, 15, 0));
        holds(c, NOW + 1, NOW + 1, now -> facing(now, skeleton(3, 30, LONG_AGO)));
        holds(c, NOW + 2, NOW + 101, now -> paused(facing(now, skeleton(3, 30, LONG_AGO))));
        holds(c, NOW + 102, NOW + 160, now -> facing(now, skeleton(3, 30, LONG_AGO)));
        assertEquals(Event.FIGHT_LOST, c.step(facing(NOW + 161, skeleton(3, 30, LONG_AGO))));
    }

    // ---- RUN

    @Test
    public void aRunHoldsShortOfTwentyFourBlocksNoMatterHowQuiet() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // (creeping about on the spot, a run standing dead still for five seconds is the stuck rule's)
        holds(c, NOW + 1, NOW + CombatCommit.RUN_CAP - 1, now -> runTick(now, 21 + shuffle(now), 0));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void twentyFourBlocksIsMeasuredAlongTheGroundFromWhereTheRunStarted() {
        CombatCommit c = runFrom(NOW, 100, 100);
        // 20 blocks on one axis is 20 blocks, and 18 on both is 25 and change even though neither axis got there
        holds(c, NOW + 1, NOW + 100, now -> runTick(now, 120, 100));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_CLEAR, c.step(runTick(NOW + 101, 118, 118)));
    }

    @Test
    public void exactlyTwentyFourBlocksIsFarEnoughAndTwentyThreeIsNot() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 100, now -> runTick(now, 23, 0));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_CLEAR, c.step(runTick(NOW + 101, 24, 0)));
    }

    @Test
    public void aRunEndsAtTwentyFourBlocksOnceNothingWasNearForTwoSeconds() {
        CombatCommit c = runFrom(NOW, 0, 0);
        long first = NOW + 1;
        // 39 ticks of quiet is not enough
        holds(c, first, first + 39, now -> runTick(now, 24, 0));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_CLEAR, c.step(runTick(first + 40, 24, 0)));
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.coolingDown(first + 40));
    }

    @Test
    public void aFoeWithinTwelveKeepsTheRunGoingAtTwentyFourBlocks() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 300, now -> runTick(now, 24 + shuffle(now), 0, zombie(1, 11)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aFoeExactlyAtTwelveStillCounts() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 300, now -> runTick(now, 24 + shuffle(now), 0, zombie(1, CombatCommit.RUN_CLEAR)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aFoeJustPastTwelveIsClear() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 40, now -> runTick(now, 24, 0, zombie(1, 12.1)));
        assertEquals(Event.RUN_CLEAR, c.step(runTick(NOW + 41, 24, 0, zombie(1, 12.1))));
    }

    @Test
    public void theClearClockRestartsWhenAFoeComesBack() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 30, now -> runTick(now, 24, 0));
        // back inside twelve for one tick
        holds(c, NOW + 31, NOW + 31, now -> runTick(now, 24, 0, zombie(1, 11)));
        // the first clock would have run out at NOW + 41, the second one starts at NOW + 32
        holds(c, NOW + 32, NOW + 32 + CombatCommit.RUN_CLEAR_TICKS - 1, now -> runTick(now, 24, 0));
        assertEquals(Event.RUN_CLEAR, c.step(runTick(NOW + 32 + CombatCommit.RUN_CLEAR_TICKS, 24, 0)));
    }

    @Test
    public void theClearClockRunsBeforeTwentyFourBlocksSoReachingItEndsItRightAway() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 100, now -> runTick(now, 10, 0));
        assertEquals(Event.RUN_CLEAR, c.step(runTick(NOW + 101, 24, 0)));
    }

    @Test
    public void theRunCapEndsItEvenWithAMobOnOurHeels() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // five blocks back is inside the watch but outside reach, and we keep moving, so it is not a corner either
        holds(c, NOW + 1, NOW + CombatCommit.RUN_CAP - 1, now -> runTick(now, shuffle(now), 0, zombie(1, 5)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_CAP, c.step(runTick(NOW + CombatCommit.RUN_CAP, shuffle(NOW + CombatCommit.RUN_CAP), 0, zombie(1, 5))));
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.coolingDown(NOW + CombatCommit.RUN_CAP));
    }

    @Test
    public void standingStillToEatKeepsTheRunGoing() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // a meal is a couple of seconds: nothing within eight does not corner us, and the stuck rule waits five seconds
        holds(c, NOW + 1, NOW + CombatCommit.RUN_STUCK_TICKS, now -> runTick(now, 0, 0));
        assertEquals(Mode.RUN, c.mode());
        assertFalse(c.cornered());
    }

    @Test
    public void standingStillLongerThanAMealWithNobodyAboutIsStuckNotCornered() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + CombatCommit.RUN_STUCK_TICKS, now -> runTick(now, 0, 0));
        assertEquals(Event.RUN_STUCK, c.step(runTick(NOW + CombatCommit.RUN_STUCK_TICKS + 1, 0, 0)));
        assertFalse(c.cornered());
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aPausedStretchInARunLeavesItARun() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // something at five keeps the corner bookkeeping busy while somebody else drives, and it is out of reach
        holds(c, NOW + 1, NOW + 300, now -> paused(runTick(now, 0, 0, zombie(1, 5))));
        assertEquals(Mode.RUN, c.mode());
        assertFalse(c.cornered());
    }

    // ---- cornered

    @Test
    public void aFoeOnTopOfUsWithNowhereToGoCornersTheRun() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // armed or not and at hp 3, a fight is all there is. the nearer of the two is the one
        Foe far = zombie(5, 2.5);
        Foe near = zombie(6, 1.5);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS, now -> unarmed(tick(now, 3f, null, far, near)));
        assertEquals(Event.RUN_TO_FIGHT, c.step(unarmed(tick(NOW + 1 + CombatCommit.CORNER_TICKS, 3f, null, far, near))));
        assertEquals(Mode.FIGHT, c.mode());
        assertTrue(c.cornered());
        assertEquals(Why.CORNERED, c.why());
        assertEquals(6, c.targetId());
        assertEquals(NOW + 1 + CombatCommit.CORNER_TICKS, c.startTick());
    }

    @Test
    public void aCorneredFightDoesNotBailOnHpOrOnACrowd() {
        CombatCommit c = runFrom(NOW, 0, 0);
        Foe a = zombie(5, 2.5);
        Foe b = zombie(6, 1.5);
        Foe d = zombie(7, 4);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS, now -> unarmed(tick(now, 3f, null, a, b, d)));
        long cornered = NOW + 1 + CombatCommit.CORNER_TICKS;
        assertEquals(Event.RUN_TO_FIGHT, c.step(unarmed(tick(cornered, 3f, null, a, b, d))));
        // hp 3 and three within six: any other fight would be on its way out of here
        holds(c, cornered + 1, cornered + 100, now -> unarmed(tick(now, 3f, b, a, b, d)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(6, c.targetId());
    }

    @Test
    public void aCorneredFightEndsWhenItsTargetDies() {
        CombatCommit c = corneredOn(6, 1.5);
        long dead = NOW + 200;
        // hp 3 with a mob at five would be a run in any other fight, boxed in it is just a win
        assertEquals(Event.FIGHT_DEAD, c.step(unarmed(tick(dead, 3f, null, zombie(9, 5)))));
        assertEquals(Mode.NONE, c.mode());
        assertFalse(c.cornered());
        assertTrue(c.coolingDown(dead));
    }

    @Test
    public void aCorneredFightChainsToAnotherFoeInReach() {
        CombatCommit c = corneredOn(6, 1.5);
        long dead = NOW + 200;
        assertEquals(Event.FIGHT_NEXT, c.step(unarmed(tick(dead, 3f, null, zombie(9, 2.9)))));
        assertEquals(Mode.FIGHT, c.mode());
        assertTrue(c.cornered());
        assertEquals(Why.CORNERED, c.why());
        assertEquals(9, c.targetId());
    }

    @Test
    public void aCorneredFightDoesNotChainToAFoeJustOutOfReach() {
        CombatCommit c = corneredOn(6, 1.5);
        assertEquals(Event.FIGHT_DEAD, c.step(unarmed(tick(NOW + 200, 3f, null, zombie(9, 3.1)))));
    }

    // a run that cornered at hp 3 with nothing in hand on this foe
    private static CombatCommit corneredOn(int id, double distance) {
        CombatCommit c = runFrom(NOW, 0, 0);
        Foe z = zombie(id, distance);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS, now -> unarmed(tick(now, 3f, null, z)));
        assertEquals(Event.RUN_TO_FIGHT, c.step(unarmed(tick(NOW + 1 + CombatCommit.CORNER_TICKS, 3f, null, z))));
        assertEquals(id, c.targetId());
        return c;
    }

    @Test
    public void movingFarEnoughStartsTheCornerWindowOver() {
        CombatCommit c = runFrom(NOW, 0, 0);
        Foe z = zombie(1, 2);
        holds(c, NOW + 1, NOW + 49, now -> runTick(now, 0, 0, z));
        // a step and a half is somewhere new, the window starts at NOW + 50
        holds(c, NOW + 50, NOW + 50 + CombatCommit.CORNER_TICKS - 1, now -> runTick(now, CombatCommit.CORNER_PROGRESS, 0, z));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 50 + CombatCommit.CORNER_TICKS, CombatCommit.CORNER_PROGRESS, 0, z)));
    }

    @Test
    public void shufflingLessThanTheProgressStepStillCountsAsStuck() {
        CombatCommit c = runFrom(NOW, 0, 0);
        Foe z = zombie(1, 2);
        holds(c, NOW + 1, NOW + 30, now -> runTick(now, 0, 0, z));
        holds(c, NOW + 31, NOW + CombatCommit.CORNER_TICKS, now -> runTick(now, 1.4, 0, z));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 1 + CombatCommit.CORNER_TICKS, 1.4, 0, z)));
    }

    // the second tier: something within the watch range, not on top of us, and six seconds on the spot
    @Test
    public void aFoeAtFiveCornersUsOnlyAfterTheSecondTier() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_FAR_TICKS, now -> runTick(now, 0, 0, zombie(1, 5)));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 1 + CombatCommit.CORNER_FAR_TICKS, 0, 0, zombie(1, 5))));
        assertTrue(c.cornered());
        assertEquals(1, c.targetId());
    }

    @Test
    public void aFoeAtFiveWhileWeKeepMovingNeverCornersUs() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 400, now -> runTick(now, shuffle(now), 0, zombie(1, 5)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aFoeJustPastReachWaitsForTheSecondTier() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // three seconds is the first tier's, a hair too far for it
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS + 20, now -> runTick(now, 0, 0, zombie(1, 3.1)));
        assertEquals(Mode.RUN, c.mode());
        holds(c, NOW + CombatCommit.CORNER_TICKS + 21, NOW + CombatCommit.CORNER_FAR_TICKS, now -> runTick(now, 0, 0, zombie(1, 3.1)));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 1 + CombatCommit.CORNER_FAR_TICKS, 0, 0, zombie(1, 3.1))));
    }

    @Test
    public void aFoeBeyondTheWatchRangeNeverCornersUs() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + CombatCommit.RUN_STUCK_TICKS, now -> runTick(now, 0, 0, zombie(1, 8.1)));
        assertEquals(Mode.RUN, c.mode());
        // standing there for five seconds is the stuck rule's end, not a corner (that one needs something within eight)
        assertEquals(Event.RUN_STUCK, c.step(runTick(NOW + CombatCommit.RUN_STUCK_TICKS + 1, 0, 0, zombie(1, 8.1))));
        assertFalse(c.cornered());
    }

    @Test
    public void aFoeExactlyInReachCornersUs() {
        CombatCommit c = runFrom(NOW, 0, 0);
        Foe z = zombie(1, CombatCommit.CORNER_RANGE);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS, now -> runTick(now, 0, 0, z));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 1 + CombatCommit.CORNER_TICKS, 0, 0, z)));
    }

    @Test
    public void aFoeArrivingAfterAWhileStillGetsTheFullCornerWindow() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // something at six opens the window for a moment, then it drops back to twelve and we stand there eating
        holds(c, NOW + 1, NOW + 10, now -> runTick(now, 0, 0, zombie(1, 6)));
        holds(c, NOW + 11, NOW + 200, now -> runTick(now, shuffle(now), 0, zombie(1, 12)));
        long arrives = NOW + 201;
        Foe z = zombie(1, 2);
        // it is not instantly cornered, the window opens now
        holds(c, arrives, arrives + CombatCommit.CORNER_TICKS - 1, now -> runTick(now, 0, 0, z));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(arrives + CombatCommit.CORNER_TICKS, 0, 0, z)));
    }

    // ---- the cooldown

    @Test
    public void aContactZombieThatHitUsTenSecondsAgoStartsNothingDuringTheCooldown() {
        CombatCommit c = wonFight();
        holds(c, WON_AT + 1, WON_AT + CombatCommit.COOLDOWN - 1, now -> tick(now, 20, null, zombie(2, 2, TEN_SECONDS)));
        assertEquals(Mode.NONE, c.mode());
    }

    // a run only ends with nothing within 12 for two seconds, so a mob inside 8 right after one means it came back
    @Test
    public void lowHpStillStartsARunDuringTheCooldown() {
        CombatCommit c = wonFight();
        assertTrue(c.coolingDown(WON_AT + 1));
        assertEquals(Event.RUN_START, c.step(tick(WON_AT + 1, 6f, null, zombie(2, 5))));
        assertEquals(Why.LOW_HP, c.why());
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void healthyOrFarAwayStartsNothingDuringTheCooldown() {
        CombatCommit c = wonFight();
        holds(c, WON_AT + 1, WON_AT + 40, now -> tick(now, 9f, null, zombie(2, 5)));
        holds(c, WON_AT + 41, WON_AT + CombatCommit.COOLDOWN - 1, now -> tick(now, 6f, null, zombie(2, 8.5)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aFreshHitBreaksTheCooldown() {
        CombatCommit c = wonFight();
        long now = WON_AT + 50;
        assertEquals(Event.NONE, c.step(tick(now, 20, null, zombie(2, 4, CombatCommit.FRESH + 1))));
        assertEquals(Event.FIGHT_START, c.step(tick(now + 1, 20, null, zombie(2, 4, CombatCommit.FRESH))));
        assertEquals(2, c.targetId());
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void contactTriggersAreBackWhenTheCooldownEnds() {
        CombatCommit c = wonFight();
        long over = WON_AT + CombatCommit.COOLDOWN;
        // never hit us, so this one is contact and nothing else
        assertEquals(Event.FIGHT_START, c.step(tick(over, 20, null, zombie(2, 2))));
        assertEquals(Why.CONTACT, c.why());
    }

    @Test
    public void oldHitsAreBackWhenTheCooldownEnds() {
        CombatCommit c = wonFight();
        long over = WON_AT + CombatCommit.COOLDOWN;
        assertEquals(Event.FIGHT_START, c.step(tick(over, 20, null, zombie(2, 5, TEN_SECONDS))));
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void lowHpRunsAreBackWhenTheCooldownEnds() {
        CombatCommit c = wonFight();
        long over = WON_AT + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(over, 7f, null, zombie(2, 5))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void theCooldownFollowsARunToo() {
        CombatCommit c = runFrom(NOW, 0, 0);
        long end = NOW + 1 + CombatCommit.RUN_CLEAR_TICKS;
        holds(c, NOW + 1, end - 1, now -> runTick(now, 24, 0));
        assertEquals(Event.RUN_CLEAR, c.step(runTick(end, 24, 0)));
        holds(c, end + 1, end + CombatCommit.COOLDOWN - 1, now -> tick(now, 20, null, zombie(2, 2, TEN_SECONDS)));
        assertEquals(Event.FIGHT_START, c.step(tick(end + CombatCommit.COOLDOWN, 20, null, zombie(2, 2))));
        assertEquals(Why.CONTACT, c.why());
    }

    // ---- heavy hitters and things we never fight

    @Test
    public void aHeavyHitterIsAFightAboveTenHpAndARunAtTenOrLess() {
        // hoglin, wither skeleton, brute, vindicator, ravager: 8 to 13 a swing is one hit from the respawn screen at 10
        for (float hp : new float[]{20, 14, 11}) {
            CombatCommit fight = new CombatCommit();
            assertEquals("hp " + hp, Event.FIGHT_START, fight.step(tick(NOW, hp, null, heavy(1, 2))));
            assertEquals(Why.CONTACT, fight.why());
        }
        for (float hp : new float[]{10, 9.5f}) {
            CombatCommit run = new CombatCommit();
            assertEquals("hp " + hp, Event.RUN_START, run.step(tick(NOW, hp, null, heavy(1, 2))));
            assertEquals(Why.HEAVY, run.why());
        }
    }

    @Test
    public void theHeavyLineIsItsOwnNumberAboveTheFleeLine() {
        assertEquals(10, CombatCommit.HEAVY_FLEE_HP, 0);
        assertEquals(8, CombatCommit.FLEE_HP, 0);
        assertTrue(CombatCommit.HEAVY_FLEE_HP > CombatCommit.FLEE_HP);
        // everything that is not heavy still fights at 9 and 10
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, 10, null, zombie(1, 2))));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, 9, null, zombie(1, 2))));
    }

    @Test
    public void aHeavyHitterThatHitUsFromSixBlocksOutIsTheSameCall() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 10, null, heavy(1, 6, 3))));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(NOW, 11, null, heavy(1, 6, 3))));
        // not having hit us, a heavy hitter at five blocks is scenery like anything else (ignore by default)
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 10.5f, null, heavy(1, 5))));
    }

    @Test
    public void aTriggerFromAZombieWithAHeavyHitterCloseIsARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 10, null, zombie(1, 2, 3), heavy(2, 5))));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void theWardenIsNeverAFightAtAnyHp() {
        for (float hp : new float[]{20, 15, 12, 9}) {
            // it hit us (the sonic boom reaches fifteen blocks)
            CombatCommit hit = new CombatCommit();
            assertEquals("hit at hp " + hp, Event.RUN_START, hit.step(tick(NOW, hp, null, warden(1, 14, 3))));
            assertEquals(Why.HEAVY, hit.why());
            // and in contact
            CombatCommit contact = new CombatCommit();
            assertEquals("contact at hp " + hp, Event.RUN_START, contact.step(tick(NOW, hp, null, warden(1, 2.5, NEVER))));
            assertEquals(Why.HEAVY, contact.why());
        }
        // scenery until it does one of those
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, warden(1, 10, NEVER))));
        // a boom from sixteen is out of range like any other shooter at sixteen
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, warden(1, 16, 3))));
    }

    @Test
    public void theWardenNeverStartsAFightEvenWhenArmedAndHealthy() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, warden(1, 2, 3))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(-1, c.targetId());
    }

    @Test
    public void aDeadTargetNeverChainsIntoAWarden() {
        CombatCommit c = fightOn(zombie(1, 2, 0));
        // the zombie dies while a warden that hit us stands at ten blocks: bailing (it is not within six) is idle's job next
        // tick, chaining to it would be a fight
        Event event = c.step(tick(NOW + 5, 20, null, warden(2, 10, 3)));
        assertEquals(Event.FIGHT_DEAD, event);
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aWardenWithinSixBailsAFightWhateverTheHp() {
        CombatCommit c = fightOn(zombie(7, 2, 0));
        Foe z = zombie(7, 2, 5);
        assertEquals(Event.FIGHT_TO_RUN, c.step(tick(NOW + 5, 20, z, z, warden(8, 5.5, NEVER))));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aHeavyTargetOutsideTheFoeListStillBailsTheFight() {
        // the target is looked up on its own: a hoglin we started on at 11 hp that drops us to 10 while it is 9 blocks out
        CombatCommit c = fightOn(heavy(7, 2, 0));
        // started above the heavy line
        Foe h = heavy(7, 9, 5);
        assertEquals(Event.FIGHT_TO_RUN, c.step(new Tick(NOW + 5, 10, true, 0, 0, 3, List.of(), h, false)));
        assertEquals(Why.HEAVY, c.why());
    }

    @Test
    public void aRunCorneredByAWardenIsNeverAFight() {
        // the warden is never a fight in any state: boxed in, on top of us, for as long as the run lasts, it stays a run
        CombatCommit c = new CombatCommit();
        Foe w = warden(1, 2, 3);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, w)));
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_FAR_TICKS + 40, now -> tick(now, 20, null, w));
        assertEquals(Mode.RUN, c.mode());
        assertFalse(c.cornered());
        assertEquals(-1, c.targetId());
    }

    @Test
    public void aRunCorneredByAWardenAndAZombieFightsTheZombie() {
        CombatCommit c = new CombatCommit();
        Foe w = warden(1, 2, 3);
        Foe z = zombie(2, 2.5);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, w, z)));
        Event event = Event.NONE;
        for (long t = NOW + 1; t <= NOW + 1 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = c.step(tick(t, 20, null, w, z));
        }
        // the warden is nearer and still not the pick
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertEquals(2, c.targetId());
    }

    @Test
    public void aRunCorneredByAHeavyHitterAtLowHpStillFightsIt() {
        // boxed in with a hoglin on us at 9 hp: it is the one thing left to do (only the warden is off the table)
        CombatCommit c = new CombatCommit();
        Foe h = heavy(1, 2, 3);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 9, null, h)));
        Event event = Event.NONE;
        for (long t = NOW + 1; t <= NOW + 1 + CombatCommit.CORNER_TICKS + 1 && event == Event.NONE; t++) {
            event = c.step(tick(t, 9, null, h));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertTrue(c.cornered());
        assertEquals(Mode.FIGHT, c.mode());
    }

    // ---- flyers: a blaze hovering over lava

    @Test
    public void aBlazeThatHitUsFromBeyondContactIsARunNotAChase() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, blaze(1, 9, 3))));
        assertEquals(Why.UNREACHABLE, c.why());
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aBlazeThatHitUsWithinContactIsAnOrdinaryFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, null, blaze(1, 3, 3))));
        assertEquals(Why.HIT, c.why());
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aBlazeIsAShooterSoItCountsFromFifteenBlocksOut() {
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(NOW, 20, null, blaze(1, 15, 3))));
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, blaze(1, 15.5, 3))));
        // never having hit us it is scenery, in contact or not (a blaze is not a melee mob)
        assertEquals(Event.NONE, new CombatCommit().step(tick(NOW, 20, null, blaze(1, 2, NEVER))));
    }

    @Test
    public void aBlazeNeverMakesAFightFromAHealthyHitBeyondContactEvenArmed() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 20, null, blaze(1, 6, 0))));
        assertEquals(-1, c.targetId());
    }

    @Test
    public void aFlyerOutOfReachIsNotAReasonToChainAFight() {
        CombatCommit c = fightOn(zombie(1, 2, 0));
        // the zombie dies, a blaze that hit us is at eight blocks: no chain, the fight is over and the run is idle's call
        Event event = c.step(tick(NOW + 5, 20, null, blaze(2, 8, 3)));
        assertEquals(Event.FIGHT_DEAD, event);
        assertEquals(Mode.NONE, c.mode());
        // (a fresh hit gets through the cooldown, and it is a run)
        assertEquals(Event.RUN_START, c.step(tick(NOW + 6, 20, null, blaze(2, 8, 0))));
        assertEquals(Why.UNREACHABLE, c.why());
    }

    // ---- a run that is going nowhere with nothing near

    @Test
    public void aRunThatMakesNoGroundWithNothingNearIsOverAfterFiveSeconds() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // standing at a lava lake: nothing angry within eight, no ground gained
        holds(c, NOW + 1, NOW + CombatCommit.RUN_STUCK_TICKS, now -> runTick(now, 0, 0, zombie(2, 12)));
        assertEquals(Event.RUN_STUCK, c.step(runTick(NOW + CombatCommit.RUN_STUCK_TICKS + 1, 0, 0, zombie(2, 12))));
        assertEquals(Mode.NONE, c.mode());
        assertTrue(c.coolingDown(NOW + CombatCommit.RUN_STUCK_TICKS + 2));
    }

    @Test
    public void aRunThatKeepsMovingIsNeverStuck() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // two blocks every second and a half: never five seconds on the same spot
        holds(c, NOW + 1, NOW + 400, now -> runTick(now, ((now - NOW) / 30) * 2, 0, zombie(2, 10)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aRunWithSomethingNearIsNotTheStuckRulesBusiness() {
        CombatCommit c = runFrom(NOW, 0, 0);
        // a zombie at six blocks: the corner window is the one that counts, and it is only an answer for something within three
        // or, after six seconds, within eight
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_FAR_TICKS - 5, now -> runTick(now, 0, 0, zombie(2, 6)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void somethingComingNearRestartsTheStuckWindow() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 60, now -> runTick(now, 0, 0, zombie(2, 12)));
        // it walks inside eight for a moment (the corner window starts) and leaves: five seconds start over, not forty ticks
        holds(c, NOW + 61, NOW + 65, now -> runTick(now, 0, 0, zombie(2, 7)));
        holds(c, NOW + 66, NOW + 66 + CombatCommit.RUN_STUCK_TICKS - 1, now -> runTick(now, 0, 0, zombie(2, 12)));
        assertEquals(Event.RUN_STUCK, c.step(runTick(NOW + 66 + CombatCommit.RUN_STUCK_TICKS, 0, 0, zombie(2, 12))));
    }

    @Test
    public void aCreeperStepAwayHoldsTheStuckWindowStill() {
        CombatCommit c = runFrom(NOW, 0, 0);
        holds(c, NOW + 1, NOW + 60, now -> runTick(now, 0, 0, zombie(2, 12)));
        // 100 paused ticks do not count as standing still
        holds(c, NOW + 61, NOW + 160, now -> paused(runTick(now, 0, 0, zombie(2, 12))));
        assertEquals(Mode.RUN, c.mode());
    }

    // ---- clocks

    @Test
    public void aClockThatWentBackwardsResetsTheMachine() {
        CombatCommit c = fightOn(5000, zombie(1, 5, 0));
        assertEquals(Event.NONE, c.step(tick(100, 20, null)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals(-1, c.targetId());
    }

    @Test
    public void aClockThatWentBackwardsForgetsTheCooldown() {
        CombatCommit c = fightOn(5000, zombie(1, 5, 0));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(5010, 20, null)));
        assertTrue(c.coolingDown(5011));
        // a new world, a zombie in contact right away would be ignored if the old cooldown were still ticking
        assertEquals(Event.FIGHT_START, c.step(tick(100, 20, null, zombie(2, 2))));
    }

    @Test
    public void aClockThatWentBackwardsForgetsWhoWeGaveUpOn() {
        CombatCommit c = fightOn(5000, zombie(1, 3.0));
        standUntilStalled(c, 5000, 5, NEVER);
        assertTrue(c.isIgnored(1));
        // ids get reused in a new world
        assertEquals(Event.FIGHT_START, c.step(tick(100, 20, null, zombie(1, 2))));
        assertEquals(1, c.targetId());
    }

    // ---- a new player (what the chain does on a respawn, the clock keeps going so the backwards clock rule is no help)

    @Test
    public void resetLetsGoOfAFight() {
        CombatCommit c = fightOn(zombie(1, 5, 0));
        c.reset();
        assertEquals(Mode.NONE, c.mode());
        assertEquals(-1, c.targetId());
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 20, null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void resetLetsGoOfARun() {
        CombatCommit c = runFrom(NOW, 0, 0);
        c.reset();
        assertEquals(Mode.NONE, c.mode());
        assertFalse(c.cornered());
        assertEquals(Event.NONE, c.step(tick(NOW + 1, 20, null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void resetForgetsTheCooldownAndWhoWeGaveUpOnEvenWithTheClockGoingOn() {
        CombatCommit c = wonFight();
        assertTrue(c.coolingDown(WON_AT + 1));
        c.reset();
        assertFalse(c.coolingDown(WON_AT + 1));
        // the cooldown belonged to the old life, a zombie in contact is a fight right away
        assertEquals(Event.FIGHT_START, c.step(tick(WON_AT + 5, 20, null, zombie(2, 2))));

        CombatCommit gaveUp = fightOn(NOW, zombie(1, 3.0));
        standUntilStalled(gaveUp, NOW, 5, NEVER);
        assertTrue(gaveUp.isIgnored(1));
        gaveUp.reset();
        assertFalse(gaveUp.isIgnored(1));
    }
}
