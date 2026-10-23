package baritone.pathing.tungsten;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.ArrayWorld;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// walking round something on the level, along the strip of floor outside a wall or a fence: found in no time, never a jump,
// and it holds up with the aim wobbling
public class RimWalkTest {

    private static final int Y = 62;

    private static TungstenSearch.Plan walk(ArrayWorld w, int sx, int sz, int dx, int dz) {
        long t0 = System.nanoTime();
        TungstenSearch.Plan plan = RimWalk.plan(w, new BetterBlockPos(sx, Y + 1, sz), Y + 1, new Landing(dx, Y + 1, dz));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("rim walk " + sx + "," + sz + " -> " + dx + "," + dz + ": " + (plan == null ? "none" : plan.ticks() + " ticks") + " in " + ms + " ms");
        // (the first one in a fresh JVM pays for loading and warming up the sim, after that they take a few tens of ms)
        assertTrue("took " + ms + " ms", ms < 2000);
        return plan;
    }

    private static void holdsUp(ArrayWorld w, int sx, int sz, int dx, int dz, TungstenSearch.Plan plan) {
        assertNotNull(plan);
        for (var k : plan.keys()) {
            assertTrue("a walk, not a jump", !k.jump());
        }
        Landing land = new Landing(dx, Y + 1, dz);
        for (long seed = 0; seed < 40; seed++) {
            String why = NeoSearches.fly(NeoSearches.standing(w, new BetterBlockPos(sx, Y + 1, sz), Y + 1), land, plan, sx + 0.5, sz + 0.5, seed, NeoSearches.JITTER);
            assertNull("seed " + seed + ": " + why, why);
        }
    }

    @Test
    public void alongTheEdgeOfABlockWithAWallOnIt() {
        // three blocks in a row, a wall post on the middle one
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.fill(8, Y, 10, 10, Y, 10, ArrayWorld.STONE);
        w.set(9, Y + 1, 10, ArrayWorld.WALL);
        holdsUp(w, 8, 10, 10, 10, walk(w, 8, 10, 10, 10));
    }

    @Test
    public void alongTheEdgeOfABlockWithAFenceOnIt() {
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.fill(8, Y, 10, 10, Y, 10, ArrayWorld.STONE);
        w.set(9, Y + 1, 10, ArrayWorld.FENCE);
        w.set(9, Y + 2, 10, ArrayWorld.FENCE);
        holdsUp(w, 8, 10, 10, 10, walk(w, 8, 10, 10, 10));
    }

    @Test
    public void roundAWalledPlatform() {
        // a 2x2 platform with a wall two high on every block (joined up, the way walls do), a block either side of it
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.fill(9, Y, 9, 10, Y, 10, ArrayWorld.STONE);
        w.fill(9, Y + 1, 9, 10, Y + 2, 10, ArrayWorld.WALL);
        w.set(8, Y, 10, ArrayWorld.STONE);
        w.set(11, Y, 9, ArrayWorld.STONE);
        holdsUp(w, 8, 10, 11, 9, walk(w, 8, 10, 11, 9));
    }

    @Test
    public void roundAFencedPlatform() {
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.fill(9, Y, 9, 10, Y, 10, ArrayWorld.STONE);
        w.fill(9, Y + 1, 9, 10, Y + 2, 10, ArrayWorld.FENCE);
        w.set(8, Y, 9, ArrayWorld.STONE);
        w.set(11, Y, 10, ArrayWorld.STONE);
        holdsUp(w, 8, 9, 11, 10, walk(w, 8, 9, 11, 10));
    }

    @Test
    public void roundThePillarsCorner() {
        // an L of floor with a pillar on the corner block: round the outside of the pillar
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.fill(8, Y, 10, 10, Y, 10, ArrayWorld.STONE);
        w.fill(10, Y, 11, 10, Y, 12, ArrayWorld.STONE);
        w.fill(10, Y + 1, 10, 10, Y + 3, 10, ArrayWorld.WALL);
        holdsUp(w, 8, 10, 10, 12, walk(w, 8, 10, 10, 12));
    }

    @Test
    public void roundAWalledPlatformThenAJump() {
        // the platform's in the way and the landing's across a gap past it: along the rim, stop, jump
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.fill(9, Y, 10, 10, Y, 11, ArrayWorld.STONE);
        w.fill(9, Y + 1, 10, 10, Y + 2, 11, ArrayWorld.WALL);
        w.set(8, Y, 10, ArrayWorld.STONE);
        w.set(10, Y, 13, ArrayWorld.STONE);
        TungstenSearch.Plan plan = walk(w, 8, 10, 10, 13);
        assertNotNull(plan);
        boolean jumped = false;
        for (var k : plan.keys()) {
            jumped |= k.jump();
        }
        assertTrue("jumps in the end", jumped);
        Landing land = new Landing(10, Y + 1, 13);
        for (long seed = 0; seed < 40; seed++) {
            String why = NeoSearches.fly(NeoSearches.standing(w, new BetterBlockPos(8, Y + 1, 10), Y + 1), land, plan, 8.5, 10.5, seed, NeoSearches.JITTER);
            assertNull("seed " + seed + ": " + why, why);
        }
    }

    @Test
    public void notOverAGap() {
        // the floor stops: that's a jump, not a walk
        ArrayWorld w = new ArrayWorld(24, 80, 24);
        w.set(8, Y, 10, ArrayWorld.STONE);
        w.set(11, Y, 10, ArrayWorld.STONE);
        w.set(9, Y + 1, 10, ArrayWorld.WALL);
        assertNull(walk(w, 8, 10, 11, 10));
    }
}
