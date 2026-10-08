package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.CombatPolicy.Verdict.CHARGE;
import static adris.altoclef.util.helpers.CombatPolicy.Verdict.FIGHT_ONE;
import static adris.altoclef.util.helpers.CombatPolicy.Verdict.IGNORE;
import static adris.altoclef.util.helpers.CombatPolicy.Verdict.KITE;
import static adris.altoclef.util.helpers.CombatPolicy.Verdict.STAND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatPolicy.Decision;
import adris.altoclef.util.helpers.CombatPolicy.Mob;
import adris.altoclef.util.helpers.CombatPolicy.Point;
import adris.altoclef.util.helpers.CombatPolicy.Scene;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// eight zombies, and a zombie off to the side of where we were going anyway
public class CombatPolicyTest {

    private static final long QUIET = 10_000;
    private static final int THRESHOLD = 3;
    private static final long GRACE = 100;

    private static Mob zombie(int id, double dx, double dz) {
        return new Mob(id, dx, 0, dz, false, false, false, false);
    }

    private static Mob spider(int id, double dx, double dz) {
        return new Mob(id, dx, 0, dz, false, false, true, false);
    }

    private static Mob creeper(int id, double dx, double dz) {
        return new Mob(id, dx, 0, dz, false, true, false, false);
    }

    private static Mob skeleton(int id, double dx, double dz, boolean sees) {
        return new Mob(id, dx, 0, dz, true, false, false, sees);
    }

    private static Scene fighting(List<Mob> mobs) {
        return new Scene(mobs, QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE);
    }

    private static Scene walking(List<Mob> mobs, List<Point> path, long sinceHurt) {
        return new Scene(mobs, sinceHurt, true, path, 0, 0, THRESHOLD, GRACE);
    }

    private static Scene at(Scene s, double x, double z) {
        return new Scene(s.mobs(), s.ticksSinceHurt(), s.travelling(), s.path(), x, z, s.swarmThreshold(), s.graceTicks());
    }

