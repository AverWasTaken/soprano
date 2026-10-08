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

    // a fight that is already on: every one of these has hit us or been hit, so none of them is a bystander
    private static List<Mob> engaged(List<Mob> mobs) {
        List<Mob> out = new ArrayList<>(mobs.size());
        for (Mob mob : mobs) out.add(mob.withHitUs());
        return out;
    }

    private static Scene fighting(List<Mob> mobs) {
        return new Scene(engaged(mobs), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE);
    }

    // the same fight with a shield to stand behind
    private static Scene fightingShielded(List<Mob> mobs) {
        return new Scene(engaged(mobs), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, 20f, true);
    }

    // a task that is busy with something other than walking (mining, smelting): nobody has touched anybody
    private static Scene working(List<Mob> mobs) {
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
    public void aLoneZombieIsAFightTheGearMathsDoesNotGetToRunFrom() {
        // the live bug: "fighting 1 zombie (one at a time)" and then the chain ran anyway, because standCapacity of a naked
        // bot is 1 and one mob is not less than one
        Decision d = new CombatPolicy().decide(0, fighting(List.of(zombie(1, 2, 0))));
        assertEquals(FIGHT_ONE, d.verdict());
        assertEquals(1, CombatPolicy.standCapacity(0, 0, false));
        assertTrue(CombatPolicy.fightsLoneMelee(d.verdict(), 1, true));
    }

    @Test
    public void thePolicyOnlyOverrulesTheGearForOneSlowMeleeMob() {
        assertFalse("a crowd is not a lone mob", CombatPolicy.fightsLoneMelee(FIGHT_ONE, 2, true));
        assertFalse("a spider or a skeleton is not a lone zombie", CombatPolicy.fightsLoneMelee(FIGHT_ONE, 1, false));
        assertFalse("kiting stays kiting", CombatPolicy.fightsLoneMelee(KITE, 1, true));
        assertFalse(CombatPolicy.fightsLoneMelee(STAND, 1, true));
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
        Scene s = new Scene(engaged(ring(4, 5)), QUIET, false, List.of(), 0, 0, 5, GRACE);
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
    public void twoOnUsWithNothingToStandBehindIsARun() {
        Decision d = new CombatPolicy().decide(0, fighting(List.of(zombie(1, 2, 0), zombie(2, 0, 2))));
        assertEquals(KITE, d.verdict());
        assertTrue(d.kiting());
        assertFalse(d.shield());
        assertEquals("two on us, no shield", d.why());
    }

    @Test
    public void twoOnUsWithAShieldIsAStand() {
        Decision d = new CombatPolicy().decide(0, fightingShielded(List.of(zombie(1, 2, 0), zombie(2, 0, 2))));
        assertEquals(STAND, d.verdict());
        assertTrue(d.shield());
        assertEquals("shield up", d.why());
    }

    @Test
    public void aShieldDoesNotSaveACrowdFromTheRun() {
        assertEquals(KITE, new CombatPolicy().decide(0, fightingShielded(ring(6, 5))).verdict());
    }

    @Test
    public void twoOnUsButOneOfThemIsFastIsNotARun() {
        // a baby zombie outruns us, so running from the pair gets us hit in the back. the slow one alone is not a pile
        Decision d = new CombatPolicy().decide(0, fighting(List.of(zombie(1, 2, 0), spider(2, 0, 2))));
        assertEquals(STAND, d.verdict());
        assertEquals(2, d.near());
    }

    @Test
    public void twoOnUsButNowhereToRunIsAStandAnyway() {
        CombatPolicy policy = new CombatPolicy();
        Scene pair = fighting(List.of(zombie(1, 2, 0), zombie(2, 0, 2)));
        assertEquals(KITE, policy.decide(0, at(pair, 100, 100)).verdict());
        // a window later we are still where we were: a dead end. the retreat has nowhere to go
        Decision d = policy.decide(CombatPolicy.KITE_WINDOW, at(pair, 100.5, 100));
        assertEquals(STAND, d.verdict());
        assertTrue(d.cornered());
        assertTrue(d.shield());
        // and it does not start again for a while
        assertEquals(STAND, policy.decide(CombatPolicy.KITE_WINDOW + 50, at(pair, 100.5, 100)).verdict());
        assertEquals(KITE, policy.decide(CombatPolicy.KITE_WINDOW + CombatPolicy.KITE_GIVE_UP + 1, at(pair, 100.5, 100)).verdict());
    }

    @Test
    public void thePairStringsOutAndTheFrontOneGetsFought() {
        CombatPolicy policy = new CombatPolicy();
        assertEquals(KITE, policy.decide(0, fighting(List.of(zombie(1, 2, 0), zombie(2, 0, 2)))).verdict());
        // one fell back a few blocks: one on us, which is a fight we can take
        Decision d = policy.decide(CombatPolicy.MIN_DWELL, fighting(List.of(zombie(1, 2, 0), zombie(2, 0, 4))));
        assertEquals(FIGHT_ONE, d.verdict());
        assertTrue(d.hold());
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
    public void aBusyTaskPassesAZombieThatIsNotCloseYet() {
        // mining, smelting, looting: no route to keep clear of, so it is just the strike range
        Decision d = new CombatPolicy().decide(0, working(List.of(zombie(1, 6, 0))));
        assertEquals(IGNORE, d.verdict());
        assertTrue(d.ignored().contains(1));
        assertEquals("busy and they are not close", d.why());
        assertEquals(IGNORE, new CombatPolicy().decide(0, working(List.of(zombie(1, 2.6, 0)))).verdict());
        // and it stops being somebody else's the moment it is on us
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, working(List.of(zombie(1, 2.5, 0)))).verdict());
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, working(List.of(zombie(1, 1, 0)))).verdict());
    }

    @Test
    public void aStaleRouteDoesNotMakeABusyTaskFightEverything() {
        // the latched path from before we stopped to mine says "zombie on the route", the task is not walking it
        Scene s = new Scene(List.of(zombie(1, 0.5, 6)), QUIET, false, AHEAD, 0, 0, THRESHOLD, GRACE);
        assertEquals(IGNORE, new CombatPolicy().decide(0, s).verdict());
    }

    @Test
    public void aBusyTaskStillRunsFromAPileBeforeItIsOnTop() {
        // three zombies at five blocks: waiting until they are inside the strike range is a bad time to start running
        assertEquals(KITE, new CombatPolicy().decide(0, working(ring(3, 5))).verdict());
        assertEquals(IGNORE, new CombatPolicy().decide(0, working(ring(2, 5))).verdict());
    }

    @Test
    public void aBusyTaskStillFightsFastMobsAndWatchesCreepersAndShooters() {
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, working(List.of(spider(1, 6, 0)))).verdict());
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, working(List.of(creeper(1, 6, 0)))).verdict());
        assertEquals(CHARGE, new CombatPolicy().decide(0, working(List.of(skeleton(1, 12, 0, true)))).verdict());
    }

    @Test
    public void onlyTheMobThatHitUsLosesItsStroll() {
        // two zombies off to the side, one of them bit us. the other one is still just a zombie on a hillside
        Mob biter = zombie(1, 6, 0).withHitUs();
        Mob bystander = zombie(2, -6, 0);
        Decision d = new CombatPolicy().decide(0, walking(List.of(biter, bystander), AHEAD, 5));
        assertEquals(FIGHT_ONE, d.verdict());
        assertFalse(d.ignored().contains(1));
        assertTrue(d.ignored().contains(2));
        // being hurt by something we can't put a name to (lava, a fall) is nobody's grudge
        assertEquals(IGNORE, new CombatPolicy().decide(0, walking(List.of(zombie(1, 6, 0)), AHEAD, 5)).verdict());
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
    public void walkingPastIsStillTheSafeMoveAtLowHealth() {
        // the hillside is not the thing that kills us at hp 3, standing next to it is
        List<Mob> hill = List.of(zombie(1, 6, 0));
        for (float hp : new float[]{20, CombatPolicy.LOW_HP + 1, CombatPolicy.LOW_HP, 3}) {
            Decision d = new CombatPolicy().decide(0, strollingAt(hill, hp));
            assertEquals("hp " + hp, IGNORE, d.verdict());
            assertTrue("hp " + hp, d.ignored().contains(1));
        }
        // a busy task passes it too
        Scene busy = new Scene(hill, QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, 3);
        assertEquals(IGNORE, new CombatPolicy().decide(0, busy).verdict());
    }

    @Test
    public void lowHealthWithAZombieOnUsIsARunNotATrade() {
        Decision d = new CombatPolicy().decide(0, withHealth(List.of(zombie(1, 2, 0)), 4));
        assertEquals(KITE, d.verdict());
        assertEquals("low hp", d.why());
        assertFalse(d.shield());
        // the line itself counts
        assertEquals(KITE, new CombatPolicy().decide(0, withHealth(List.of(zombie(1, 2, 0)), CombatPolicy.LOW_HP)).verdict());
        // one point of health up is the old single fight
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, withHealth(List.of(zombie(1, 2, 0)), CombatPolicy.LOW_HP + 1)).verdict());
    }

    @Test
    public void lowHealthRunsEvenWithAShield() {
        Scene s = new Scene(engaged(List.of(zombie(1, 2, 0))), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, 3, true);
        assertEquals(KITE, new CombatPolicy().decide(0, s).verdict());
    }

    @Test
    public void lowHealthWithNothingInContactIsNotARunByItself() {
        // the flee stance and the latch handle the rest, the verdict only turns contact into feet
        assertEquals(FIGHT_ONE, new CombatPolicy().decide(0, withHealth(List.of(zombie(1, 5, 0)), 4)).verdict());
    }

    @Test
    public void theLowHealthRunKeepsGoingAfterHealthTicksUpAndLeavesNoHold() {
        CombatPolicy policy = new CombatPolicy();
        assertEquals(KITE, policy.decide(0, withHealth(List.of(zombie(1, 2, 0)), 4)).verdict());
        // hp 9 on a heal tick, still latched, still in contact: still going
        assertEquals(KITE, policy.decide(CombatPolicy.MIN_DWELL, withHealth(List.of(zombie(1, 2, 0)), 9)).verdict());
        // out of contact (latched still): the run is over, and nobody is told to wait around for the stragglers
        long later = CombatPolicy.LOW_HP_LATCH - 10;
        Decision d = policy.decide(later, withHealth(List.of(zombie(1, 6, 0)), 9));
        assertTrue(policy.lowHpLatched(later));
        assertEquals(FIGHT_ONE, d.verdict());
        assertFalse(d.hold());
        // the same run at full health does get the hold
        CombatPolicy healthy = new CombatPolicy();
        healthy.decide(0, fighting(ring(6, 5)));
        assertTrue(healthy.decide(CombatPolicy.MIN_DWELL, fighting(List.of(zombie(1, 5, 0)))).hold());
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
        return new Scene(engaged(mobs), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, hp);
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
    public void twoOnUsIsARunOneOnUsIsStillACharge() {
        List<Mob> pile = List.of(zombie(1, 2, 0), zombie(2, 0, 2), skeleton(3, 9, 0, true));
        assertEquals(KITE, new CombatPolicy().decide(0, fighting(pile)).verdict());
        assertEquals(STAND, new CombatPolicy().decide(0, fightingShielded(pile)).verdict());
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
        // now it is a melee: feet without a shield, the shield with one
        assertEquals(STAND, new CombatPolicy().decide(0, fightingShielded(pile)).verdict());
        assertEquals(KITE, policy.decide(CombatPolicy.CHARGE_COMMIT, fighting(pile)).verdict());
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
        // nothing carried over: two zombies on us at the start of a charge is a melee, not a charge
        List<Mob> pile = List.of(skeleton(1, 10, 0, true), zombie(2, 2, 0), zombie(3, 0, 2));
        assertEquals(KITE, policy.decide(2, fighting(pile)).verdict());
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

    // ---- the hand-off

    // the chain asks once a tick, so the hold gets its chance to remember a real yes
    private static CombatPolicy.TravelTracker walkedThirty() {
        CombatPolicy.TravelTracker t = new CombatPolicy.TravelTracker();
        for (long now = 0; now <= 30; now++) {
            t.update(now, now * 0.25, 0, true, false);
            t.travelling(now, now * 0.25, 0);
        }
        return t;
    }

    @Test
    public void mobDefenseTakingTheWheelDoesNotMakeUsStopTravelling() {
        CombatPolicy.TravelTracker t = walkedThirty();
        boolean everyTick = true;
        for (long now = 31; now < 31 + CombatPolicy.TRAVEL_HOLD; now++) {
            t.update(now, now * 0.25, 0, false, true);
            everyTick &= t.travelling(now, now * 0.25, 0);
        }
        assertTrue("the answer from before the hand-off held the whole time", everyTick);
    }

    @Test
    public void theHoldRunsOutEventually() {
        CombatPolicy.TravelTracker t = walkedThirty();
        long now = 31;
        for (; now < 31 + 3 * CombatPolicy.TRAVEL_HOLD; now++) {
            t.update(now, now * 0.25, 0, false, true);
            t.travelling(now, now * 0.25, 0);
        }
        assertFalse("a fight that long is not a journey", t.travelling(now, now * 0.25, 0));
    }

    @Test
    public void theUserTaskStandingStillIsNotAHandOff() {
        // it still has the wheel, there is just no path: that fades on the old schedule, the hold is for a real hand-off
        CombatPolicy.TravelTracker t = walkedThirty();
        for (long now = 31; now <= 45; now++) {
            t.update(now, now * 0.25, 0, false, false);
            t.travelling(now, now * 0.25, 0);
        }
        assertFalse(t.travelling(45, 45 * 0.25, 0));
    }

    // ---- hysteresis

    // the zombie that got knocked back to 3.5 blocks while we were on our way somewhere else
    private static Scene knockedBack(double dx) {
        return walking(List.of(zombie(1, dx, 0)), List.of(new Point(-5, 0, 0)), QUIET);
    }

    @Test
    public void aFightOnceChosenIsKeptForAWhile() {
        // the live flap: fighting 1 zombie / passing 1 zombie, on our way, several times a second
        assertEquals("a fresh policy walks past it", IGNORE, new CombatPolicy().decide(0, knockedBack(3.5)).verdict());
        CombatPolicy policy = new CombatPolicy();
        assertEquals(FIGHT_ONE, policy.decide(0, working(List.of(zombie(1, 2, 0)))).verdict());
        assertEquals(FIGHT_ONE, policy.decide(1, knockedBack(3.5)).verdict());
        assertEquals(FIGHT_ONE, policy.decide(CombatPolicy.FIGHT_LATCH - 1, knockedBack(3.5)).verdict());
        assertEquals("and then it is a stroll again", IGNORE, policy.decide(CombatPolicy.FIGHT_LATCH, knockedBack(3.5)).verdict());
    }

    @Test
    public void beingOnTheZombieKeepsTheFightGoing() {
        CombatPolicy policy = new CombatPolicy();
        for (long now = 0; now < 3 * CombatPolicy.FIGHT_LATCH; now += 10) {
            assertEquals("at " + now, FIGHT_ONE, policy.decide(now, working(List.of(zombie(1, 2, 0)))).verdict());
        }
        assertEquals(FIGHT_ONE, policy.decide(3 * CombatPolicy.FIGHT_LATCH + 5, knockedBack(3.5)).verdict());
    }

    @Test
    public void aFightEndsWhenTheZombieLeaves() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, working(List.of(zombie(1, 2, 0))));
        assertEquals(IGNORE, policy.decide(1, knockedBack(CombatPolicy.FIGHT_LEAVE + 1)).verdict());
    }

    @Test
    public void aFightEndsWhenTheZombieDies() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, working(List.of(zombie(1, 2, 0))));
        // gone from the scene, and then a different (or respawned) mob that happens to carry the id is a stranger
        assertEquals(IGNORE, policy.decide(1, working(List.of())).verdict());
        assertEquals(IGNORE, policy.decide(2, knockedBack(3.5)).verdict());
    }

    @Test
    public void lowHpStillOverridesTheFightLatch() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, working(List.of(zombie(1, 2, 0))));
        assertEquals(KITE, policy.decide(1, withHealth(List.of(zombie(1, 2, 0)), 4)).verdict());
    }

    @Test
    public void aPassByIsKeptUntilItIsOnTop() {
        List<Mob> far = List.of(zombie(1, 5, 0));
        CombatPolicy policy = new CombatPolicy();
        assertEquals(IGNORE, policy.decide(0, knockedBack(5)).verdict());
        // the route swung round toward it. a fresh policy would fight, ours has already made its mind up
        Scene towardIt = walking(far, List.of(new Point(5, 0, 0)), QUIET);
        assertEquals("fresh", FIGHT_ONE, new CombatPolicy().decide(0, towardIt).verdict());
        assertEquals(IGNORE, policy.decide(1, towardIt).verdict());
        // and the user task losing the wheel (not travelling any more) changes nothing
        assertEquals(IGNORE, policy.decide(2, working(far)).verdict());
        // within reach it is a fight, whatever we decided
        assertEquals(FIGHT_ONE, policy.decide(3, working(List.of(zombie(1, 2, 0)))).verdict());
    }

    @Test
    public void aPassByEndsWhenItBitesUs() {
        CombatPolicy policy = new CombatPolicy();
        policy.decide(0, knockedBack(5));
        assertEquals(FIGHT_ONE, policy.decide(1, walking(List.of(zombie(1, 5, 0).withHitUs()), List.of(new Point(-5, 0, 0)), 0)).verdict());
    }

    @Test
    public void aPileFormingEndsThePassByToo() {
        CombatPolicy policy = new CombatPolicy();
        List<Point> away = List.of(new Point(0, 0, -9));
        assertEquals(IGNORE, policy.decide(0, walking(ring(3, 5.5), away, QUIET)).verdict());
        assertFalse("a pile is never somebody else's business once we stop travelling",
                policy.decide(1, working(ring(3, 5.5))).verdict() == IGNORE);
    }

    // ---- danger

    private static CombatPolicy.Danger danger(float hp, int armor, boolean food, Decision decision, int melee, int shooters,
                                              int capacity) {
        return new CombatPolicy.Danger(hp, armor, food, false, false, false, true, decision, melee, shooters, capacity);
    }

    private static Decision stand(String why) {
        return new Decision(STAND, java.util.Set.of(), 1, 2, false, why);
    }

    @Test
    public void oneZombieAtHp17IsNotDangerWithOrWithoutAShield() {
        // the live bug: "passing 1 zombie ... (hp 17, armor 0, shield)" and then NIGERUNDAYOO
        List<Mob> lone = List.of(zombie(1, 5, 0));
        for (boolean shield : new boolean[]{true, false}) {
            Scene passing = new Scene(lone, QUIET, true, List.of(new Point(-5, 0, 0)), 0, 0, THRESHOLD, GRACE, 17f, shield);
            Decision pass = new CombatPolicy().decide(0, passing);
            assertEquals(IGNORE, pass.verdict());
            assertFalse("passing, shield " + shield, CombatPolicy.inDanger(danger(17, 0, true, pass, 0, 0, 1)));

            Scene fight = new Scene(engaged(List.of(zombie(1, 2, 0))), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, 17f, shield);
            Decision one = new CombatPolicy().decide(0, fight);
            assertEquals(FIGHT_ONE, one.verdict());
            assertTrue(CombatPolicy.vulnerable(0, 17));
            assertFalse("fighting, shield " + shield, CombatPolicy.inDanger(danger(17, 0, true, one, 1, 0, 1)));
        }
    }

    @Test
    public void aCrowdWithNoShieldIsTheKitesBusinessNotTheDangerRuns() {
        Scene crowd = new Scene(engaged(ring(3, 4)), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE, 17f, false);
        Decision d = new CombatPolicy().decide(0, crowd);
        assertEquals(KITE, d.verdict());
        assertFalse(CombatPolicy.inDanger(danger(17, 0, true, d, 3, 0, 1)));
    }

    @Test
    public void hpNineWithFoodAndSomethingOnUsIsDanger() {
        Decision one = new CombatPolicy().decide(0, withHealth(List.of(zombie(1, 5, 0)), 9));
        assertTrue(CombatPolicy.inDanger(danger(9, 0, true, one, 1, 0, 1)));
        // no food and a lone zombie is a fight, flee has its own stance for it
        assertFalse(CombatPolicy.inDanger(danger(9, 0, false, new Decision(FIGHT_ONE, java.util.Set.of(), 1, 1, false), 1, 0, 1)));
    }

    @Test
    public void hpNineWithFoodAndNobodyAroundIsNotDanger() {
        // it used to be, and the run it started was finished before it began
        assertFalse(CombatPolicy.inDanger(danger(9, 0, true, new Decision(IGNORE, java.util.Set.of(), 0, 0, false), 0, 0, 1)));
    }

    @Test
    public void witherAndPoisonAreDangerWhateverTheVerdict() {
        Decision calm = new Decision(IGNORE, java.util.Set.of(), 0, 0, false);
        assertTrue(CombatPolicy.inDanger(new CombatPolicy.Danger(20, 20, false, false, false, true, true, calm, 0, 0, 4)));
    }

    @Test
    public void aStandAgainstMoreThanTheGearTakesIsDangerUnlessWeAreCornered() {
        assertEquals(2, CombatPolicy.standCapacity(0, 0, true));
        assertTrue(CombatPolicy.inDanger(danger(17, 0, true, stand("shield up"), 3, 0, 2)));
        assertFalse(CombatPolicy.inDanger(danger(17, 0, true, stand("shield up"), 2, 0, 2)));
        assertFalse("the run already went nowhere", CombatPolicy.inDanger(danger(17, 0, true, stand("nowhere to run"), 3, 0, 2)));
    }

    @Test
    public void aFiringLineIsDangerButOneOrTwoShootersAreACharge() {
        Decision d = new Decision(FIGHT_ONE, java.util.Set.of(), 0, 0, false);
        assertTrue(CombatPolicy.inDanger(danger(17, 0, true, d, 0, 3, 1)));
        assertFalse(CombatPolicy.inDanger(danger(17, 0, true, d, 0, 2, 1)));
    }

    @Test
    public void wellDressedAndHealthyIsNeverDangerFromACount() {
        assertFalse(CombatPolicy.inDanger(danger(20, 20, true, stand("shield up"), 6, 0, 4)));
    }

    @Test
    public void withThePolicyOffItIsTheOldRule() {
        Decision none = new Decision(STAND, java.util.Set.of(), 0, 0, false);
        assertTrue(CombatPolicy.inDanger(new CombatPolicy.Danger(17, 0, true, false, false, false, false, none, 1, 0, 1)));
    }

    @Test
    public void outrunningThemIsNotARunToo() {
        Decision outrun = new Decision(IGNORE, java.util.Set.of(1), 0, 0, false, "outrunning");
        assertTrue(outrun.outrunning());
        assertFalse(CombatPolicy.inDanger(danger(9, 0, true, outrun, 3, 0, 1)));
    }

    // ---- running the way we were going

    // a straight route ahead (+z), one block a step, ten steps
    private static List<Point> ahead() {
        List<Point> out = new ArrayList<>();
        for (int i = 1; i <= 10; i++) out.add(new Point(0, 0, i));
        return out;
    }

    @Test
    public void aMobBehindUsIsNotInTheWay() {
        assertTrue(CombatPolicy.canOutrun(ahead(), List.of(zombie(1, 0, -4), zombie(2, 1, -5), zombie(3, -1, -5))));
    }

    @Test
    public void aMobOnTheRouteIsInTheWay() {
        assertFalse(CombatPolicy.canOutrun(ahead(), List.of(zombie(1, 0, 6))));
    }

    @Test
    public void aMobThatCutsTheCornerIsInTheWay() {
        // six off to the side and eight along: it meets us there at a walk
        assertFalse(CombatPolicy.canOutrun(ahead(), List.of(zombie(1, 6, 8))));
        // twelve off to the side it does not
        assertTrue(CombatPolicy.canOutrun(ahead(), List.of(zombie(1, 12, 5))));
    }

    @Test
    public void thingsThatOutrunOrShootUsAreNeverOutrun() {
        assertFalse(CombatPolicy.canOutrun(ahead(), List.of(spider(1, 0, -8))));
        assertFalse(CombatPolicy.canOutrun(ahead(), List.of(skeleton(1, 0, -8, true))));
        assertFalse("no route is no route", CombatPolicy.canOutrun(List.of(), List.of(zombie(1, 0, -8))));
    }

    @Test
    public void aCrowdBehindUsOnTheWayToSomewhereIsLeftToTheUserTask() {
        List<Mob> behind = List.of(zombie(1, -1, -4), zombie(2, 0, -5), zombie(3, 1, -4));
        Scene s = new Scene(engaged(behind), QUIET, true, ahead(), 0, 0, THRESHOLD, GRACE);
        Decision d = new CombatPolicy().decide(0, s);
        assertEquals(IGNORE, d.verdict());
        assertTrue(d.outrunning());
        assertEquals("every one of them is handed back", 3, d.ignored().size());
    }

    @Test
    public void aCrowdOnTheRouteIsRunFromWithAReason() {
        List<Mob> inFront = List.of(zombie(1, -1, 5), zombie(2, 0, 6), zombie(3, 1, 5));
        Scene s = new Scene(engaged(inFront), QUIET, true, ahead(), 0, 0, THRESHOLD, GRACE);
        Decision d = new CombatPolicy().decide(0, s);
        assertEquals(KITE, d.verdict());
        assertEquals("they're in the way", d.why());
    }

    @Test
    public void aCrowdWithNowhereInParticularToGoIsStillAKite() {
        Decision d = new CombatPolicy().decide(0, new Scene(engaged(ring(3, 4)), QUIET, false, List.of(), 0, 0, THRESHOLD, GRACE));
        assertEquals(KITE, d.verdict());
        assertFalse(d.outrunning());
    }
}
