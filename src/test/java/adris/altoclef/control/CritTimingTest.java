package adris.altoclef.control;

import adris.altoclef.control.CritTiming.Sample;
import adris.altoclef.control.CritTiming.Step;
import org.junit.Test;

import static org.junit.Assert.*;

public class CritTimingTest {

    // standing next to a mob, weapon a few ticks from ready, nothing in the way
    private static Sample ready() {
        Sample s = new Sample();
        s.ticksToFull = 4;
        return s;
    }

    // up in the air, not falling yet
    private static Sample rising(double ticksToFull) {
        Sample s = new Sample();
        s.onGround = false;
        s.ticksToFull = ticksToFull;
        return s;
    }

    private static Sample falling(double ticksToFull) {
        Sample s = rising(ticksToFull);
        s.falling = true;
        return s;
    }

    private static Sample landed() {
        Sample s = new Sample();
        s.ticksToFull = 0;
        return s;
    }

    @Test
    public void jumpsWhenTheCooldownIsAboutToFillAndTheMobIsInReach() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        assertTrue(t.inFlight(0));
    }

    @Test
    public void jumpsRightAtTheLeadAndNotBefore() {
        Sample s = ready();
        s.ticksToFull = CritTiming.LEAD_TICKS;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        s.ticksToFull = CritTiming.LEAD_TICKS + 0.5;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void aFullCooldownOnTheGroundSwingsNowInsteadOfHopping() {
        // a hop is 7 ticks up before anything falls, which is past the 4 tick cap on holding a swing. the next one is timed
        Sample s = ready();
        s.ticksToFull = 0;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.ticksToFull = 0.5;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    // ---- where the mob is

    private static Sample at(double gap) {
        Sample s = ready();
        s.reachGap = gap;
        s.reach = 3.0;
        return s;
    }

    @Test
    public void noHopWhenTheMobIsAtTheEdgeOfReach() {
        // 3.5 is not even in vanilla reach (3.0), 2.9 is in reach but would be gone by the time we come down
        CritTiming far = new CritTiming();
        assertEquals(Step.APPROACH, far.step(0, at(3.5)));
        assertFalse(far.inFlight(0));
        CritTiming edge = new CritTiming();
        assertEquals(Step.APPROACH, edge.step(0, at(2.9)));
        assertFalse(edge.inFlight(0));
    }

    @Test
    public void hopsOnceTheMobIsInsideReachMinusTheMargin() {
        assertEquals(Step.JUMP, new CritTiming().step(0, at(2.4)));
        assertEquals(Step.JUMP, new CritTiming().step(0, at(2.5)));
        assertEquals(Step.APPROACH, new CritTiming().step(0, at(2.6)));
    }

    @Test
    public void theMarginFollowsTheReachWeActuallyHave() {
        Sample longArms = at(3.4);
        longArms.reach = 4.0;
        assertEquals(Step.JUMP, new CritTiming().step(0, longArms));
        Sample shortArms = at(2.4);
        shortArms.reach = 2.5;
        assertEquals(Step.APPROACH, new CritTiming().step(0, shortArms));
    }

    @Test
    public void noHopAtAMobThatIsWalkingAway() {
        Sample s = at(2.0);
        s.retreatSpeed = 0.2;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.retreatSpeed = CritTiming.MAX_RETREAT;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        // coming at us is the good kind of moving
        s.retreatSpeed = -0.2;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    @Test
    public void aFarMobThatIsAlsoLeavingIsNotChased() {
        Sample s = at(3.2);
        s.retreatSpeed = 0.25;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void approachNeverAppearsWithoutAHopBeingDue() {
        // cooldown still far off: nothing to time yet. cooldown full: the plain swing owns the tick
        Sample early = at(3.2);
        early.ticksToFull = CritTiming.LEAD_TICKS + 1;
        assertEquals(Step.PLAIN, new CritTiming().step(0, early));
        Sample full = at(3.2);
        full.ticksToFull = 0;
        assertEquals(Step.PLAIN, new CritTiming().step(0, full));
        // and every guard that cancels a hop cancels walking up for one too
        Sample pressured = at(3.2);
        pressured.meleeNear = 2;
        assertEquals(Step.PLAIN, new CritTiming().step(0, pressured));
        Sample wet = at(3.2);
        wet.inFluid = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, wet));
    }

    @Test
    public void backOffSitsOutWhileAHopIsOn() {
        assertTrue(CritTiming.shouldBackOff(true, false));
        assertFalse(CritTiming.shouldBackOff(true, true));
        assertFalse(CritTiming.shouldBackOff(false, false));
        assertFalse(CritTiming.shouldBackOff(false, true));
    }

    @Test
    public void firstFallingTickInReachSwingsEvenThoughTheMobDriftedABit() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, at(2.2)));
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(Math.max(0, 4 - tick))));
        }
        // the moment it counts, not a tick later and not on the next hop
        Sample down = falling(0);
        down.reachGap = 2.9;
        assertEquals(Step.SWING, t.step(7, down));
    }

    @Test
    public void mobLeavingReachMidHopStillGetsAPlainHitOnLanding() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, at(2.2)));
        assertEquals(Step.WAIT, t.step(1, rising(3)));
        // gone while we were up: the hop is dropped right here
        Sample gone = falling(0);
        gone.inReach = false;
        assertEquals(Step.PLAIN, t.step(2, gone));
        assertFalse(t.inFlight(2));
        // back in reach on the tick we touch down: a plain swing, not a wait for another hop
        assertEquals(Step.PLAIN, t.step(3, landed()));
        // and then it is back to hopping
        assertEquals(Step.JUMP, t.step(4, ready()));
    }

    // ---- not under fire

    @Test
    public void noHopWithinTwentyTicksOfBeingHit() {
        Sample s = ready();
        s.ticksSinceHurt = CritTiming.HURT_QUIET_TICKS;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.ticksSinceHurt = CritTiming.HURT_QUIET_TICKS + 1;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    @Test
    public void noHopWithTwoOnUs() {
        Sample s = ready();
        s.meleeNear = 1;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
        s.meleeNear = 2;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
    }

    @Test
    public void noHopAtTenHealthOrLess() {
        Sample s = ready();
        s.health = 10;
        assertEquals(Step.PLAIN, new CritTiming().step(0, s));
        s.health = 10.5f;
        assertEquals(Step.JUMP, new CritTiming().step(0, s));
    }

    @Test
    public void pressureAsOneQuestionForWantsCritTick() {
        assertFalse(CritTiming.underPressure(1000, 1, 20));
        assertTrue(CritTiming.underPressure(20, 0, 20));
        assertTrue(CritTiming.underPressure(1000, 2, 20));
        assertTrue(CritTiming.underPressure(1000, 0, 10));
    }

    @Test
    public void gettingHitMidAirGivesUpTheCrit() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        Sample hit = rising(3);
        hit.ticksSinceHurt = 0;
        assertEquals(Step.PLAIN, t.step(1, hit));
        assertFalse(t.inFlight(1));
        // and the next swing is a plain one instead of another try, same as every other give up
        assertEquals(Step.PLAIN, t.step(2, landed()));
    }

    @Test
    public void aSecondMobArrivingMidAirOrTheHealthDroppingGivesUpToo() {
        CritTiming crowd = new CritTiming();
        crowd.step(0, ready());
        Sample two = rising(3);
        two.meleeNear = 2;
        assertEquals(Step.PLAIN, crowd.step(1, two));
        CritTiming hurting = new CritTiming();
        hurting.step(0, ready());
        Sample low = falling(0);
        low.health = 6;
        assertEquals(Step.PLAIN, hurting.step(1, low));
    }

    @Test
    public void aFreeCritIsStillTakenUnderPressure() {
        // already coming down with everything lined up costs nothing, no reason to throw it away
        Sample s = falling(0);
        s.meleeNear = 3;
        assertEquals(Step.SWING, new CritTiming().step(0, s));
    }

    // ---- the cap

    @Test
    public void aCrimpedHopNeverHoldsTheSwingMoreThanFourTicksPastFull() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        // still rising, cooldown full from tick 1 on (a wall bonked the jump short, whatever): the cap steps in
        for (int tick = 1; tick <= CritTiming.MAX_READY_WAIT; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(0)));
        }
        assertEquals(Step.SWING, t.step(CritTiming.MAX_READY_WAIT + 1, rising(0)));
        assertFalse(t.inFlight(CritTiming.MAX_READY_WAIT + 1));
    }

    @Test
    public void theCapCountsConsecutiveReadyTicksOnly() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        // ready, not ready, ready: the count restarts, no early swing
        assertEquals(Step.WAIT, t.step(1, rising(0)));
        assertEquals(Step.WAIT, t.step(2, rising(0)));
        assertEquals(Step.WAIT, t.step(3, rising(2)));
        for (int tick = 4; tick <= 7; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(0)));
        }
    }

    @Test
    public void everyGuardTurnsTheJumpIntoAPlainHit() {
        assertPlain(s -> s.inReach = false);
        assertPlain(s -> s.inFluid = true);
        assertPlain(s -> s.onClimbable = true);
        assertPlain(s -> s.blind = true);
        assertPlain(s -> s.riding = true);
        assertPlain(s -> s.usingItem = true);
        assertPlain(s -> s.shielding = true);
        assertPlain(s -> s.lowHeadroom = true);
        assertPlain(s -> s.unsafeFloor = true);
        assertPlain(s -> s.pathing = true);
        assertPlain(s -> s.normalHitKills = true);
        assertPlain(s -> s.enabled = false);
    }

    private static void assertPlain(java.util.function.Consumer<Sample> spoil) {
        Sample s = ready();
        spoil.accept(s);
        CritTiming t = new CritTiming();
        assertEquals(Step.PLAIN, t.step(0, s));
        assertFalse(t.inFlight(0));
    }

    @Test
    public void jumpThenWaitThenSwingOnTheWayDown() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        // up for six ticks, cooldown filling the whole time
        for (int tick = 1; tick <= 6; tick++) {
            assertEquals("tick " + tick, Step.WAIT, t.step(tick, rising(Math.max(0, 4 - tick))));
        }
        // first falling tick with the cooldown full: this is the crit
        assertEquals(Step.SWING, t.step(7, falling(0)));
        assertFalse(t.inFlight(7));
    }

    @Test
    public void fallingWithAnEmptyCooldownKeepsWaiting() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        assertEquals(Step.WAIT, t.step(1, rising(5)));
        assertEquals(Step.WAIT, t.step(2, falling(3)));
        assertEquals(Step.WAIT, t.step(3, falling(1)));
        assertEquals(Step.SWING, t.step(4, falling(0)));
    }

    @Test
    public void neverSwingsWhileRisingEvenWithAFullCooldown() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        assertEquals(Step.WAIT, t.step(1, rising(0)));
        assertEquals(Step.WAIT, t.step(2, rising(0)));
    }

    @Test
    public void mobLeavingReachMidJumpFallsBackToANormalHitNextTime() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample gone = rising(2);
        gone.inReach = false;
        assertEquals(Step.PLAIN, t.step(1, gone));
        assertFalse(t.inFlight(1));
        // the next hit is a plain one, not another hop
        assertEquals(Step.PLAIN, t.step(2, landed()));
        // and then it is back to hopping
        assertEquals(Step.JUMP, t.step(3, ready()));
    }

    @Test
    public void landingWithoutASwingFallsBackToANormalHitNextTime() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        for (int tick = 1; tick <= 8; tick++) {
            t.step(tick, tick < 7 ? rising(30) : falling(30));
        }
        // touched down with the cooldown still not there
        Sample down = landed();
        down.ticksToFull = 20;
        assertEquals(Step.PLAIN, t.step(9, down));
        assertFalse(t.inFlight(9));
        Sample again = ready();
        assertEquals(Step.PLAIN, t.step(10, again));
        assertEquals(Step.JUMP, t.step(11, again));
    }

    @Test
    public void aJumpThatNeverLeavesTheGroundIsAbandoned() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(0, ready()));
        // the key press takes a tick or two to show up
        assertEquals(Step.WAIT, t.step(1, ready()));
        assertEquals(Step.WAIT, t.step(2, ready()));
        assertEquals(Step.PLAIN, t.step(3, ready()));
        assertFalse(t.inFlight(3));
        assertEquals(Step.PLAIN, t.step(4, ready()));
    }

    @Test
    public void guardsThatShowUpMidAirCancelTheCrit() {
        assertCancelledMidAir(s -> s.shielding = true);
        assertCancelledMidAir(s -> s.usingItem = true);
        assertCancelledMidAir(s -> s.inFluid = true);
        assertCancelledMidAir(s -> s.onClimbable = true);
        assertCancelledMidAir(s -> s.blind = true);
        assertCancelledMidAir(s -> s.riding = true);
        assertCancelledMidAir(s -> s.pathing = true);
    }

    private static void assertCancelledMidAir(java.util.function.Consumer<Sample> spoil) {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample s = falling(0);
        spoil.accept(s);
        assertEquals(Step.PLAIN, t.step(1, s));
        assertFalse(t.inFlight(1));
    }

    @Test
    public void turningTheSettingOffMidAirDropsTheJump() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample off = rising(1);
        off.enabled = false;
        assertEquals(Step.PLAIN, t.step(1, off));
        assertFalse(t.inFlight(1));
        // and no leftover "next one is plain" either
        assertEquals(Step.JUMP, t.step(2, ready()));
    }

    @Test
    public void alreadyFallingWithAFullCooldownIsAFreeCrit() {
        CritTiming t = new CritTiming();
        assertEquals(Step.SWING, t.step(0, falling(0)));
        assertFalse(t.inFlight(0));
    }

    @Test
    public void fallingButNotReadyOrNotAllowedIsJustPlain() {
        assertEquals(Step.PLAIN, new CritTiming().step(0, falling(2)));
        Sample wet = falling(0);
        wet.inFluid = true;
        assertEquals(Step.PLAIN, new CritTiming().step(0, wet));
        Sample far = falling(0);
        far.inReach = false;
        assertEquals(Step.PLAIN, new CritTiming().step(0, far));
        // rising from a knockback is nothing to swing at
        assertEquals(Step.PLAIN, new CritTiming().step(0, rising(0)));
    }

    @Test
    public void aSecondCallerInTheSameTickDoesNotActTwice() {
        CritTiming t = new CritTiming();
        assertEquals(Step.JUMP, t.step(5, ready()));
        assertEquals(Step.WAIT, t.step(5, ready()));
        // a plain answer stays plain for both
        CritTiming p = new CritTiming();
        Sample s = ready();
        s.pathing = true;
        assertEquals(Step.PLAIN, p.step(5, s));
        assertEquals(Step.PLAIN, p.step(5, s));
        // and a swing is only handed out once
        CritTiming w = new CritTiming();
        assertEquals(Step.SWING, w.step(9, falling(0)));
        assertEquals(Step.WAIT, w.step(9, falling(0)));
    }

    @Test
    public void aFlightNobodyIsSteeringAnyMoreGoesStale() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        assertTrue(t.inFlight(2));
        assertFalse(t.inFlight(10));
        // the next ask starts clean, jump included
        assertEquals(Step.JUMP, t.step(10, ready()));
    }

    @Test
    public void aStaleGiveUpDoesNotLeaveAPlainHitOwed() {
        CritTiming t = new CritTiming();
        t.step(0, ready());
        Sample gone = rising(2);
        gone.inReach = false;
        t.step(1, gone);
        // nobody asked for a while, the "next one is plain" promise was about that fight, not this one
        assertEquals(Step.JUMP, t.step(20, ready()));
    }

    @Test
    public void swordCooldownAndAxeCooldownBothFindTheirLead() {
        // a sword is ready every 12.5 ticks, an axe every 25: the lead is in ticks, not in fractions of the cooldown
        Sample sword = ready();
        sword.ticksToFull = 6;
        assertEquals(Step.JUMP, new CritTiming().step(0, sword));
        Sample axe = ready();
        axe.ticksToFull = 18;
        assertEquals(Step.PLAIN, new CritTiming().step(0, axe));
        axe.ticksToFull = 7;
        assertEquals(Step.JUMP, new CritTiming().step(0, axe));
    }
}
