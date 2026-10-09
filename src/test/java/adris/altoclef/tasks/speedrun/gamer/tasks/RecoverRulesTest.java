package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules.Gate;
import adris.altoclef.tasks.speedrun.gamer.tasks.RecoverRules.Verdict;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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
    public void aDropThatNeverComesIsWrittenOffAfterItsChaseSeconds() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        // a drop on a ledge: still visible, never ours. one second per tick call keeps the sums exact
        for (int s = 0; s <= RecoverRules.CHASE_SECONDS; s++) {
            assertFalse("at " + s, chase.update(7, s));
        }
        assertTrue(chase.update(7, RecoverRules.CHASE_SECONDS + 1));
    }

    @Test
    public void chasingTwoDropsInTurnsAddsUpPerDrop() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        double t = 0;
        boolean seven = false;
        boolean eight = false;
        // the closest one flips every second, each only ever gets its own half of the time
        for (int i = 0; i < 38; i++) {
            t += 1;
            if (i % 2 == 0) {
                seven |= chase.update(7, t);
            } else {
                eight |= chase.update(8, t);
            }
        }
        assertFalse(seven);
        assertFalse(eight);
        for (int i = 0; i < 6; i++) {
            t += 1;
            seven |= chase.update(7, t);
            t += 1;
            eight |= chase.update(8, t);
        }
        assertTrue(seven);
        assertTrue(eight);
    }

    @Test
    public void aGapIsNotChasingTime() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        assertFalse(chase.update(1, 0));
        // an hour of standoff in between counts for one tick at most
        assertFalse(chase.update(1, 3600));
        chase.idle();
        assertFalse(chase.update(1, 7200));
        assertFalse(chase.update(1, 7200.05));
    }

    @Test
    public void resetForgetsWhatWasSpent() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        double t = 0;
        while (!chase.update(3, t += 0.5)) {
            assertTrue(t < 100);
        }
        chase.reset();
        assertFalse(chase.update(3, t + 1));
    }

    @Test
    public void aDropTenBlocksOutIsChasedNotWalkedBackFrom() {
        // the loop: chasing it took us past ARRIVE_RANGE and the walk back fired. it can't anymore
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(10, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(RecoverRules.DROP_RADIUS + RecoverRules.LEASH_MARGIN, true));
        assertEquals(RecoverRules.Step.PICKUP, RecoverRules.step(0, true));
    }

    @Test
    public void farOrEmptyMeansWalkBack() {
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(RecoverRules.DROP_RADIUS + 1, false));
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(10, false));
        // dragged way off by a chase, the pile wins again
        assertEquals(RecoverRules.Step.WALK, RecoverRules.step(RecoverRules.DROP_RADIUS + RecoverRules.LEASH_MARGIN + 0.5, true));
    }

    @Test
    public void onThePileWithNothingAroundJustLooks() {
        assertEquals(RecoverRules.Step.LOOK, RecoverRules.step(RecoverRules.ARRIVE_RANGE, false));
        assertEquals(RecoverRules.Step.LOOK, RecoverRules.step(2, false));
    }

    @Test
    public void twentySecondsOfTicksIsTwentySeconds() {
        RecoverRules.Chase chase = new RecoverRules.Chase();
        // every recover tick (10 a second) now counts, nothing in between idles it
        double t = 0;
        while (t < RecoverRules.CHASE_SECONDS - 0.2) {
            assertFalse("at " + t, chase.update(5, t));
            t += 0.1;
        }
        boolean gone = false;
        for (int i = 0; i < 5 && !gone; i++) {
            t += 0.1;
            gone = chase.update(5, t);
        }
        assertTrue(gone);
        assertTrue(t < RecoverRules.CHASE_SECONDS + 0.5);
    }

    @Test
    public void thePickupIsPinnedToTheChosenDrop() {
        RecoverRules.Pin<String> pin = new RecoverRules.Pin<>();
        String andesite = new String("andesite");
        String otherAndesite = new String("andesite");
        assertTrue(pin.retarget(andesite));
        assertSame(andesite, pin.drop());
        // same drop next tick, keep the task (and its stuck checks)
        assertFalse(pin.retarget(andesite));
        // another drop of the same kind is still another drop, the old item-kind key missed this. equal is not enough,
        // a drop back from out of range is a new entity that looks the same
        assertTrue(pin.retarget(otherAndesite));
        assertSame(otherAndesite, pin.drop());
        pin.clear();
        assertTrue(pin.retarget(otherAndesite));
    }

    @Test
    public void resetForgetsTheCrowd() {
        Gate gate = new Gate();
        gate.update(0, 3);
        gate.reset();
        assertEquals(Verdict.WAIT, gate.update(100, 3));
    }
}
