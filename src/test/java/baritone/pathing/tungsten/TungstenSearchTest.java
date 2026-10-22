package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the rule and the search, on made up blocks. src is a single block at (8, 63, 8), feet at y 64
public class TungstenSearchTest {

    private static final int SX = 8, SY = 63, SZ = 8;

    private static ArrayWorld world() {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.set(SX, SY, SZ, ArrayWorld.STONE);
        return w;
    }

    private static PlayerSim standing(ArrayWorld w) {
        return ReachTableTest.standing(w);
    }

    private static Landing land(int dx, int dy, int dz) {
        return new Landing(SX + dx, SY + 1 + dy, SZ + dz);
    }

    // play keys on a fresh sim and say where it ends up
    private static SimState replay(ArrayWorld w, java.util.List<PlayerSim.Controls> keys) {
        PlayerSim sim = standing(w);
        for (PlayerSim.Controls c : keys) {
            assertTrue(sim.failure(), Keys.step(sim, c));
        }
        return SimState.of(sim);
    }

    @Test
    public void theRuleMakesAStraightThreeGapAndADiagonalOne() {
        for (int[] d : new int[][]{{4, 0}, {0, 4}, {-4, 0}, {3, 3}, {-3, 2}, {2, -3}}) {
            ArrayWorld w = world();
            w.set(SX + d[0], SY, SZ + d[1], ArrayWorld.STONE);
            Landing land = land(d[0], 0, d[1]);
            JumpPolicy.Rollout r = JumpPolicy.best(standing(w), land);
            assertNotNull(d[0] + " " + d[1], r);
            SimState end = replay(w, r.keys());
            assertTrue(end.toString(), end.onGround && land.holdsWell(end.x, end.y, end.z));
            assertTrue(end.toString(), end.horizontalSpeed() < 0.05);
        }
    }

    @Test
    public void theRuleSaysNoToAFourGapFromAStandingStart() {
        ArrayWorld w = world();
        w.set(SX + 6, SY, SZ, ArrayWorld.STONE);
        assertNull(JumpPolicy.best(standing(w), land(6, 0, 0)));
    }

    @Test
    public void aShortHopDoesNotOvershoot() {
        // one block of gap: the edge jump would carry us well past a single block, the rule has to jump early or steer back
        ArrayWorld w = world();
        w.set(SX + 2, SY, SZ, ArrayWorld.STONE);
        JumpPolicy.Rollout r = JumpPolicy.best(standing(w), land(2, 0, 0));
        assertNotNull(r);
        SimState end = replay(w, r.keys());
        assertTrue(end.toString(), land(2, 0, 0).holdsWell(end.x, end.y, end.z));
    }

    @Test
    public void dropsUseTheRunOffWhenTheJumpWouldHurt() {
        // two down: a jump peaks 1.25 up and the 3.25 fall hurts, running off the edge doesn't
        ArrayWorld w = world();
        w.set(SX + 2, SY - 2, SZ + 2, ArrayWorld.STONE);
        JumpPolicy.Rollout r = JumpPolicy.best(standing(w), land(2, -2, 2));
        assertNotNull(r);
        assertEquals(JumpPolicy.Variant.RUN_OFF, r.variant());
    }

    @Test
    public void aCeilingAtHeadHeightIsRespected() {
        // a block over the gap at y 66 (the head tops out at 64 + 1.8 + 1.25). the sim bonks, and the long jump can't make it
        ArrayWorld w = world();
        w.set(SX + 4, SY, SZ, ArrayWorld.STONE);
        for (int x = SX; x <= SX + 4; x++) {
            w.set(x, SY + 3, SZ, ArrayWorld.STONE);
        }
        JumpPolicy.Rollout r = JumpPolicy.best(standing(w), land(4, 0, 0));
        assertNull(r == null ? null : r.toString(), r);
    }

