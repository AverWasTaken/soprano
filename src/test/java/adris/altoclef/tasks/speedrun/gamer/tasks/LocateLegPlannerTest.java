package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocateLegPlannerTest {
    @Test
    public void legBudgetGrowsWithDistance() {
        assertTrue(LocateLegPlanner.legBudgetSeconds(0) >= 60);
        assertTrue(LocateLegPlanner.legBudgetSeconds(1000) > LocateLegPlanner.legBudgetSeconds(100));
    }

    @Test
    public void aLegThatKeepsGettingCloserNeverStalls() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(0, 200);
        double dist = 200;
        for (double t = 1; t <= 50; t++) {
            dist -= 4;
            leg.update(t, dist);
            assertFalse("t " + t, leg.expired(t));
        }
    }

    @Test
    public void aLegThatStandsStillExpiresAfterAMinute() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(100, 200);
        leg.update(120, 199);
        leg.update(150, 198.5);
        assertFalse(leg.expired(159));
        assertTrue(leg.expired(160));
        assertTrue(leg.why(160).contains("progress"));
    }

    @Test
    public void tinyJitterIsNotProgress() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(0, 100);
        for (int t = 1; t < 60; t++) {
            leg.update(t, 100 - (t % 2) * 1.0);
        }
        assertTrue(leg.expired(60));
    }

    @Test
    public void aLegCannotRunPastItsBudgetEvenWhenCreeping() {
        LocateLegPlanner.Leg leg = new LocateLegPlanner.Leg(0, 10);
        double budget = LocateLegPlanner.legBudgetSeconds(10);
        double dist = 10;
        double t = 0;
        // 4 blocks every 50 s: always "progress", never stalled, so the hard budget is what ends it
        while (t < budget + 1) {
            t += 50;
            dist -= 4;
            leg.update(t, dist);
            if (leg.expired(t)) {
                assertTrue(t >= budget);
                assertEquals("took too long", leg.why(t));
                return;
            }
        }
        throw new AssertionError("never expired");
    }

    @Test
    public void emptyThrowsAreFreeButCappedInARow() {
        LocateLegPlanner.ThrowLedger l = new LocateLegPlanner.ThrowLedger(0, 14, 3);
        l.empty();
        l.empty();
        assertFalse(l.tooManyEmpty());
        assertEquals(0, l.counted());
        l.empty();
        assertTrue(l.tooManyEmpty());
    }

    @Test
    public void aRealThrowResetsTheEmptyStreak() {
        LocateLegPlanner.ThrowLedger l = new LocateLegPlanner.ThrowLedger(0, 14, 3);
        l.empty();
        l.empty();
        l.eyeAppeared();
        l.empty();
        l.empty();
        assertFalse(l.tooManyEmpty());
        assertEquals(1, l.counted());
    }

    @Test
    public void throwCapIsHardAndSurvivesARelog() {
        LocateLegPlanner.ThrowLedger l = new LocateLegPlanner.ThrowLedger(12, 14, 3);
        assertFalse(l.outOfThrows());
        l.eyeAppeared();
        assertFalse(l.outOfThrows());
        l.eyeAppeared();
        assertTrue(l.outOfThrows());
    }

    @Test
    public void relocateStepsAlongTheRingNotInOrOut() {
        double[] t = LocateLegPlanner.relocateTarget(2000, 0, 150);
        assertEquals(2000, t[0], 1e-9);
        assertEquals(150, t[1], 1e-9);
        double[] u = LocateLegPlanner.relocateTarget(0, -2000, 150);
        assertEquals(150, u[0], 1e-9);
        assertEquals(-2000, u[1], 1e-9);
        // 150 blocks away, and the radius from the origin barely changes (tangent, not radial)
        double[] v = LocateLegPlanner.relocateTarget(1500, -1500, 150);
        assertEquals(150, Math.hypot(v[0] - 1500, v[1] + 1500), 1e-9);
        assertTrue(Math.abs(Math.hypot(v[0], v[1]) - Math.hypot(1500, 1500)) < 6);
    }

    @Test
    public void relocateAtTheOriginStillGoesSomewhere() {
        double[] t = LocateLegPlanner.relocateTarget(0, 0, 150);
        assertEquals(150, Math.hypot(t[0], t[1]), 1e-9);
    }
}
