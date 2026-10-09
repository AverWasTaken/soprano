package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CreeperStep.Event;
import adris.altoclef.util.helpers.CreeperStep.Seen;
import java.util.List;
import org.junit.Test;

// the lit creeper preempt: lit and within 4 is a step, anything else is scenery, and the hold always ends
public class CreeperStepTest {

    private static List<Seen> lit(int id, double distance) {
        return List.of(new Seen(id, true, distance, false));
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

    @Test
    public void twoAndAHalfSecondsIsTheCapAndTheSameOneIsNotHeldAgain() {
        CreeperStep step = new CreeperStep();
        step.step(0, lit(7, 3), false);
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP - 1, lit(7, 3), false));
        assertEquals(Event.END_CAP, step.step(CreeperStep.HOLD_CAP, lit(7, 3), false));
        assertEquals(Event.NONE, step.step(CreeperStep.HOLD_CAP + 1, lit(7, 3), false));
        assertFalse(step.active());
        assertEquals(50, CreeperStep.HOLD_CAP);
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
}