    private static List<Mob> ring(int count, double radius) {
        List<Mob> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = i * 2 * Math.PI / count;
            out.add(zombie(i, Math.cos(angle) * radius, Math.sin(angle) * radius));
        }
        return out;
    }

    // ---- the swarm

    @Test
    public void eightZombiesAreARunNotAStand() {
        Decision d = new CombatPolicy().decide(0, fighting(ring(8, 5)));
        assertEquals(KITE, d.verdict());
        assertFalse(d.shield());
        assertEquals(8, d.swarm());
    }

    @Test
    public void twoZombiesAreNotACrowd() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(ring(2, 5))).verdict());
    }

    @Test
    public void threeIsTheDefaultLine() {
        assertEquals(KITE, new CombatPolicy().decide(0, fighting(ring(3, 5))).verdict());
    }

    @Test
    public void theThresholdIsTheSettings() {
        Scene s = new Scene(ring(4, 5), QUIET, false, List.of(), 0, 0, 5, GRACE);
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, s).verdict());
    }

    @Test
    public void farAwayZombiesDoNotMakeACrowd() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(ring(8, 7))).verdict());
    }

    @Test
    public void fastMobsAreNotWorthRunningFrom() {
        // you cannot outrun a spider, so three of them are a fight in place, not a footrace
        List<Mob> spiders = List.of(spider(1, 4, 0), spider(2, 0, 4), spider(3, -4, 0));
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(spiders)).verdict());
    }

    @Test
    public void onlyOneInReachIsOneAtATime() {
        CombatPolicy policy = new CombatPolicy();
        List<Mob> mobs = new ArrayList<>(ring(5, 5));
        mobs.add(zombie(99, 1, 1));
        // five coming and one already on us: that is a crowd, it runs first and asks questions later
        assertEquals(KITE, policy.decide(0, fighting(mobs)).verdict());
        // once it has run long enough that only the front one is close, the front one gets hit
        List<Mob> strung = List.of(zombie(1, 2, 0), zombie(2, 5, 0), zombie(3, 5.5, 0));
        Decision d = policy.decide(CombatPolicy.MIN_DWELL, fighting(strung));
        assertEquals(FIGHT_ONE, d.verdict());
        assertFalse(d.shield());
    }

    @Test
    public void twoOnUsAndNoCrowdIsAShield() {
        Decision d = new CombatPolicy().decide(0, fighting(List.of(zombie(1, 2, 0), zombie(2, 0, 2))));
        assertEquals(STAND, d.verdict());
        assertTrue(d.shield());
    }

    @Test
    public void kiteNeedsTwentyTicksBeforeItChangesItsMind() {
        CombatPolicy policy = new CombatPolicy();
        assertEquals(KITE, policy.decide(0, fighting(ring(8, 5))).verdict());
        List<Mob> strung = List.of(zombie(1, 2, 0), zombie(2, 5, 0), zombie(3, 5.5, 0));
        assertEquals(KITE, policy.decide(CombatPolicy.MIN_DWELL - 1, fighting(strung)).verdict());
        assertEquals(FIGHT_ONE, policy.decide(CombatPolicy.MIN_DWELL, fighting(strung)).verdict());
    }

    @Test
    public void stringingOutDoesNotImmediatelyStartTheRunAgain() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ring(8, 5)));
        List<Mob> strung = List.of(zombie(1, 2, 0), zombie(2, 5, 0), zombie(3, 5.5, 0));
        assertEquals(FIGHT_ONE, policy.decide(30, fighting(strung)).verdict());
        // still three within six, but only one on us: we are fighting the front one, not flapping
        assertEquals(FIGHT_ONE, policy.decide(60, fighting(strung)).verdict());
        assertEquals(FIGHT_ONE, policy.decide(90, fighting(strung)).verdict());
        // the second one gets there: now it is a pile again
        List<Mob> piling = List.of(zombie(1, 2, 0), zombie(2, 2, 2), zombie(3, 5.5, 0));
        assertEquals(KITE, policy.decide(120, fighting(piling)).verdict());
    }

    @Test
    public void aRetreatThatGoesNowhereStands() {
        CombatPolicy policy = new CombatPolicy();
        Scene crowd = fighting(ring(6, 4));
        assertEquals(KITE, policy.decide(0, at(crowd, 100, 100)).verdict());
        // a window later we have moved a block and a half. dead end
        Decision d = policy.decide(CombatPolicy.KITE_WINDOW, at(crowd, 101, 100.5));
        assertEquals(STAND, d.verdict());
        assertTrue(d.shield());
        // and it does not try to run again for a while
        assertEquals(STAND, policy.decide(CombatPolicy.KITE_WINDOW + 100, at(crowd, 101, 100.5)).verdict());
        assertEquals(KITE, policy.decide(CombatPolicy.KITE_WINDOW + CombatPolicy.KITE_GIVE_UP + 1, at(crowd, 101, 100.5)).verdict());
    }

    @Test
    public void aRetreatThatMakesGroundKeepsGoing() {
        CombatPolicy policy = new CombatPolicy();
        Scene crowd = fighting(ring(6, 4));
        assertEquals(KITE, policy.decide(0, at(crowd, 0, 0)).verdict());
        assertEquals(KITE, policy.decide(CombatPolicy.KITE_WINDOW, at(crowd, 8, 0)).verdict());
        assertEquals(KITE, policy.decide(CombatPolicy.KITE_WINDOW * 2, at(crowd, 16, 0)).verdict());
    }

    @Test
    public void nobodyInReachAndACrowdOnOurHeelsMeansKeepRunning() {
        CombatPolicy policy = new CombatPolicy();
        List<Mob> behind = ring(5, 5);
        assertEquals(KITE, policy.decide(0, fighting(behind)).verdict());
        assertEquals(KITE, policy.decide(25, at(fighting(behind), 10, 0)).verdict());
    }

    @Test
    public void whenTheCrowdThinsOutTheStragglersComeToUs() {
        CombatPolicy policy = new CombatPolicy();
        assertEquals(KITE, policy.decide(0, fighting(ring(6, 5))).verdict());
        // two left within six, the rest fell behind
        List<Mob> few = List.of(zombie(1, 5, 0), zombie(2, 5.5, 1), zombie(3, 9, 0));
        Decision d = policy.decide(25, at(fighting(few), 10, 0));
        assertEquals(FIGHT_ONE, d.verdict());
        // and we do not walk back into them to say hello
        assertTrue(d.hold());
        // the hold runs out
        assertFalse(policy.decide(25 + CombatPolicy.HOLD_TICKS, at(fighting(few), 10, 0)).hold());
    }

    @Test
    public void aRetreatThatNeverShakesThemGivesUp() {
        CombatPolicy policy = new CombatPolicy();
        List<Mob> behind = ring(5, 5);
        assertEquals(KITE, policy.decide(0, fighting(behind)).verdict());
        // plenty of ground covered every window, and they are still right there
        for (long now = 10; now < CombatPolicy.KITE_MAX; now += 10) {
            assertEquals(KITE, policy.decide(now, at(fighting(behind), now, 0)).verdict());
        }
        assertEquals(STAND, policy.decide(CombatPolicy.KITE_MAX, at(fighting(behind), 400, 0)).verdict());
    }

    @Test
    public void aCrowdThatIsGoneIsGone() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ring(8, 5)));
        assertEquals(IGNORE, policy.decide(5, fighting(List.of())).verdict());
        // and the next one is a new crowd, no hangover
        assertEquals(KITE, policy.decide(6, fighting(ring(8, 5))).verdict());
    }

    @Test
    public void idleResetsTheCrowdToo() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ring(8, 5)));
        assertEquals(IGNORE, policy.idle().verdict());
        assertEquals(KITE, policy.decide(1, fighting(ring(8, 5))).verdict());
    }

    @Test
    public void creepersAreNotCounted() {
        // the creeper logic has its own plan, it should not turn two zombies into a stampede
        List<Mob> mobs = List.of(zombie(1, 4, 0), zombie(2, -4, 0), creeper(3, 0, 5));
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(mobs)).verdict());
    }

    // ---- walking past

    private static final List<Point> AHEAD = List.of(new Point(0, 0, 1), new Point(0, 0, 3), new Point(0, 0, 6), new Point(0, 0, 9));

    @Test
    public void aZombieOffToTheSideWhileWeTravelIsIgnored() {
        Decision d = new CombatPolicy().decide(0, walking(List.of(zombie(1, 6, 0)), AHEAD, QUIET));
        assertEquals(IGNORE, d.verdict());
        assertTrue(d.ignored().contains(1));
    }

    @Test
    public void notTravellingMeansNothingIsIgnored() {
        Scene s = new Scene(List.of(zombie(1, 6, 0)), QUIET, false, AHEAD, 0, 0, THRESHOLD, GRACE);
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, s).verdict());
    }

    @Test
    public void aHitEndsTheStroll() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 6, 0)), AHEAD, 50)).verdict());
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 6, 0)), AHEAD, GRACE)).verdict());
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 6, 0)), AHEAD, GRACE + 1)).verdict());
    }

    @Test
    public void aZombieInReachIsAFight() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 2, 0)), AHEAD, QUIET)).verdict());
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 2.5, 0)), AHEAD, QUIET)).verdict());
    }

    @Test
    public void aZombieOnTheRouteIsNotPassedItIsMetThere() {
        // it is five blocks away but our path goes right through where it is standing
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 0.5, 6)), AHEAD, QUIET)).verdict());
        // and just clear of the route is just clear of it
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 3, 6)), AHEAD, QUIET)).verdict());
    }

    @Test
    public void aZombieInOtherPartsOfTheMapIsNotOnTheRoute() {
        // directly over the path but eight blocks up
        Mob above = new Mob(1, 0, 8, 6, false, false, false, false);
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(above), AHEAD, QUIET)).verdict());
    }

    @Test
    public void fastMobsAreNeverWalkedPast() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(spider(1, 8, 0)), AHEAD, QUIET)).verdict());
    }

    @Test
    public void creepersCloseByAreNeverWalkedPast() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, walking(List.of(creeper(1, 6.5, 0)), AHEAD, QUIET)).verdict());
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(creeper(1, 7.5, 0)), AHEAD, QUIET)).verdict());
    }

    @Test
    public void skeletonsWithAClearShotAreNeverWalkedPast() {
        // (and now that is a charge, not a fight)
        assertEquals(CHARGE, new CombatPolicy().decide(0, walking(List.of(skeleton(1, 12, 0, true)), AHEAD, QUIET)).verdict());
        // around a corner, it is not shooting at anything
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(skeleton(1, 12, 0, false)), AHEAD, QUIET)).verdict());
        // and a long way off, the line of sight number is whatever it is, out of range
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(skeleton(1, 16, 0, true)), AHEAD, QUIET)).verdict());
    }

    @Test
    public void passingAGroupDoesNotMakeItACrowd() {
        // three zombies on a hillside off to our left. none of them is our business, so none of them counts
        List<Mob> hill = List.of(zombie(1, 6, 0), zombie(2, 6, 2), zombie(3, 5, -2));
        Decision d = new CombatPolicy().decide(0, walking(hill, AHEAD, QUIET));
        assertEquals(IGNORE, d.verdict());
        assertEquals(3, d.ignored().size());
        assertEquals(0, d.swarm());
    }

    @Test
    public void onlyTheOnesInTheWayAreFought() {
        List<Mob> mixed = List.of(zombie(1, 6, 0), zombie(2, 1, 2));
        Decision d = new CombatPolicy().decide(0, walking(mixed, AHEAD, QUIET));
        assertEquals(FIGHT_ONE, d.verdict());
        assertTrue(d.ignored().contains(1));
        assertFalse(d.ignored().contains(2));
    }

    @Test
    public void aCrowdOnTheRouteIsStillACrowd() {
        List<Mob> wall = new ArrayList<>();
        for (int i = 0; i < 6; i++) wall.add(zombie(i, -1 + i * 0.4, 5));
        assertEquals(KITE, new CombatPolicy().decide(0, walking(wall, AHEAD, QUIET)).verdict());
    }

    // ---- the low hp latch

    // walking along AHEAD at the given health. the zombie on the hillside is off the route, so at full health it's ignored
    private static Scene strollingAt(List<Mob> mobs, float hp) {
        return new Scene(mobs, QUIET, true, AHEAD, 0, 0, THRESHOLD, GRACE, hp);
    }

    @Test
    public void nobodyIsWalkedPastAtLowHealth() {
        List<Mob> hill = List.of(zombie(1, 6, 0));
        assertEquals(IGNORE, new CombatPolicy().decide(0, strollingAt(hill, 20)).verdict());
        assertTrue(new CombatPolicy().decide(0, strollingAt(hill, 20)).ignored().contains(1));
        // hp 3 and "passing 5 zombified piglins, not my problem" is how this bot died
        Decision d = new CombatPolicy().decide(0, strollingAt(hill, 3));
        assertEquals(FIGHT_ONE, d.verdict());
        assertFalse(d.ignored().contains(1));
        // the line itself counts (<=)
        assertFalse(new CombatPolicy().decide(0, strollingAt(hill, CombatPolicy.LOW_HP)).ignored().contains(1));
        assertTrue(new CombatPolicy().decide(0, strollingAt(hill, CombatPolicy.LOW_HP + 1)).ignored().contains(1));
    }

    @Test
    public void lowHealthWithSomethingNearLatchesForTwoSeconds() {
        CombatPolicy policy = new CombatPolicy();
        assertFalse(policy.lowHpLatched(0));
        policy.decide(100, withHealth(List.of(zombie(1, 5, 0)), 4));
        assertTrue(policy.lowHpLatched(100));
        assertTrue(policy.lowHpLatched(100 + CombatPolicy.LOW_HP_LATCH - 1));
        assertFalse(policy.lowHpLatched(100 + CombatPolicy.LOW_HP_LATCH));
    }

    @Test
    public void theLatchKeepsWalkingPastOffAfterHealthRecovers() {
        CombatPolicy policy = new CombatPolicy();
        List<Mob> hill = List.of(zombie(1, 6, 0));
        policy.decide(0, strollingAt(hill, 5));
        // a heal tick later we are at 8 hp, which on its own would be a stroll again. the latch says no
        assertFalse(policy.decide(10, strollingAt(hill, 8)).ignored().contains(1));
        // and once it runs out, it is
        assertTrue(policy.decide(CombatPolicy.LOW_HP_LATCH + 1, strollingAt(hill, 8)).ignored().contains(1));
    }

    @Test
    public void theLatchSurvivesAQuietTickBetweenFights() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, withHealth(List.of(zombie(1, 5, 0)), 3));
        // nothing dealable for a tick (the mob stepped out of the zone): the policy goes idle, the latch must not
        policy.idle();
        assertTrue(policy.lowHpLatched(5));
    }

    @Test
    public void noLatchWithoutSomethingCloseOrWithHealthToSpare() {
        CombatPolicy far = new CombatPolicy();
        far.decide(0, withHealth(List.of(zombie(1, 12, 0)), 3));
        assertFalse(far.lowHpLatched(0));
        CombatPolicy healthy = new CombatPolicy();
        healthy.decide(0, withHealth(List.of(zombie(1, 3, 0)), CombatPolicy.LOW_HP + 1));
        assertFalse(healthy.lowHpLatched(0));
    }

    @Test
    public void everyTickInTheLatchRefreshesIt() {
        CombatPolicy policy = new CombatPolicy();
        for (long t = 0; t < 200; t += 10) {
            policy.decide(t, withHealth(List.of(zombie(1, 4, 0)), 3));
            assertTrue("at " + t, policy.lowHpLatched(t + CombatPolicy.LOW_HP_LATCH - 1));
        }
    }

    // ---- the charge

    private static Scene withHealth(List<Mob> mobs, float hp) {
        return new Scene(mobs, QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, hp);
    }

    private static final List<Mob> ONE_SKELETON = List.of(skeleton(1, 10, 0, true));

    @Test
    public void aLoneSkeletonIsAChargeNotAFight() {
        Decision d = new CombatPolicy().decide(0, fighting(ONE_SKELETON));
        assertEquals(CHARGE, d.verdict());
        assertTrue(d.charging());
        // the shield is a stand-your-ground thing, a charge only raises it for an arrow and the chain does that itself
        assertFalse(d.shield());
        assertFalse(d.kiting());
        assertFalse(d.hold());
    }

    @Test
    public void twoShootersAreStillAChargeThreeAreAFiringLine() {
        List<Mob> two = List.of(skeleton(1, 8, 0, true), skeleton(2, 0, 9, true));
        assertEquals(CHARGE, new CombatPolicy().decide(0, fighting(two)).verdict());
        List<Mob> three = List.of(skeleton(1, 8, 0, true), skeleton(2, 0, 9, true), skeleton(3, -9, 0, true));
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(three)).verdict());
    }

    @Test
    public void aShooterStartsTheChargeFromTwelve() {
        assertEquals(CHARGE, new CombatPolicy().decide(0, fighting(List.of(skeleton(1, 12, 0, true)))).verdict());
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(List.of(skeleton(1, 13, 0, true)))).verdict());
    }

    @Test
    public void aCrowdOutranksTheShooter() {
        List<Mob> mobs = new ArrayList<>(ring(4, 5));
        mobs.add(skeleton(50, 0, 9, true));
        assertEquals(KITE, new CombatPolicy().decide(0, fighting(mobs)).verdict());
    }

    @Test
    public void twoOnUsIsAStandOneOnUsIsStillACharge() {
        List<Mob> pile = List.of(zombie(1, 2, 0), zombie(2, 0, 2), skeleton(3, 9, 0, true));
        assertEquals(STAND, new CombatPolicy().decide(0, fighting(pile)).verdict());
        List<Mob> one = List.of(zombie(1, 2, 0), skeleton(3, 9, 0, true));
        assertEquals(CHARGE, new CombatPolicy().decide(0, fighting(one)).verdict());
    }

    @Test
    public void theChargeSticksThroughALostLineOfSight() {
        CombatPolicy policy = new CombatPolicy();
        assertEquals(CHARGE, policy.decide(0, fighting(ONE_SKELETON)).verdict());
        // it stepped behind a pillar and backed off a bit. still the thing to kill
        assertEquals(CHARGE, policy.decide(3, fighting(List.of(skeleton(1, 14, 0, false)))).verdict());
    }

    @Test
    public void theChargeIsCommittedForTwentyTicks() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ONE_SKELETON));
        List<Mob> pile = List.of(skeleton(1, 10, 0, true), zombie(2, 2, 0), zombie(3, 0, 2));
        // two zombies arrive: not yet, we are committed
        assertEquals(CHARGE, policy.decide(CombatPolicy.CHARGE_COMMIT - 1, fighting(pile)).verdict());
        // now it is a melee and the shield is the answer
        assertEquals(STAND, policy.decide(CombatPolicy.CHARGE_COMMIT, fighting(pile)).verdict());
    }

    @Test
    public void lowHealthEndsTheChargeAndItDoesNotComeBackUntilWeRecover() {
        CombatPolicy policy = new CombatPolicy();
        assertEquals(CHARGE, policy.decide(0, fighting(ONE_SKELETON)).verdict());
        assertEquals(FIGHT_ONE, policy.decide(1, withHealth(ONE_SKELETON, 8)).verdict());
        // hp 9 would be a legal start on its own, but we just ran out of health doing this
        assertEquals(FIGHT_ONE, policy.decide(2, withHealth(ONE_SKELETON, 10)).verdict());
        assertEquals(FIGHT_ONE, policy.decide(3, withHealth(ONE_SKELETON, CombatPolicy.CHARGE_RESUME - 1)).verdict());
        assertEquals(CHARGE, policy.decide(4, withHealth(ONE_SKELETON, CombatPolicy.CHARGE_RESUME)).verdict());
    }

    @Test
    public void nineHealthIsEnoughToStartEightIsNot() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, withHealth(ONE_SKELETON, 8)).verdict());
        assertEquals(CHARGE, new CombatPolicy().decide(0, withHealth(ONE_SKELETON, 9)).verdict());
    }

    @Test
    public void aThirdShooterSeeingUsEndsTheChargeEvenInsideTheCommit() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ONE_SKELETON));
        List<Mob> three = List.of(skeleton(1, 8, 0, true), skeleton(2, 0, 9, true), skeleton(3, -9, 0, true));
        assertEquals(FIGHT_ONE, policy.decide(1, fighting(three)).verdict());
    }

    @Test
    public void theChargeEndsWithTheShooter() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ONE_SKELETON));
        assertEquals(FIGHT_ONE, policy.decide(1, fighting(List.of(zombie(5, 6, 0)))).verdict());
        // and the next skeleton is a fresh charge
        assertEquals(CHARGE, policy.decide(2, fighting(ONE_SKELETON)).verdict());
    }

    @Test
    public void nothingLeftResetsTheCharge() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, fighting(ONE_SKELETON));
        assertEquals(IGNORE, policy.decide(1, fighting(List.of())).verdict());
        // nothing carried over: two zombies on us at the start of a charge is a stand, not a charge
        List<Mob> pile = List.of(skeleton(1, 10, 0, true), zombie(2, 2, 0), zombie(3, 0, 2));
        assertEquals(STAND, policy.decide(2, fighting(pile)).verdict());
    }

    @Test
    public void shootersThatOutrunUsAreNotChargedAtPillagersAndFriends() {
        Mob pillager = new Mob(1, 8, 0, 0, true, false, true, true);
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, fighting(List.of(pillager))).verdict());
    }

    @Test
    public void aShooterByTheRouteIsMetNotPassed() {
        // inside six blocks it is a charge even though it has not seen us yet
        assertEquals(CHARGE, new CombatPolicy().decide(0, walking(List.of(skeleton(1, 5.5, 0, false)), AHEAD, QUIET)).verdict());
        // a zombie this far off the route is a stroll, a skeleton a few blocks wider than that is not
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 3.5, 7)), AHEAD, QUIET)).verdict());
        assertEquals(CHARGE, new CombatPolicy().decide(0, walking(List.of(skeleton(1, 3.5, 7, false)), AHEAD, QUIET)).verdict());
        // and one well clear of it that cannot see us stays somebody else's
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(skeleton(1, 9, 0, false)), AHEAD, QUIET)).verdict());
    }

    @Test
    public void aSkeletonDoesNotMakeAnUnarmouredBotRunButAPileDoes() {
        // the isInDanger rule: vulnerable (no armor at hp 17) and one shooter close is a charge, not a flee
        assertTrue(CombatPolicy.vulnerable(0, 17));
        assertFalse(CombatPolicy.dangerousCompany(0, 1));
        assertFalse(CombatPolicy.dangerousCompany(0, 2));
        assertTrue(CombatPolicy.dangerousCompany(0, 3));
        // anything that walks at us still counts
        assertTrue(CombatPolicy.dangerousCompany(1, 0));
    }

    @Test
    public void vulnerableIsTheOldRule() {
        assertTrue(CombatPolicy.vulnerable(0, 17.5f));
        assertFalse(CombatPolicy.vulnerable(0, 18));
        assertTrue(CombatPolicy.vulnerable(9, 9));
        assertFalse(CombatPolicy.vulnerable(10, 9));
        assertTrue(CombatPolicy.vulnerable(15, 2));
        assertFalse(CombatPolicy.vulnerable(16, 2));
    }

    // ---- gear

    @Test
    public void aShieldIsOneMoreMobNotTwentyMore() {
        // naked: one mob and that is the end of it
        assertEquals(1, CombatPolicy.standCapacity(0, 0, false));
        assertEquals(2, CombatPolicy.standCapacity(0, 0, true));
        // iron kit would say eight, a shield would have said twenty-eight
        assertEquals(CombatPolicy.STAND_MAX, CombatPolicy.standCapacity(15, 3, false));
        assertEquals(CombatPolicy.STAND_MAX, CombatPolicy.standCapacity(20, 4, true));
    }

    // ---- the travel latch

    @Test
    public void walkingForASecondAlongAPathIsTravelling() {
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 30; now++) t.update(now, now * 0.25, 0, true);
        assertTrue(t.travelling(30, 7.5, 0));
    }

    @Test
    public void standingStillOnAPathIsNotTravelling() {
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 30; now++) t.update(now, 0, 0, true);
        assertFalse(t.travelling(30, 0, 0));
    }

    @Test
    public void theFirstStepOfAnythingIsNotYet() {
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 6; now++) t.update(now, now * 0.5, 0, true);
        assertFalse(t.travelling(6, 3, 0));
    }

    @Test
    public void runningWithoutAPathIsNotTravelling() {
        // fleeing is not a journey
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 30; now++) t.update(now, now * 0.25, 0, false);
        assertFalse(t.travelling(30, 7.5, 0));
    }

    @Test
    public void theLatchFadesWhenTheUserTaskLosesTheTick() {
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 30; now++) t.update(now, now * 0.25, 0, true);
        for (long now = 31; now <= 40; now++) t.update(now, now * 0.25, 0, false);
        assertTrue(t.travelling(40, 10, 0));
        t.update(41, 10.25, 0, false);
        assertFalse(t.travelling(41, 10.25, 0));
    }

    @Test
    public void aNewUserTaskForgetsTheOldRoute() {
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 30; now++) t.update(now, now * 0.25, 0, true);
        t.setUpcoming(List.of(new Point(1, 2, 3)));
        t.clear();
        assertFalse(t.travelling(30, 7.5, 0));
        assertTrue(t.upcoming().isEmpty());
    }
}
