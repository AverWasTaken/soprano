package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.BreathDodge.Move;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the breath dodge pick and the dunk water pickup caps
public class BreathDodgeTest {

    @Test
    public void outOfTheCloudOrInTheAirDoesNothing() {
        assertEquals(Move.NONE, BreathDodge.choose(false, true, true, true));
        assertEquals(Move.NONE, BreathDodge.choose(true, false, false, true));
    }

    @Test
    public void groundRuns() {
        assertEquals(Move.RUN, BreathDodge.choose(true, true, true, false));
        assertEquals(Move.RUN, BreathDodge.choose(true, true, true, true));
    }

    @Test
    public void pillarTopDropsWithABucketAndDigsWithout() {
        assertEquals(Move.DROP, BreathDodge.choose(true, true, false, true));
        assertEquals(Move.DIG, BreathDodge.choose(true, true, false, false));
    }

    @Test
    public void endPickupNeedsReachAndGivesUpFast() {
        assertFalse(BreathDodge.pickupExpired(true, 1, 3));
        assertTrue(BreathDodge.pickupExpired(true, 1, 6));
        assertTrue(BreathDodge.pickupExpired(true, 3.5, 3));
    }

    @Test
    public void elsewhereTheWaterIsFetchedFromFarForTenSeconds() {
        assertFalse(BreathDodge.pickupExpired(false, 9, 30));
        assertTrue(BreathDodge.pickupExpired(false, 10.5, 1));
    }
}
