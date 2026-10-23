package baritone.pathing.tungsten;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.ArrayWorld;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// chains by way of the rims of walled blocks, the CubeCraft way: hop up onto the strip outside a wall, walk round it, run
// along it and jump off the end
public class LedgesTest {

    private static final int Y = 62;

    @Test
    public void upOntoTheRimRoundItAndOff() {
        ArrayWorld w = new ArrayWorld(32, 90, 32);
        // two blocks to start on, a block up from them a 2x2 platform with a wall two high on every block (so the only
        // floor up there is the strip round the outside), and past it, across a three block gap, the landing at the
        // platform's height
        w.fill(8, Y, 12, 9, Y, 13, ArrayWorld.STONE);
        w.fill(8, Y, 10, 9, Y + 1, 11, ArrayWorld.STONE);
        w.fill(8, Y + 2, 10, 9, Y + 3, 11, ArrayWorld.WALL);
        w.set(13, Y + 1, 10, ArrayWorld.STONE);
        BetterBlockPos src = new BetterBlockPos(8, Y + 1, 13);
        Landing land = new Landing(13, Y + 2, 10);
        long t0 = System.nanoTime();
        TungstenSearch.Plan plan = Ledges.plan(w, src, Y + 1, land);
        System.out.println("ledges: " + (plan == null ? "none" : plan.ticks() + " ticks") + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        assertNotNull(plan);
        int jumps = 0;
        for (var k : plan.keys()) {
            if (k.jump()) {
                jumps++;
            }
        }
        assertTrue("two jumps at least: up onto the rim and off it, " + jumps, jumps >= 2);
        for (long seed = 0; seed < 30; seed++) {
            String why = NeoSearches.fly(NeoSearches.standing(w, src, Y + 1), land, plan, src.x + 0.5, src.z + 0.5, seed, NeoSearches.JITTER);
            assertNull("seed " + seed + ": " + why, why);
        }
    }

    @Test
    public void nothingToStandOnNoChain() {
        // no rim (the walls cover the whole of their blocks' tops), so no way: not a made up one
        ArrayWorld w = new ArrayWorld(32, 90, 32);
        w.fill(8, Y, 12, 9, Y, 13, ArrayWorld.STONE);
        w.fill(8, Y + 1, 10, 9, Y + 3, 11, ArrayWorld.STONE);
        w.set(15, Y + 1, 10, ArrayWorld.STONE);
        assertNull(Ledges.plan(w, new BetterBlockPos(8, Y + 1, 13), Y + 1, new Landing(15, Y + 2, 10)));
    }
}
