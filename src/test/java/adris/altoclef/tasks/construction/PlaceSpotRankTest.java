package adris.altoclef.tasks.construction;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaceSpotRankTest {

    private static double score(double distSq, boolean hasBelow, int dy, boolean canScaffold) {
        return PlaceSpotRank.score(distSq, false, hasBelow, false, dy, canScaffold);
    }

    @Test
    public void airWithNoFloorAndNothingToBuildItOneIsRejected() {
        assertEquals(Double.POSITIVE_INFINITY, score(1, false, 0, false), 0);
    }

    @Test
    public void airWithNoFloorIsFineWhenAThrowawayCanBuildOne() {
        assertTrue(score(1, false, 0, true) < Double.POSITIVE_INFINITY);
    }

    @Test
    public void aSupportedSpotNeedsNoThrowaway() {
        assertTrue(score(4, true, 0, false) < Double.POSITIVE_INFINITY);
    }

    @Test
    public void aFloorStillBeatsAnEquallyCloseDrop() {
        assertTrue(score(4, true, 0, true) < score(4, false, 0, true));
    }

    @Test
    public void ourOwnLevelBeatsAnEquallyCloseClimb() {
        assertTrue(score(4, true, 0, false) < score(4, true, 2, false));
        assertTrue(score(4, true, 0, false) < score(4, true, -2, false));
    }

    @Test
    public void aSupportedSpotNextToUsBeatsAFarAwayOneEvenOnTheSameLevel() {
        assertTrue(score(1, true, 0, false) < score(36, true, 0, false));
    }

    @Test
    public void theOldScoreIsWhatItWas() {
        // distance, +4 for a block already there, +10 for no floor, +3 for inside us
        assertEquals(5, PlaceSpotRank.oldScore(5, false, true, false), 0);
        assertEquals(9, PlaceSpotRank.oldScore(5, true, true, false), 0);
        assertEquals(15, PlaceSpotRank.oldScore(5, false, false, false), 0);
        assertEquals(8, PlaceSpotRank.oldScore(5, false, true, true), 0);
    }

    @Test
    public void rejectionIsOnlyInTheNewRankSoTheFallbackStillHasAnAnswer() {
        assertTrue(PlaceSpotRank.oldScore(1, false, false, false) < Double.POSITIVE_INFINITY);
    }
}
