package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.BucketSourceRules.Cells;
import adris.altoclef.util.helpers.BucketSourceRules.Kind;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    public void aFarPoolDoesNotShutOutACloseLid() {
        double none = Double.NaN;
        assertFalse(BucketSourceRules.openOutranksLids(200, 3));
        assertTrue(BucketSourceRules.openOutranksLids(20, 3));
        // past 32 but within 16 of the lid
        assertTrue(BucketSourceRules.openOutranksLids(40, 30));
        assertFalse(BucketSourceRules.openOutranksLids(60, 10));
        // no lid to prefer, any distance will do. no open one, nothing outranks
        assertTrue(BucketSourceRules.openOutranksLids(200, none));
        assertFalse(BucketSourceRules.openOutranksLids(none, 3));
        assertFalse(BucketSourceRules.openOutranksLids(none, none));
    }

    @Test
    public void theRankingPicksTheLidWhenTheOpenOneDoesNotOutrankIt() {
        // open at 200 + lid at 3: the lid is worth going to; open at 20 + lid at 3: it is not
        assertTrue(BucketSourceRules.worthGoingTo(Kind.LID, BucketSourceRules.openOutranksLids(200, 3)));
        assertFalse(BucketSourceRules.worthGoingTo(Kind.LID, BucketSourceRules.openOutranksLids(20, 3)));
        // no open sources at all: the nearest acceptable lid
        assertTrue(BucketSourceRules.worthGoingTo(Kind.LID, BucketSourceRules.openOutranksLids(Double.NaN, 3)));
    }

    @Test
    public void anOpenPoolBeatsALidAndALidIsTheFallback() {
        assertTrue(BucketSourceRules.worthGoingTo(Kind.OPEN, true));
        assertTrue(BucketSourceRules.worthGoingTo(Kind.OPEN, false));
        assertFalse(BucketSourceRules.worthGoingTo(Kind.LID, true));
        assertTrue(BucketSourceRules.worthGoingTo(Kind.LID, false));
        assertFalse(BucketSourceRules.worthGoingTo(Kind.NONE, false));
    }

    private enum Cell { STONE, AIR, WATER, ICE, PATH }

    // a little world by cell kind, stone where nothing was said. PATH is a dirt path: has a body, isn't a full block
    private static final class Grid implements Cells {
        private final Map<Long, Cell> cells = new HashMap<>();

        Grid set(Cell kind, int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        cells.put(key(x, y, z), kind);
                    }
                }
            }
            return this;
        }

        private Cell at(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), Cell.STONE);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return at(x, y, z) == Cell.STONE;
        }

        @Override
        public boolean open(int x, int y, int z) {
            Cell c = at(x, y, z);
            return c == Cell.AIR || c == Cell.WATER;
        }

        @Override
        public boolean free(int x, int y, int z) {
            return at(x, y, z) == Cell.AIR;
        }

        @Override
        public boolean ice(int x, int y, int z) {
            return at(x, y, z) == Cell.ICE;
        }
    }

    // a 5x5 pond at y 0 sunk in stone, the top layer at y 1 frozen right to the shore, open sky from y 2 up
    private static Grid frozenPond() {
        return new Grid()
                .set(Cell.AIR, -6, 2, -6, 6, 5, 6)
                .set(Cell.WATER, -2, 0, -2, 2, 0, 2)
                .set(Cell.ICE, -2, 1, -2, 2, 1, 2);
    }

    @Test
    public void aFrozenPondIsALidFromTheNextIceOrTheShore() {
        Grid pond = frozenPond();
        // the middle: every side of its lid is more ice, standing on that is the way
        assertEquals(Kind.LID, BucketSourceRules.classify(pond, 0, 0, 0));
        // the corner: the shore stone is flush with the ice, standing on that works too
        assertEquals(Kind.LID, BucketSourceRules.classify(pond, 2, 0, 2));
        assertTrue(BucketSourceRules.lid(pond, 0, 1, 0));
    }

    @Test
    public void thawedItIsOpenWaterAgain() {
        Grid pond = frozenPond().set(Cell.AIR, -2, 1, -2, 2, 1, 2);
        assertEquals(Kind.OPEN, BucketSourceRules.classify(pond, 0, 0, 0));
        // and water sitting on the source is open the way it always was
        Grid deep = frozenPond().set(Cell.WATER, -2, 1, -2, 2, 1, 2);
        assertEquals(Kind.OPEN, BucketSourceRules.classify(deep, 0, 0, 0));
    }

    @Test
    public void iceWithNowhereToStandBesideItIsNobodys() {
        // a pocket of water and ice under a stone roof: the ice is a lid, but no block beside it has two free cells on top
        Grid buried = new Grid()
                .set(Cell.WATER, 0, 0, 0, 0, 0, 0)
                .set(Cell.ICE, 0, 1, 0, 0, 1, 0);
        assertEquals(Kind.NONE, BucketSourceRules.classify(buried, 0, 0, 0));
        // a head's worth of room is not enough either
        Grid low = new Grid()
                .set(Cell.AIR, -3, 2, -3, 3, 2, 3)
                .set(Cell.WATER, 0, 0, 0, 0, 0, 0)
                .set(Cell.ICE, 0, 1, 0, 0, 1, 0);
        assertEquals(Kind.NONE, BucketSourceRules.classify(low, 0, 0, 0));
    }

    @Test
    public void aStoneLidFlushWithItsShoreStaysRejected() {
        // same shape as the frozen pond but stone on top: the stand beside rule is for ice only
        Grid roof = frozenPond().set(Cell.STONE, -2, 1, -2, 2, 1, 2);
        assertEquals(Kind.NONE, BucketSourceRules.classify(roof, 0, 0, 0));
        assertEquals(Kind.NONE, BucketSourceRules.classify(roof, 2, 0, 2));
        // with a free cell beside the lid it is a lid the way it always was
        Grid edge = frozenPond().set(Cell.STONE, -2, 1, -2, 2, 1, 2).set(Cell.AIR, 3, 1, 0, 3, 1, 0);
        assertEquals(Kind.LID, BucketSourceRules.classify(edge, 2, 0, 0));
    }

    @Test
    public void aDirtPathOverTheSourceIsStillNotALid() {
        // has a body, isn't a full block, isn't ice: same as before, open if the water beside it is open
        Grid path = frozenPond().set(Cell.AIR, -2, 1, -2, 2, 1, 2).set(Cell.PATH, 0, 1, 0, 0, 1, 0);
        assertFalse(BucketSourceRules.lid(path, 0, 1, 0));
        assertEquals(Kind.OPEN, BucketSourceRules.classify(path, 0, 0, 0));
        // and with stone all round it, nothing to scoop from and no lid to dig
        Grid shut = new Grid()
                .set(Cell.AIR, -3, 2, -3, 3, 5, 3)
                .set(Cell.WATER, 0, 0, 0, 0, 0, 0)
                .set(Cell.PATH, 0, 1, 0, 0, 1, 0);
        assertEquals(Kind.NONE, BucketSourceRules.classify(shut, 0, 0, 0));
    }
}
