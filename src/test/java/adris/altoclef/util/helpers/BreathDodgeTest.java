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
    public void aRunStuckInTheCloudDropsWithWaterAndDigsWithout() {
        assertEquals(Move.RUN, BreathDodge.escalate(Move.RUN, false, true, false));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.RUN, true, true, false));
        assertEquals(Move.DIG, BreathDodge.escalate(Move.RUN, true, false, false));
    }

    @Test
    public void neverDigsObsidianKeepsRunningWithoutWater() {
        assertEquals(Move.RUN, BreathDodge.escalate(Move.RUN, true, false, true));
        assertEquals(Move.RUN, BreathDodge.escalate(Move.DIG, true, false, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.DIG, true, true, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.RUN, true, true, true));
    }

    @Test
    public void otherMovesPassThrough() {
        assertEquals(Move.NONE, BreathDodge.escalate(Move.NONE, true, true, true));
        assertEquals(Move.DROP, BreathDodge.escalate(Move.DROP, true, false, true));
        assertEquals(Move.DIG, BreathDodge.escalate(Move.DIG, true, false, false));
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

    // one water bucket is the fall bucket, a breath drop only bets a second one
    @Test
    public void theLastWaterBucketIsNeverDroppedFor() {
        assertFalse(BreathDodge.spareWater(0));
        assertFalse(BreathDodge.spareWater(1));
        assertTrue(BreathDodge.spareWater(2));
        assertEquals(Move.DIG, BreathDodge.choose(true, true, false, BreathDodge.spareWater(1)));
        assertEquals(Move.DROP, BreathDodge.choose(true, true, false, BreathDodge.spareWater(2)));
        assertEquals(Move.RUN, BreathDodge.escalate(Move.DIG, true, BreathDodge.spareWater(1), true));
    }

    @Test
    public void theLastBucketInTheEndIsWorthAShortWalk() {
        assertFalse(BreathDodge.pickupExpired(true, true, 5, 10));
        assertTrue(BreathDodge.pickupExpired(true, true, 5, 13));
        assertTrue(BreathDodge.pickupExpired(true, true, 10.5, 2));
        // with another water bucket in the bag it is the old click-from-here rule
        assertTrue(BreathDodge.pickupExpired(true, false, 1, 6));
    }
}
