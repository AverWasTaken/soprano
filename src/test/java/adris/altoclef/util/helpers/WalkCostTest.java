package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the cave trip: a table a few blocks down is a long walk back up, not "24 blocks away"
public class WalkCostTest {
    @Test
    public void flatWalkIsJustTheDistance() {
        assertEquals(10.0, WalkCost.estimate(10, 0, 0), 1e-9);
        assertEquals(5.0, WalkCost.estimate(3, 0, 4), 1e-9);
    }

    @Test
    public void everyBlockOfHeightCostsFourFlatOnes() {
        assertEquals(4.0, WalkCost.estimate(0, 1, 0), 1e-9);
        // up and down are the same trip
        assertEquals(WalkCost.estimate(2, 3, 0), WalkCost.estimate(2, -3, 0), 1e-9);
        assertEquals(14.0, WalkCost.estimate(2, -3, 0), 1e-9);
    }

    @Test
    public void budgetIsInclusiveAndTwentyByDefault() {
        assertTrue(WalkCost.within(20, 0, 0, WalkCost.STATION_BUDGET));
        assertFalse(WalkCost.within(20.1, 0, 0, WalkCost.STATION_BUDGET));
        // five up is exactly the budget, six is a loss
        assertTrue(WalkCost.within(0, 5, 0, WalkCost.STATION_BUDGET));
        assertFalse(WalkCost.within(0, 6, 0, WalkCost.STATION_BUDGET));
    }

    @Test
    public void aDropWeCouldCraftIsOnlyWorthAShortWalk() {
        // sticks on the surface, planks in the bag: 5 up is 20, not worth it. 8 flat is
        assertFalse(WalkCost.dropWorthWalking(0, 5, 0, true));
        assertFalse(WalkCost.dropWorthWalking(0, 2.1, 0, true));
        assertTrue(WalkCost.dropWorthWalking(8, 0, 0, true));
        assertFalse(WalkCost.dropWorthWalking(8.1, 0, 0, true));
        // our own mining drops are right at our feet
        assertTrue(WalkCost.dropWorthWalking(3, 1, 0, true));
    }

    @Test
    public void aDropWeCannotCraftStillHasACap() {
        assertTrue(WalkCost.dropWorthWalking(30, 0, 0, false));
        assertTrue(WalkCost.dropWorthWalking(0, 8, 0, false));
        assertFalse(WalkCost.dropWorthWalking(0, 9, 0, false));
        assertFalse(WalkCost.dropWorthWalking(40, 0, 0, false));
        assertTrue(WalkCost.dropBudget(true) < WalkCost.dropBudget(false));
    }

    // 40 blocks of straight line used to be "close" with no table in hand, OwnTables.forgetFar says 20
    @Test
    public void makingATableUsesTheSameBudgetCarriedOrNot() {
        double inf = Double.POSITIVE_INFINITY;
        assertEquals(inf, WalkCost.newTableCost(true, true, true), 0);
        assertEquals(inf, WalkCost.newTableCost(false, true, false), 0);
        // out of budget with a table in hand: placing it is free
        assertEquals(0, WalkCost.newTableCost(true, false, false), 0);
        // out of budget with nothing in hand: craft one, cheaper with wood on us
        assertEquals(10, WalkCost.newTableCost(false, false, true), 0);
        assertEquals(100, WalkCost.newTableCost(false, false, false), 0);
    }

    @Test
    public void theCaveTripIsNotWorthIt() {
        // table at y 30, log at the surface y 64, a few blocks over: the old sphere said 34 and a bit, the walk says 136+
        assertFalse(WalkCost.within(3, 34, 0, WalkCost.STATION_BUDGET));
        assertTrue(WalkCost.estimate(3, 34, 0) > 100);
    }
}
