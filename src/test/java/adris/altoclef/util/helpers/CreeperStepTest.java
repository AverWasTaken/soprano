package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CreeperStep.Event;
import adris.altoclef.util.helpers.CreeperStep.Seen;
import java.util.List;
import org.junit.Test;

// the lit creeper preempt: lit and within 4 is a step, anything else is scenery, and the hold always ends
public class CreeperStepTest {

    private static List<Seen> lit(int id, double distance) {
        return List.of(new Seen(id, true, distance, false));
    }

    // a charged one never gets the bait, so these are the defuse's rules
    private static List<Seen> charged(int id, boolean lit, double distance, boolean fusing) {
        return List.of(new Seen(id, lit, distance, false, fusing, true, false));
    }

    private static List<Seen> charged(int id, double distance) {
        return charged(id, true, distance, true);
    }

    @Test
    public void litWithinFourSteps() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3.5), false));
        assertTrue(step.active());
        assertEquals(7, step.creeperId());
        assertTrue(step.shouldLog());
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, CreeperStep.TRIGGER), false));
    }

    @Test
    public void litAtFiveIsNothing() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, lit(7, 5), false));
        assertFalse(step.active());
    }

    @Test
    public void unlitAtTwoIsNothing() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, List.of(new Seen(7, false, 2, true)), true));
        assertFalse(step.active());
    }

    @Test
    public void litAtFiveWithThePathWalkingIntoItSteps() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, List.of(new Seen(7, true, 5, true)), false));
    }

    @Test
    public void picksTheClosestLitOne() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(1, true, 3.9, false), new Seen(2, true, 2, false), new Seen(3, false, 1, false)), false);
        assertEquals(2, step.creeperId());
    }

    @Test
    public void holdsWhileLitEvenPastFour() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        for (int t = 1; t < CreeperStep.HOLD_CAP; t++) {
            assertEquals(Event.NONE, step.step(t, lit(7, 6), false));
            assertTrue(step.active());
        }
    }

    @Test
    public void explodedOrGoneEndsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.END_GONE, step.step(20, List.of(), false));
        assertFalse(step.active());
    }

    @Test
    public void unswellingEndsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.END_UNLIT, step.step(10, List.of(new Seen(7, false, 6, false)), false));
        assertFalse(step.active());
    }

    // a creeper that lost its line to us goes unlit with the swell still up, and relights the moment we walk back in
    @Test
    public void aDeflatingFuseCloseByKeepsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        assertFalse(step.baiting());
        for (int t = 1; t < CreeperStep.HOLD_CAP; t++) {
            assertEquals("tick " + t, Event.NONE, step.step(t, charged(7, false, CreeperStep.HOLD_RADIUS, true), false));
            assertTrue(step.active());
        }
    }

    @Test
    public void aDrainedFuseEndsTheHoldAtOnce() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.END_UNLIT, step.step(10, List.of(new Seen(7, false, 3, false, false)), false));
        assertFalse(step.active());
    }

    @Test
    public void aDeflatingFuseFarAwayEndsTheHold() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        assertEquals(Event.END_UNLIT, step.step(10, charged(7, false, CreeperStep.HOLD_RADIUS + 0.1, true), false));
    }

    @Test
    public void aFuseThatNeverDrainsStillHitsTheCap() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        // lit for a while, then deflating and never draining: the cap ends the hold
        assertEquals(Event.NONE, step.step(10, charged(7, false, 2, true), false));
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, charged(7, false, 2, true), false));
        assertFalse(step.active());
    }

    @Test
    public void aHoldThatOnlyRanLongBecauseItRelitIsNotBlacklisted() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        for (int t = 1; t < 20; t++) step.step(t, charged(7, 3), false);
        for (int t = 20; t < 35; t++) step.step(t, charged(7, false, 3, true), false);
        for (int t = 35; t < CreeperStep.HOLD_CAP; t++) step.step(t, charged(7, 3), false);
        // the cap lands, but it has only been lit again for fifteen ticks
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, charged(7, 3), false));
        // so its next relight beside us is stepped from again
        assertEquals(Event.START, step.step(CreeperStep.HOLD_CAP + 1, charged(7, 3), false));
    }

    @Test
    public void aCreeperThatSatLitThroughTheWholeCapIsNotHeldAgain() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, charged(7, 3), false));
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP + 1, charged(7, 3), false));
    }

    @Test
    public void twoAndAHalfSecondsIsTheCapAndTheSameOneIsNotHeldAgain() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP - 1, charged(7, 3), false));
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, charged(7, 3), false));
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP + 1, charged(7, 3), false));
        assertFalse(step.active());
        assertEquals(50, CreeperStep.HOLD_CAP);
    }

    // ---- the bait

    @Test
    public void aPlainCreeperGetsTheBait() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3), false));
        assertTrue(step.baiting());
        assertFalse(step.escalate());
    }

    // healing up (RecoverHold) is nothing committed, but a hurt bot standing still for a pop is not the plan: the defuse
    @Test
    public void noBaitWhileHealingUp() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3), false, CombatCommit.Mode.NONE, true, true));
        assertFalse(step.baiting());
        // and one that started baiting drops it once the hold comes on
        CreeperStep other = new CreeperStep();
        other.step(0, lit(7, 3), false, CombatCommit.Mode.NONE, true, false);
        assertTrue(other.baiting());
        other.step(1, lit(7, 3), false, CombatCommit.Mode.NONE, true, true);
        assertFalse(other.baiting());
    }

    // standing at 6.5 while it swells, it loses its line for a moment (unlit, still charged), lights again, then pops
    @Test
    public void theBaitHoldsThroughLitUnlitAndLitAgainUntilItPops() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        for (int t = 1; t < 15; t++) assertEquals(Event.NONE, step.step(t, lit(7, 6.5), false));
        // past HOLD_RADIUS and deflating: the defuse would let go here, the bait does not
        for (int t = 15; t < 20; t++) {
            assertEquals(Event.NONE, step.step(t, List.of(new Seen(7, false, 6.5, false, true)), false));
            assertTrue(step.active());
        }
        for (int t = 20; t < 40; t++) assertEquals(Event.NONE, step.step(t, lit(7, 6.5), false));
        assertEquals(Event.END_GONE, step.step(40, List.of(), false));
        assertFalse(step.active());
    }

    // a long bait (it went out and relit) that turns into the defuse is already past the defuse's cap: it ends, and since it
    // never sat lit through the whole thing it is not blacklisted
    @Test
    public void aLongBaitFlippedToTheDefuseEndsWithoutTheBlacklist() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        for (int t = 1; t < 20; t++) step.step(t, lit(7, 6.5), false, Mode.NONE, true);
        for (int t = 20; t < 30; t++) step.step(t, List.of(new Seen(7, false, 6.5, false, true)), false, Mode.NONE, true);
        for (int t = 30; t < 60; t++) step.step(t, lit(7, 3), false, Mode.NONE, true);
        assertEquals(Event.END_CAP, step.step(60, lit(7, 3), false, Mode.FIGHT, true));
        assertEquals(Event.START, step.step(61, lit(7, 3), false, Mode.FIGHT, true));
    }

    @Test
    public void aBaitThatDrainedIsOver() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.END_UNLIT, step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false));
    }

    @Test
    public void theBaitHasItsOwnCap() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        for (int t = 1; t < CreeperStep.BAIT_CAP; t++) {
            assertEquals("tick " + t, Event.NONE, step.step(t, List.of(new Seen(7, false, 6.5, false, true)), false));
        }
        assertEquals(Event.END_CAP, step.step(CreeperStep.BAIT_CAP, List.of(new Seen(7, false, 6.5, false, true)), false));
        // never sat lit through it, so it is not blacklisted
        assertEquals(Event.START, step.step(CreeperStep.BAIT_CAP + 1, lit(7, 3), false));
    }

    @Test
    public void aChargedOneIsDefused() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false);
        assertTrue(step.active());
        assertFalse(step.baiting());
    }

    @Test
    public void ourStuffCloseToItMeansDefuse() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, true, 3, false, true, false, true)), false);
        assertTrue(step.active());
        assertFalse(step.baiting());
        // the defuse lets go of a far deflating one, same as it always did
        assertEquals(Event.END_UNLIT, step.step(10, List.of(new Seen(7, false, 6.5, false, true, false, true)), false));
    }

    // ---- coming back

    @Test
    public void aBaitThatDidNotTakeAndRelitIsARun() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, step.step(0, lit(7, 3), false, Mode.NONE, true));
        assertFalse(step.escalate());
        assertEquals(Event.END_UNLIT, step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.NONE, true));
        // it walked back in and lit again
        assertEquals(Event.START, step.step(120, lit(7, 2.8), false, Mode.NONE, true));
        assertTrue(step.escalate());
        // the defuse carries us out of the blast while the run gets going
        assertFalse(step.baiting());
        // the hand-off is that one tick
        assertEquals(Event.NONE, step.step(121, lit(7, 3.5), false, Mode.NONE, true));
        assertFalse(step.escalate());
    }

    @Test
    public void aDefusedOneThatCameBackIsARunToo() {
        CreeperStep step = new CreeperStep();
        step.step(0, charged(7, 3), false, Mode.NONE, true);
        assertEquals(Event.END_GONE, step.step(20, List.of(), false, Mode.NONE, true));
        assertEquals(Event.START, step.step(200, charged(7, 3), false, Mode.NONE, true));
        assertTrue(step.escalate());
    }

    @Test
    public void withTheCommitmentOffARepeatIsTheBaitAgain() {
        // nobody to hand a run to, and the defuse is the loop, so the bait gets another go
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, false);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.NONE, false);
        assertEquals(Event.START, step.step(120, lit(7, 2.8), false, Mode.NONE, false));
        assertFalse(step.escalate());
        assertTrue(step.baiting());
    }

    @Test
    public void midFightARepeatIsJustTheDefuse() {
        // the fight is till dead, so no run, and no standing still with something chewing on us either
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.FIGHT, true);
        assertEquals(Event.START, step.step(120, lit(7, 2.8), false, Mode.FIGHT, true));
        assertFalse(step.escalate());
        assertFalse(step.baiting());
    }

    @Test
    public void aCommitmentNeverGetsTheBait() {
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 2.5), false, Mode.FIGHT, true));
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, true, 2.5, true)), false, Mode.RUN, true);
        assertTrue(step.active());
        assertFalse(step.baiting());
    }

    // a crowd or low hp turns into a run while we stand there waiting for the pop: the run gets the defuse, not a statue
    @Test
    public void aRunThatStartsMidBaitTurnsItIntoTheDefuse() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        assertTrue(step.baiting());
        assertEquals(Event.NONE, step.step(10, lit(7, 6.5), false, Mode.NONE, true));
        assertEquals(Event.NONE, step.step(11, lit(7, 6.5), false, Mode.RUN, true));
        assertTrue(step.active());
        assertFalse(step.baiting());
        // and the run's rules from here: a deflating fuse is held through until it is drained and we are at SAFE
        assertEquals(Event.NONE, step.step(12, List.of(new Seen(7, false, 6.5, false, true)), false, Mode.RUN, true));
        assertEquals(Event.NONE, step.step(13, List.of(new Seen(7, false, CreeperStep.SAFE + 1, false, true)), false, Mode.RUN, true));
        assertEquals(Event.NONE, step.step(14, List.of(new Seen(7, false, CreeperStep.SAFE - 1, false, false)), false, Mode.RUN, true));
        assertEquals(Event.END_UNLIT, step.step(15, List.of(new Seen(7, false, CreeperStep.SAFE, false, false)), false, Mode.RUN, true));
    }

    // the flip: unlit at 6 with the swell still up used to end the step, the run walked back past it and it relit
    @Test
    public void aStepInsideARunDoesNotLetGoOfADeflatingFuse() {
        CreeperStep step = new CreeperStep();
        step.step(0, List.of(new Seen(7, true, 2.5, true)), false, Mode.RUN, true);
        assertEquals(Event.NONE, step.step(5, List.of(new Seen(7, false, 6, false, true)), false, Mode.RUN, true));
        assertTrue(step.active());
    }

    // lit at 3 from a standstill. the creeper stands still while it swells (its goals stop), we sprint straight away with
    // a standing start's speed, and the 30 tick fuse has to find us past 5
    @Test
    public void theHandSprintIsPastTheBlastBeforeTheFuseIsOut() {
        double dist = 3;
        double v = 0;
        long t = 0;
        while (CreeperStep.rawGoing(t, dist)) {
            // ground sprint: 0.28 a tick at the top, friction 0.546
            v = v * 0.546 + 0.28 * (1 - 0.546);
            dist += v;
            t++;
        }
        assertTrue("past 5 within the fuse, was " + dist + " after " + t, dist > 5 && t <= 30);
        // the old 10 ticks left us in the blast
        double ten = 3;
        double vv = 0;
        for (int i = 0; i < 10; i++) {
            vv = vv * 0.546 + 0.28 * (1 - 0.546);
            ten += vv;
        }
        assertTrue(ten < 5.5);
    }

    @Test
    public void longAfterTheWindowItIsANewCreeper() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(new Seen(7, false, 7.5, false, false)), false, Mode.NONE, true);
        assertEquals(Event.START, step.step(31 + CreeperStep.REPEAT_WINDOW, lit(7, 3), false, Mode.NONE, true));
        assertFalse(step.escalate());
        assertTrue(step.baiting());
    }

    @Test
    public void anotherCreeperIsNotARepeat() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false, Mode.NONE, true);
        step.step(30, List.of(), false, Mode.NONE, true);
        assertEquals(Event.START, step.step(40, lit(8, 3), false, Mode.NONE, true));
        assertFalse(step.escalate());
    }

    // ---- the run

    @Test
    public void aRunOnlyStepsForItsOwnPathWalkingIntoOne() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.NONE, step.step(0, lit(7, 2.5), false, Mode.RUN, true));
        assertFalse(step.active());
        assertEquals(Event.START, step.step(1, List.of(new Seen(7, true, 2.5, true)), false, Mode.RUN, true));
    }

    @Test
    public void noRunNoChange() {
        assertEquals(Event.START, new CreeperStep().step(0, lit(7, 2.5), false, Mode.NONE, true));
    }

    // ---- the numbers

    @Test
    public void theBaitStopsPastTheBlastAndShortOfTheDefuse() {
        // nothing past 6 is hurt, past 7 it stops swelling. the stop plus the slide lands in between
        assertTrue(CreeperStep.BAIT_STOP > 6);
        assertTrue(CreeperStep.BAIT_STOP + 0.35 < 7);
        assertTrue(CreeperStep.BAIT_RESUME >= 6);
        assertTrue(CreeperStep.BAIT_RING_MIN > 6 && CreeperStep.BAIT_RING_MAX < 7);
        assertTrue(CreeperStep.BAIT_RING_MIN >= CreeperStep.BAIT_STOP);
    }

    @Test
    public void standingHasALittleSlack() {
        assertFalse(CreeperStep.baitStands(6.19, false));
        assertTrue(CreeperStep.baitStands(6.2, false));
        assertTrue(CreeperStep.baitStands(6.1, true));
        assertFalse(CreeperStep.baitStands(5.99, true));
        // past 7 is a miss, but walking back at it is not the answer either
        assertTrue(CreeperStep.baitStands(7.5, false));
    }

    @Test
    public void theRingIsSixThreeToSixNine() {
        assertFalse(CreeperStep.inBaitRing(6.29));
        assertTrue(CreeperStep.inBaitRing(6.3));
        assertTrue(CreeperStep.inBaitRing(6.6));
        assertTrue(CreeperStep.inBaitRing(6.9));
        assertFalse(CreeperStep.inBaitRing(6.91));
    }

    @Test
    public void aClockGoingBackwardsEndsIt() {
        CreeperStep step = new CreeperStep();
        step.step(100, lit(7, 3), false);
        assertEquals(Event.END_CAP, step.step(5, lit(7, 3), false));
    }

    @Test
    public void aRelitCreeperStepsAgainButLogsOnce() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        step.step(5, List.of(new Seen(7, false, 6, false)), false);
        assertEquals(Event.START, step.step(40, lit(7, 2.5), false));
        assertFalse(step.shouldLog());
    }

    @Test
    public void boxedInWithAShieldPutsItUp() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), true);
        for (int t = 1; t < CreeperStep.CORNER_TICKS; t++) assertEquals(Event.NONE, step.step(t, lit(7, 3.2), true));
        assertEquals(Event.CORNERED, step.step(CreeperStep.CORNER_TICKS, lit(7, 3.2), true));
        assertTrue(step.cornered());
        assertTrue(step.active());
    }

    private static Event stepNoHeading(CreeperStep step, long now, List<Seen> seen, boolean shield, boolean noHeading) {
        return step.step(now, seen, shield, Mode.NONE, false, false, noHeading);
    }

    @Test
    public void noWayOutIsCorneredOnTheFirstTick() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.CORNERED, stepNoHeading(step, 0, lit(7, 3.5), true, true));
        assertTrue(step.cornered());
        assertTrue(step.active());
        assertTrue(step.shouldLog());
    }

    @Test
    public void noWayOutBeatsTheBaitRing() {
        // a plain creeper with nothing near it is bait material: a heading means the bait, no heading means the shield
        CreeperStep bait = new CreeperStep();
        assertEquals(Event.START, stepNoHeading(bait, 0, lit(7, 3.5), true, false));
        assertTrue(bait.baiting());
        assertFalse(bait.cornered());
        CreeperStep boxed = new CreeperStep();
        assertEquals(Event.CORNERED, stepNoHeading(boxed, 0, lit(7, 3.5), true, true));
        assertFalse(boxed.baiting());
    }

    @Test
    public void noWayOutWithoutAShieldIsTheOrdinaryStep() {
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, stepNoHeading(step, 0, lit(7, 3.5), false, true));
        assertFalse(step.cornered());
    }

    @Test
    public void noWayOutPastTheTriggerWaits() {
        // lit but only the path is walking into it: nothing is blowing up on us yet
        CreeperStep step = new CreeperStep();
        assertEquals(Event.START, stepNoHeading(step, 0, List.of(new Seen(7, true, 5, true)), true, true));
        assertFalse(step.cornered());
    }

    @Test
    public void noWayOutFoundMidStepCornersAtOnce() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), true);
        assertEquals(Event.CORNERED, stepNoHeading(step, 1, lit(7, 3), true, true));
        assertTrue(step.cornered());
    }

    @Test
    public void anUnlitOneInAShaftIsNotCornered() {
        // it un-swelled, nothing to put a shield up for. the held step goes on being held, not cornered
        CreeperStep step = new CreeperStep();
        stepNoHeading(step, 0, lit(7, 3), false, false);
        assertEquals(Event.NONE, stepNoHeading(step, 1, List.of(new Seen(7, false, 3, false, true)), true, true));
        assertFalse(step.cornered());
    }

    @Test
    public void noShieldMeansKeepTrying() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS + 5, lit(7, 3), false));
        assertFalse(step.cornered());
    }

    @Test
    public void walkingAwayIsNotCornered() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), true);
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS, lit(7, 4.5), true));
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS + 1, lit(7, 3.9), true));
        assertFalse(step.cornered());
    }

    @Test
    public void pathNodesWithinThreeOfTheCreeperCount() {
        assertTrue(CreeperStep.pathPassesNear(10.5, 64, 10.5, List.of(new int[]{0, 64, 0}, new int[]{8, 64, 10})));
        assertFalse(CreeperStep.pathPassesNear(10.5, 64, 10.5, List.of(new int[]{0, 64, 0}, new int[]{6, 64, 10})));
        assertFalse(CreeperStep.pathPassesNear(10.5, 64, 10.5, List.of()));
    }

    @Test
    public void resetForgetsEverything() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        step.reset();
        assertFalse(step.active());
        assertEquals(Event.START, step.step(1, lit(7, 3), false));
        assertTrue(step.shouldLog());
    }

    @Test
    public void theLogLineSaysWhatHappened() {
        assertEquals("combat: [overworld] lit creeper at 3 blocks, stepping out of the blast",
                CombatLog.creeperStep(baritone.api.utils.Dimension.OVERWORLD, 3.2));
    }

    @Test
    public void theRawSprintAndPathLinesSayWhatHappened() {
        baritone.api.utils.Dimension d = baritone.api.utils.Dimension.NETHER;
        assertEquals("combat: [nether] creeper step: sprinting straight away", CombatLog.creeperSprint(d, 0));
        assertEquals("combat: [nether] creeper step: sprinting 45 degrees off straight away", CombatLog.creeperSprint(d, -45));
        assertEquals("combat: [nether] creeper step: no safe heading, waiting on the path", CombatLog.creeperNoHeading(d));
        assertEquals("combat: [nether] creeper step: path asked for", CombatLog.creeperPath(d, false));
        assertEquals("combat: [nether] creeper step: no path running, asking again", CombatLog.creeperPath(d, true));
    }

    @Test
    public void theStepGoesEightBlocksAndCornersStillCountPositionOnly() {
        assertEquals(8, CreeperStep.SAFE, 0);
        // the clock-and-distance test does not care how the ground was gained, so sprinting out of a corner is not cornered
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), true);
        assertEquals(Event.NONE, step.step(CreeperStep.CORNER_TICKS, lit(7, 3.8), true));
        assertFalse(step.cornered());
    }
}
