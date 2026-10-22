package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.LavaWorld.Cell.LAVA;
import static adris.altoclef.util.helpers.LavaWorld.Cell.STONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// where a player in lava should swim when baritone has nothing for them. feet are the lava cell, the shore is one up
public class LavaExitTest {

    // a lake over x 0..12 in a stone floor (top y 63), banks are x -1 and x 13
    private static LavaWorld lake() {
        return LavaWorld.flat(63).fill(0, 63, -5, 12, 63, 25, LAVA);
    }

    private static LavaExit.Cell nearest(int x, int y, int z, LavaWorld w) {
        return LavaExit.nearest(x, y, z, w, w);
    }

    @Test
    public void picksTheNearerBank() {
        LavaExit.Cell exit = nearest(5, 63, 10, lake());
        assertNotNull(exit);
        // standing on top of the bank block, one up from the lava surface
        assertEquals(-1, exit.x());
        assertEquals(64, exit.y());
        assertEquals(10, exit.z());
    }

    @Test
    public void flipsToTheOtherBankWhenThatIsCloser() {
        LavaExit.Cell exit = nearest(11, 63, 10, lake());
        assertNotNull(exit);
        assertEquals(13, exit.x());
    }

    @Test
    public void aTwoHighWallBesideUsIsNotAWayOut() {
        // a pillar two blocks tall next to us, its top is two above the lava surface: closer than the bank, and no use
        LavaWorld w = lake().set(6, 63, 10, STONE).set(6, 64, 10, STONE);
        LavaExit.Cell exit = nearest(5, 63, 10, w);
        assertNotNull(exit);
        assertEquals(-1, exit.x());
    }

    @Test
    public void aOneHighStepBesideUsIsAWayOut() {
        LavaWorld w = lake().set(6, 63, 10, STONE);
        LavaExit.Cell exit = nearest(5, 63, 10, w);
        assertNotNull(exit);
        assertEquals(6, exit.x());
        assertEquals(64, exit.y());
    }

    @Test
    public void underTheSurfaceTheBankIsMeasuredFromTheSurface() {
        // three deep, we are two below the top layer, the bank is three above our feet but one above the surface
        LavaWorld w = LavaWorld.flat(63).fill(0, 61, -5, 12, 63, 25, LAVA);
        LavaExit.Cell exit = nearest(5, 61, 10, w);
        assertNotNull(exit);
        assertEquals(-1, exit.x());
        assertEquals(64, exit.y());
    }

    @Test
    public void aLakeBiggerThanTheRadiusHasNoAnswer() {
        LavaWorld w = new LavaWorld().fill(-30, 63, -30, 30, 63, 30, LAVA);
        assertNull(nearest(0, 63, 0, w));
    }

    @Test
    public void aFilledPitWithWallsOnEveryFaceClimbsToTheLedgeAtTheCorner() {
        // a one cell pit full of lava in the floor, walls two high on all four faces, so the only step out is the floor
        // top at a corner, one up and diagonal. the lava cell itself has a floor under it and is never the answer
        LavaWorld w = LavaWorld.flat(63).set(5, 63, 5, LAVA);
        int[][] faces = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] f : faces) {
            w.set(5 + f[0], 64, 5 + f[1], STONE).set(5 + f[0], 65, 5 + f[1], STONE);
        }
        LavaExit.Cell exit = nearest(5, 63, 5, w);
        assertNotNull(exit);
        assertEquals(64, exit.y());
        assertEquals(1, Math.abs(exit.x() - 5));
        assertEquals(1, Math.abs(exit.z() - 5));
    }

    @Test
    public void aBankOneCellAwayIsAHop() {
        LavaExit.Cell exit = nearest(0, 63, 10, lake());
        assertEquals(-1, exit.x());
        assertTrue(LavaExit.withinHop(exit, 0, 63, 10));
    }

    @Test
    public void aBankTwoCellsAwayIsStillAHop() {
        assertTrue(LavaExit.withinHop(nearest(1, 63, 10, lake()), 1, 63, 10));
    }

    @Test
    public void aBankThreeCellsAwayIsBaritonesProblem() {
        assertFalse(LavaExit.withinHop(nearest(2, 63, 10, lake()), 2, 63, 10));
    }

    @Test
    public void noExitIsNoHop() {
        assertFalse(LavaExit.withinHop(null, 0, 63, 0));
    }

    @Test
    public void aLedgeTwoAboveUsIsNotAHop() {
        // swimming a block under the surface: the bank is two above our feet, jump alone won't clear that
        assertFalse(LavaExit.withinHop(new LavaExit.Cell(-1, 65, 10), 0, 63, 10));
    }

    @Test
    public void aCellNeedsHeadroomAndAFloor() {
        // a stone block with a ceiling right over it is not a place to stand, and there is nothing else dry in range
        LavaWorld w = new LavaWorld().fill(-5, 63, -5, 5, 63, 5, LAVA)
                .set(3, 63, 0, STONE).set(3, 65, 0, STONE);
        assertNull(nearest(0, 63, 0, w));
    }

    @Test
    public void aTwoDeepPoolNearTheBottomStillSeesTheBank() {
        // pool two deep (62, 63) on a floor, a bank at its top level
        LavaWorld w = LavaWorld.flat(61).fill(0, 62, -5, 12, 63, 25, LAVA)
                .fill(-5, 62, -5, -1, 63, 25, STONE);
        LavaExit.Cell exit = nearest(0, 62, 10, w);
        assertNotNull(exit);
        assertEquals(-1, exit.x());
        assertEquals(64, exit.y());
        // two up from the floor cell is not a jump yet, a cell higher in the pool it is
        assertFalse(LavaExit.withinHop(exit, 0, 62, 10));
        assertTrue(LavaExit.withinHop(nearest(0, 63, 10, w), 0, 63, 10));
    }
}
