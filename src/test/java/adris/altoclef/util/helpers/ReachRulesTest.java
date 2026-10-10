package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReachRulesTest {

    private static final double EYE = 1.62;

    // a mob whose feet are at (dx, dy, dz) from ours, with a box w wide and h tall. our eye is 1.62 above our own feet
    private static double gap(double dx, double dy, double dz, double w, double h) {
        return ReachRules.gap(0, EYE, 0, dx - w / 2, dy, dz - w / 2, dx + w / 2, dy + h, dz + w / 2);
    }

    private static double zombie(double dx, double dy, double dz) {
        return gap(dx, dy, dz, 0.6, 1.95);
    }

    @Test
    public void aMobInOurOwnCellHasNoGapAtAll() {
        assertEquals(0, zombie(0, 0, 0), 1e-9);
        assertEquals(true, ReachRules.inReach(zombie(0, 0, 0), ReachRules.MELEE_REACH));
    }

    @Test
    public void aBabyInOurCellIsBelowTheEyeButStillInReach() {
        // 0.975 tall, so the top of it is 0.645 under the eye
        assertEquals(0.645, gap(0, 0, 0, 0.3, 0.975), 1e-9);
        assertEquals(true, ReachRules.inReach(gap(0, 0, 0, 0.3, 0.975), ReachRules.MELEE_REACH));
    }

    @Test
    public void aMobStraightUpTheShaftIsCloserThanItsFeetSay() {
        // at +1 the eye is inside its box, at +2 the box starts 0.38 above the eye
        assertEquals(0, zombie(0, 1, 0), 1e-9);
        assertEquals(0.38, zombie(0, 2, 0), 1e-9);
        // 3.5 up is 1.88 from the eye to its feet, well in reach, and 4.62 up is exactly the edge
        assertEquals(1.88, zombie(0, 3.5, 0), 1e-9);
        assertEquals(true, ReachRules.inReach(zombie(0, 3.5, 0), ReachRules.MELEE_REACH));
        assertEquals(3.0, zombie(0, 4.62, 0), 1e-9);
        assertEquals(false, ReachRules.inReach(zombie(0, 4.7, 0), ReachRules.MELEE_REACH));
    }

    @Test
    public void aMobStraightBelowIsFartherThanItsFeetSay() {
        // the nearest point is the top of its head, 1.95 up from its feet
        assertEquals(0.67, zombie(0, -1, 0), 1e-9);
        assertEquals(2.67, zombie(0, -3, 0), 1e-9);
        assertEquals(true, ReachRules.inReach(zombie(0, -3, 0), ReachRules.MELEE_REACH));
        // 3.5 down is under the 4 block feet line and out of the 3 block reach
        assertEquals(3.17, zombie(0, -3.5, 0), 1e-9);
        assertEquals(false, ReachRules.inReach(zombie(0, -3.5, 0), ReachRules.MELEE_REACH));
    }

    @Test
    public void aZombieThreePointFiveAwayOnTheFlatIsInsideTheFourBlockLineAndOutsideTheReach() {
        assertEquals(3.2, zombie(3.5, 0, 0), 1e-9);
        assertEquals(false, ReachRules.inReach(zombie(3.5, 0, 0), ReachRules.MELEE_REACH));
        assertEquals(true, ReachRules.inReach(zombie(3.2, 0, 0), ReachRules.MELEE_REACH));
    }

    @Test
    public void aSpidersWideBoxPullsItCloserThanItsFeet() {
        // 1.4 wide, 0.9 tall, feet 3.4 away: the near face is 2.7 away, and the eye is 0.72 over the top of it
        double spider = gap(3.4, 0, 0, 1.4, 0.9);
        assertEquals(Math.sqrt(2.7 * 2.7 + 0.72 * 0.72), spider, 1e-9);
        assertEquals(true, ReachRules.inReach(spider, ReachRules.MELEE_REACH));
        // and a diagonal one is measured on all three axes at once
        assertEquals(Math.sqrt(2 * 2 + 1.5 * 1.5 + 0.72 * 0.72), gap(2.7, 0, 2.2, 1.4, 0.9), 1e-9);
    }
}
