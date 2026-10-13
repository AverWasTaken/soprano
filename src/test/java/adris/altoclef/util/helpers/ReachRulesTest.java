package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ApproachRules.Plan;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReachRulesTest {

    private static final double EYE = 1.62;
    // altoEntityReachRange's default
    private static final double SETTING = 4;

    // a mob whose feet are at (dx, dy, dz) from ours, with a box w wide and h tall. our eye is 1.62 above our own feet
    private static double gap(double dx, double dy, double dz, double w, double h) {
        return ReachRules.gap(0, EYE, 0, dx - w / 2, dy, dz - w / 2, dx + w / 2, dy + h, dz + w / 2);
    }

    private static double zombie(double dx, double dy, double dz) {
        return gap(dx, dy, dz, 0.6, 1.95);
    }

    private static boolean feetLine(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < SETTING;
    }

    private static boolean killReach(double dx, double dy, double dz, double gap) {
        return ReachRules.killReach(feetLine(dx, dy, dz), gap, SETTING);
    }

    @Test
    public void aMobInOurOwnCellHasNoGapAtAll() {
        assertEquals(0, zombie(0, 0, 0), 1e-9);
        assertEquals(true, killReach(0, 0, 0, zombie(0, 0, 0)));
    }

    @Test
    public void aBabyInOurCellIsBelowTheEyeButStillInReach() {
        // 0.975 tall, so the top of it is 0.645 under the eye
        assertEquals(0.645, gap(0, 0, 0, 0.3, 0.975), 1e-9);
        assertEquals(true, killReach(0, 0, 0, gap(0, 0, 0, 0.3, 0.975)));
    }

    @Test
    public void aMobStraightUpTheShaftIsCloserThanItsFeetSay() {
        // at +1 the eye is inside its box, at +2 the box starts 0.38 above the eye
        assertEquals(0, zombie(0, 1, 0), 1e-9);
        assertEquals(0.38, zombie(0, 2, 0), 1e-9);
        assertEquals(1.88, zombie(0, 3.5, 0), 1e-9);
        assertEquals(true, killReach(0, 3.5, 0, zombie(0, 3.5, 0)));
        // 4.5 up is past the feet line and still inside the setting by the eye: this is what the gap adds
        assertEquals(false, feetLine(0, 4.5, 0));
        assertEquals(true, killReach(0, 4.5, 0, zombie(0, 4.5, 0)));
        // and the far edge: the eye is 4 from the bottom of its box at 5.62 up
        assertEquals(true, killReach(0, 5.6, 0, zombie(0, 5.6, 0)));
        assertEquals(false, killReach(0, 5.7, 0, zombie(0, 5.7, 0)));
    }

    @Test
    public void aMobStraightBelowIsFartherThanItsFeetSay() {
        // the nearest point is the top of its head, 1.95 up from its feet
        assertEquals(0.67, zombie(0, -1, 0), 1e-9);
        assertEquals(2.67, zombie(0, -3, 0), 1e-9);
        assertEquals(3.17, zombie(0, -3.5, 0), 1e-9);
        // inside the feet line, so it was in reach before and still is
        assertEquals(true, killReach(0, -3.5, 0, zombie(0, -3.5, 0)));
        // 4.2 down is past the feet line and the gap (3.87) rescues it; 4.5 down (4.17) is out on both
        assertEquals(false, feetLine(0, -4.2, 0));
        assertEquals(true, killReach(0, -4.2, 0, zombie(0, -4.2, 0)));
        assertEquals(false, killReach(0, -4.5, 0, zombie(0, -4.5, 0)));
    }

    @Test
    public void aSpidersWideBoxPullsItCloserThanItsFeet() {
        // 1.4 wide, 0.9 tall, feet 3.4 away: the near face is 2.7 away, and the eye is 0.72 over the top of it
        double spider = gap(3.4, 0, 0, 1.4, 0.9);
        assertEquals(Math.sqrt(2.7 * 2.7 + 0.72 * 0.72), spider, 1e-9);
        assertEquals(true, killReach(3.4, 0, 0, spider));
        // and a diagonal one is measured on all three axes at once
        assertEquals(Math.sqrt(2 * 2 + 1.5 * 1.5 + 0.72 * 0.72), gap(2.7, 0, 2.2, 1.4, 0.9), 1e-9);
    }

    // the three to three and a bit band: a zombie there is out of a plain player's eye reach and was in the feet line's, and
    // the first version of this rule dropped it, so the bot backed off, walked up and backed off. it never leaves reach now
    @Test
    public void nothingTheFeetLineHadInReachEverLeavesIt() {
        for (double feet = 2.0; feet < SETTING; feet += 0.05) {
            assertEquals("zombie at " + feet, true, killReach(feet, 0, 0, zombie(feet, 0, 0)));
        }
        for (double feet = 3.0; feet <= 3.35; feet += 0.05) {
            // outside a plain player's three, inside the feet line: the plan is the swing, not the approach
            assertEquals("zombie at " + feet, Plan.INTERACT,
                    ApproachRules.plan(false, killReach(feet, 0, 0, zombie(feet, 0, 0)), true, true, true, false, false, ApproachRules.Policy.KILL));
        }
    }

    // feet close but out of eye reach: a chicken two down and one and a half across is 2.5 by the feet and 3.2 by the gap. it
    // used to be hit, then it was shuffled away from, and it is hit again
    @Test
    public void aShortMobFeetCloseButOutOfEyeReachIsStillHit() {
        double gap = gap(1.5, -2, 0, 0.4, 0.7);
        assertEquals(true, gap > ReachRules.MELEE_REACH);
        assertEquals(true, killReach(1.5, -2, 0, gap));
        Plan plan = ApproachRules.plan(false, killReach(1.5, -2, 0, gap), true, true, true, true, false, ApproachRules.Policy.KILL);
        assertEquals(Plan.INTERACT, plan);
    }
}
