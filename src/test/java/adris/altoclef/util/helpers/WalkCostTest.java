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
    public void theCaveTripIsNotWorthIt() {
        // table at y 30, log at the surface y 64, a few blocks over: the old sphere said 34 and a bit, the walk says 136+
        assertFalse(WalkCost.within(3, 34, 0, WalkCost.STATION_BUDGET));
        assertTrue(WalkCost.estimate(3, 34, 0) > 100);
    }
}
