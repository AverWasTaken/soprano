package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules.Gate;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecoverRulesTest {

    @Test
    public void aHostileOnThePileIsAThreatOneFarAwayIsNot() {
        assertTrue(RecoverRules.threatens(3, 0, 4));
        assertTrue(RecoverRules.threatens(16, 0, 0));
        assertFalse(RecoverRules.threatens(16.5, 0, 0));
        assertFalse(RecoverRules.threatens(12, 0, 12));
    }

    @Test
    public void aHostileEightFloorsAwayHasRockBetweenUs() {
        assertTrue(RecoverRules.threatens(1, 8, 1));
        assertFalse(RecoverRules.threatens(1, 8.5, 1));
        assertFalse(RecoverRules.threatens(1, -12, 1));
    }

    @Test
    public void lavaAndTheVoidAreNotWorthTheTrip() {
        assertTrue(RecoverRules.hopeless(true, 60, -64));
        assertTrue(RecoverRules.hopeless(false, -65, -64));
        assertFalse(RecoverRules.hopeless(false, -64, -64));
        assertFalse(RecoverRules.hopeless(false, 70, -64));
    }

    @Test
    public void anEmptyPileIsAGo() {
        assertEquals(Verdict.GO, new Gate().update(100, 0));
    }

    @Test
    public void aCrowdedPileWaitsThirtySecondsAndThenIsGivenUp() {
        Gate gate = new Gate();
        assertEquals(Verdict.WAIT, gate.update(100, 2));
        assertEquals(Verdict.WAIT, gate.update(100 + RecoverRules.WAIT_SECONDS - 0.05, 2));
        assertEquals(Verdict.GIVE_UP, gate.update(100 + RecoverRules.WAIT_SECONDS, 2));
    }

    @Test
    public void aQuietMomentRestartsTheClock() {
        Gate gate = new Gate();
        gate.update(0, 3);
        assertEquals(Verdict.WAIT, gate.update(25, 3));
        // they wandered off for a second. thirty in a row is the rule, not thirty with gaps
        assertEquals(Verdict.GO, gate.update(26, 0));
        assertEquals(Verdict.WAIT, gate.update(27, 1));
        assertEquals(Verdict.WAIT, gate.update(50, 1));
        assertEquals(Verdict.GIVE_UP, gate.update(57, 1));
    }

    @Test
    public void resetForgetsTheCrowd() {
        Gate gate = new Gate();
        gate.update(0, 3);
        gate.reset();
        assertEquals(Verdict.WAIT, gate.update(100, 3));
    }
}