    @Test
    public void theRuleWontJumpOntoIce() {
        ArrayWorld w = world();
        w.set(SX + 3, SY, SZ, ArrayWorld.ICE);
        assertNull(JumpPolicy.best(standing(w), land(3, 0, 0)));
    }

    @Test
    public void theSearchFindsWhatTheRuleFinds() {
        ArrayWorld w = world();
        w.set(SX + 3, SY, SZ + 3, ArrayWorld.STONE);
        Landing land = land(3, 0, 3);
        PlayerSim start = standing(w);
        TungstenSearch.Plan plan = TungstenSearch.search(start, land, region(start, land), 400_000, null);
        assertNotNull(plan);
        SimState end = replay(w, plan.keys());
        assertTrue(end.toString(), land.holdsWell(end.x, end.y, end.z) && end.onGround);
        assertEquals(plan.states().get(plan.states().size() - 1).x, end.x, 1e-9);
    }

    @Test
    public void theSearchHopsAcrossAStepTheRuleCant() {
        // the landing is out of reach in one jump, but there's a block on the way: two jumps, which the rule never plans
        ArrayWorld w = world();
        w.set(SX + 3, SY, SZ + 1, ArrayWorld.STONE);
        w.set(SX + 6, SY, SZ - 1, ArrayWorld.STONE);
        Landing land = land(6, 0, -1);
        assertNull(JumpPolicy.best(standing(w), land));
        PlayerSim start = standing(w);
        TungstenSearch.Plan plan = TungstenSearch.search(start, land, region(start, land), 2_000_000, null);
        assertNotNull(plan);
        SimState end = replay(w, plan.keys());
        assertTrue(end.toString(), land.holdsWell(end.x, end.y, end.z) && end.onGround);
        System.out.println("two hops: " + plan.ticks() + " ticks, " + plan.nodes() + " nodes, " + plan.simTicks() + " sim ticks");
    }

    @Test
    public void theSearchGoesAroundAWall() {
        // a wall two high straight across the line, landing just past its end on the far side: a neo the rule can't see
        ArrayWorld w = world();
        w.fill(SX, SY, SZ - 1, SX, SY, SZ, ArrayWorld.STONE);
        w.fill(SX + 1, SY + 1, SZ - 3, SX + 1, SY + 2, SZ, ArrayWorld.STONE); // the wall, z 5 to 8, standing on nothing
        w.set(SX + 2, SY, SZ, ArrayWorld.STONE);
        Landing land = land(2, 0, 0);
        assertNull(JumpPolicy.best(standing(w), land));
        PlayerSim start = standing(w);
        TungstenSearch.Plan plan = TungstenSearch.search(start, land, region(start, land), 3_000_000, null);
        assertNotNull(plan);
        SimState end = replay(w, plan.keys());
        assertTrue(end.toString(), land.holdsWell(end.x, end.y, end.z) && end.onGround);
        System.out.println("neo: " + plan.ticks() + " ticks, " + plan.nodes() + " nodes, " + plan.simTicks() + " sim ticks");
    }

    @Test
    public void theSearchGivesUpOnTheImpossible() {
        ArrayWorld w = world();
        w.set(SX + 9, SY, SZ, ArrayWorld.STONE);
        Landing land = land(9, 0, 0);
        PlayerSim start = standing(w);
        assertNull(TungstenSearch.search(start, land, region(start, land), 200_000, null));
    }

    @Test
    public void aHurtingFallIsNotAPlan() {
        ArrayWorld w = world();
        w.set(SX + 2, SY - 5, SZ, ArrayWorld.STONE);
        Landing land = land(2, -5, 0);
        PlayerSim start = standing(w);
        assertNull(TungstenSearch.search(start, land, region(start, land), 300_000, null));
        assertFalse(JumpPolicy.best(standing(w), land) != null);
    }

    private static TungstenSearch.Region region(PlayerSim start, Landing land) {
        return TungstenSearch.Region.around(start.x, start.z, land.centerX(), land.centerZ(), Math.min(start.y, land.y()) - 4, 3);
    }
}
