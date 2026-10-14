package adris.altoclef.util.helpers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TreeReachTest {
    private static final int FLOOR = 63;

    @Test
    public void gapIsTheAirBetweenTheLogAndTheFloor() {
        assertEquals(0, TreeReach.gap(FLOOR + 1, FLOOR));
        assertEquals(6, TreeReach.gap(FLOOR + 7, FLOOR));
        assertEquals(Integer.MAX_VALUE, TreeReach.gap(70, TreeReach.NO_FLOOR));
    }

    @Test
    public void theShaftReachesFiveAirCellsUp() {
        assertTrue(TreeReach.fromGround(FLOOR + 1 + TreeReach.MAX_UNDER, FLOOR, false));
        assertFalse(TreeReach.fromGround(FLOOR + 2 + TreeReach.MAX_UNDER, FLOOR, false));
    }

    @Test
    public void aSideToStandOnIsAlwaysReach() {
        assertTrue(TreeReach.fromGround(FLOOR + 20, FLOOR, true));
        assertTrue(TreeReach.fromGround(FLOOR + 20, TreeReach.NO_FLOOR, true));
    }

    @Test
    public void noFloorAndNoSideIsOutOfReach() {
        assertFalse(TreeReach.fromGround(FLOOR + 2, TreeReach.NO_FLOOR, false));
    }

    @Test
    public void onlyAFlaggedLogOutOfReachWithNoMeansIsSkipped() {
        // flagged, can't be had from the ground
        assertTrue(TreeReach.skip(true, false, false, false));
        // a tower, or dirt to build one from, makes it fair again
        assertFalse(TreeReach.skip(true, false, true, false));
        assertFalse(TreeReach.skip(true, false, false, true));
        // the shaft gets it
        assertFalse(TreeReach.skip(true, true, false, false));
        // an intact trunk is not flagged, whatever the rest says. it opens up as it is cut from the bottom
        assertFalse(TreeReach.skip(false, false, false, false));
    }

    @Test
    public void dirtFirstIsTheCaseWhereOnlyTheDirtIsMissing() {
        assertTrue(TreeReach.needsDirtFirst(true, false, false, true));
        assertFalse(TreeReach.needsDirtFirst(true, false, true, true));
        assertFalse(TreeReach.needsDirtFirst(true, false, false, false));
        assertFalse(TreeReach.needsDirtFirst(true, true, false, true));
        assertFalse(TreeReach.needsDirtFirst(false, false, false, true));
    }

    @Test
    public void skipAndDirtFirstNeverBothSayYes() {
        for (int bits = 0; bits < 16; bits++) {
            boolean flagged = (bits & 1) != 0, ground = (bits & 2) != 0, throwaway = (bits & 4) != 0, dirt = (bits & 8) != 0;
            assertFalse(TreeReach.skip(flagged, ground, throwaway, dirt) && TreeReach.needsDirtFirst(flagged, ground, throwaway, dirt));
        }
    }

    @Test
    public void dirtWantedFollowsTheClimbWithinReason() {
        assertEquals(4, TreeReach.dirtWanted(0));
        assertEquals(4, TreeReach.dirtWanted(3));
        assertEquals(7, TreeReach.dirtWanted(6));
        assertEquals(10, TreeReach.dirtWanted(40));
        assertEquals(8, TreeReach.dirtWanted(Integer.MAX_VALUE));
    }
}
