package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.BucketSourceRules.Cells;
import adris.altoclef.util.helpers.BucketSourceRules.Kind;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// lava is only worth walking to if a bucket can get at it, and the pool you can just scoop beats the one under a roof
public class BucketSourceRulesTest {

    // a world that is stone everywhere except the cells it is told are open. the source sits at 0,0,0
    private static Cells world(int... openXyz) {
        Set<Long> open = new HashSet<>();
        for (int i = 0; i < openXyz.length; i += 3) {
            open.add(key(openXyz[i], openXyz[i + 1], openXyz[i + 2]));
        }
        return new Cells() {
            @Override
            public boolean solid(int x, int y, int z) {
                return !open.contains(key(x, y, z));
            }

            @Override
            public boolean open(int x, int y, int z) {
                return open.contains(key(x, y, z));
            }
        };
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 100) << 40) | ((long) (y + 100) << 20) | (z + 100);
    }

    @Test
    public void aPoolEdgeIsOpen() {
        // air beside the source and over it
        assertEquals(Kind.OPEN, BucketSourceRules.classify(world(1, 0, 0, 0, 1, 0), 0, 0, 0));
    }

    @Test
    public void openFromOnlyOneSideStillCounts() {
        // a beside-only opening needs the lid over it to be non-solid, so open() is false for it but solid() must be too
        Cells beside = new Cells() {
            @Override
            public boolean solid(int x, int y, int z) {
                return !(x == 0 && y == 1 && z == 0) && !(x == 0 && y == 0 && z == -1);
            }

            @Override
            public boolean open(int x, int y, int z) {
                return x == 0 && y == 0 && z == -1;
            }
        };
        assertEquals(Kind.OPEN, BucketSourceRules.classify(beside, 0, 0, 0));
        // nothing beside it but air over it is a scoop from above
        assertEquals(Kind.OPEN, BucketSourceRules.classify(world(0, 1, 0), 0, 0, 0));
    }

    @Test
    public void aSourceBuriedInStoneIsNobodys() {
        assertEquals(Kind.NONE, BucketSourceRules.classify(world(), 0, 0, 0));
    }

    @Test
    public void aSourceSeenOnlyAcrossACornerIsRejected() {
        // the only open cells touch it at an edge or a corner (diagonal beside it, diagonal in the lid layer)
        assertEquals(Kind.NONE, BucketSourceRules.classify(world(1, 0, 1, 1, 1, 1, -1, 1, 1), 0, 0, 0));
    }

    @Test
    public void aLidWithNoSideCellIsRejected() {
        // solid over the source, and the lid's four neighbours are stone: digging it means tunnelling in. (the cell below the
        // lid layer being open doesn't change that, the dig is about the lid)
        assertEquals(Kind.NONE, BucketSourceRules.classify(world(1, 0, 1, 0, -1, 0), 0, 0, 0));
    }

    @Test
    public void aLidWithAnOpenSideCellIsALid() {
        assertEquals(Kind.LID, BucketSourceRules.classify(world(1, 1, 0), 0, 0, 0));
    }

    @Test
    public void aLidCellOnlyAtTheDiagonalDoesNotCount() {
        assertEquals(Kind.NONE, BucketSourceRules.classify(world(1, 1, 1), 0, 0, 0));
    }

    @Test
    public void aLidWhoseOnlySideCellIsAPoolIsRejected() {
        Cells wet = new Cells() {
            @Override
            public boolean solid(int x, int y, int z) {
                return !(x == 1 && y == 1 && z == 0);
            }

            @Override
            public boolean open(int x, int y, int z) {
                return x == 1 && y == 1 && z == 0;
            }

            @Override
            public boolean free(int x, int y, int z) {
                return false;
            }
        };
        assertEquals(Kind.NONE, BucketSourceRules.classify(wet, 0, 0, 0));
    }

    @Test
    public void glassOverTheSourceIsNotAnOpening() {
        // solid() is false for it (it isn't a lid) but it isn't open either, so with the sides stone there is no way in
        Cells glass = new Cells() {
            @Override
            public boolean solid(int x, int y, int z) {
                return !(x == 0 && y == 1 && z == 0);
            }

            @Override
            public boolean open(int x, int y, int z) {
                return false;
            }
        };
        assertEquals(Kind.NONE, BucketSourceRules.classify(glass, 0, 0, 0));
    }

    @Test
    public void anOpenPoolBeatsALidAndALidIsTheFallback() {
        assertTrue(BucketSourceRules.worthGoingTo(Kind.OPEN, true));
        assertTrue(BucketSourceRules.worthGoingTo(Kind.OPEN, false));
        assertFalse(BucketSourceRules.worthGoingTo(Kind.LID, true));
        assertTrue(BucketSourceRules.worthGoingTo(Kind.LID, false));
        assertFalse(BucketSourceRules.worthGoingTo(Kind.NONE, false));
    }
}
