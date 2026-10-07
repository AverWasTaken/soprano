package adris.altoclef.util.helpers;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoneDigRankTest {
    // we stand at the middle of cell (0, 64, 0)
    private static final double X = 0.5, Y = 64, Z = 0.5;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static double at(int x, int y, int z, boolean exposed) {
        return StoneDigRank.score(X, Y, Z, x, y, z, exposed);
    }

    @Test
    public void aWallAtOurLevelBeatsTheFloorWeStandOn() {
        // the floor is right there, 1 block down, and plain distance loved it
        assertTrue(at(3, 64, 0, true) < at(0, 63, 0, true));
        assertTrue(at(8, 64, 0, true) < at(0, 63, 0, true));
    }

    @Test
    public void feetLevelAndTheCellAboveCostTheSame() {
        assertEquals(at(2, 64, 0, true), at(2, 65, 0, true), 1e-9);
        // two up is a climb, 4 a block from there
        assertEquals(at(2, 65, 0, true) + 4, at(2, 66, 0, true), 1e-9);
    }

    @Test
    public void everythingBelowTheFeetIsPricedAsAHole() {
        double flat = at(2, 64, 0, true);
        assertEquals(flat + 10, at(2, 63, 0, true), 1e-9);
        assertEquals(flat + 20, at(2, 62, 0, true), 1e-9);
    }

    @Test
    public void theStoneWeStandOnIsTheLastResort() {
        // nothing else within 16 and the floor is the pick, that is the only time we dig down
        double standing = at(0, 63, 0, true);
        assertTrue(standing > at(16, 64, 0, true));
        assertTrue(standing < at(40, 64, 0, true));
        // the whole column under us, not just the top
        assertTrue(at(0, 62, 0, true) > standing);
        // a floor block next to us is not the column
        assertTrue(at(1, 63, 0, true) < standing);
    }

    @Test
    public void exposedStoneBeatsBuriedStoneAtTheSameSpot() {
        assertEquals(at(3, 64, 1, true) + StoneDigRank.BURIED, at(3, 64, 1, false), 1e-9);
        // and a short walk to a face beats a buried block next to us
        assertTrue(at(5, 64, 0, true) < at(1, 64, 0, false));
    }

    @Test
    public void aHillsideFiveBlocksOffBeatsAShaftIntoTheDirt() {
        // stone under a layer of grass is a buried block two down
        assertTrue(at(6, 65, 0, true) < at(0, 62, 0, false));
        assertTrue(at(6, 65, 0, true) < at(1, 62, 0, false));
    }

    @Test
    public void standingOnABlockTopWithFloatNoiseStillCountsAsThatLevel() {
        // 63.9999 is standing on top of y 63, our feet are in 64
        assertEquals(StoneDigRank.score(X, 64, Z, 2, 64, 0, true), StoneDigRank.score(X, 63.9999, Z, 2, 64, 0, true), 1e-9);
    }

    @Test
    public void onlyStoneishTargetsGetTheRanking() {
        assertTrue(StoneDigRank.stoneOnly(Blocks.STONE, Blocks.COBBLESTONE));
        assertTrue(StoneDigRank.stoneOnly(Blocks.DEEPSLATE));
        assertFalse(StoneDigRank.stoneOnly(Blocks.IRON_ORE));
        assertFalse(StoneDigRank.stoneOnly(Blocks.STONE, Blocks.DIRT));
        assertFalse(StoneDigRank.stoneOnly(Blocks.OAK_LOG));
        assertFalse(StoneDigRank.stoneOnly());
    }
}
