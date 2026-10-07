package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.tasks.speedrun.gamer.portal.WaterFlow.Kind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WaterFlowTest {
    // stone floor at y=0, air above. x >= 10 is a lava pool one deep (y=0 is lava there too), z is unbounded air
    private static Kind flat(int x, int y, int z) {
        if (y < 0) {
            return Kind.SOLID;
        }
        if (y == 0) {
            return x >= 10 ? Kind.LAVA : Kind.SOLID;
        }
        return Kind.AIR;
    }

    @Test
    public void spreadsSevenCellsOnTheFlat() {
        WaterFlow.Result r = WaterFlow.run(WaterFlowTest::flat, new int[][]{{0, 1, 0}});
        assertTrue(r.hasWater(7, 1, 0));
        assertFalse(r.hasWater(8, 1, 0));
        assertEquals(1, (int) r.water.get(WaterFlow.key(7, 1, 0)));
    }

    @Test
    public void lavaSourcesUnderOrBesideWaterTurnToObsidianAndNothingElse() {
        // 4 cells from the lava edge, so the last cell of the flow (level 1) is the first lava cell
        WaterFlow.Result r = WaterFlow.run(WaterFlowTest::flat, new int[][]{{3, 1, 0}});
        assertTrue(r.isObsidian(10, 0, 0));
        assertFalse(r.isObsidian(11, 0, 0));
    }

    @Test
    public void waterBelowLavaDoesNothingToIt() {
        // lava on a shelf, the source underneath it: lava keeps its place
        WaterFlow.World w = (x, y, z) -> {
            if (y < 0) {
                return Kind.SOLID;
            }
            if (y == 1 && x == 0 && z == 0) {
                return Kind.LAVA;
            }
            if (y == 0 && (x != 0 || z != 0)) {
                return Kind.SOLID;
            }
            return Kind.AIR;
        };
        WaterFlow.Result r = WaterFlow.run(w, new int[][]{{0, 0, 0}});
        assertFalse(r.isObsidian(0, 1, 0));
    }

    @Test
    public void aFallingColumnDoesNotSpreadUntilItLands() {
        WaterFlow.Result r = WaterFlow.run(WaterFlowTest::flat, new int[][]{{0, 5, 0}});
        assertTrue(r.hasWater(0, 1, 0));
        assertFalse("nothing sideways halfway down", r.hasWater(1, 3, 0));
        assertTrue("the landing puddle spreads", r.hasWater(3, 1, 0));
    }

    @Test
    public void exposureLimitCutsTheFlowShort() {
        WaterFlow.Result r = WaterFlow.run(WaterFlowTest::flat, new int[][]{{0, 1, 0}}, 2);
        assertTrue(r.hasWater(2, 1, 0));
        assertFalse(r.hasWater(3, 1, 0));
    }
}
