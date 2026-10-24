package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

// run ups: the four block gap, and the jumps a standing start doesn't have in it
public class RunUpTest {

    @BeforeClass
    public static void boot() {
        adris.altoclef.util.helpers.ItemFixture.boot(); // (run on its own, nothing else has started the game's registries)
    }

    private static final int SX = 8, SY = 63, SZ = 8;

    // src at (SX, SZ), a runway of `runway` more blocks behind it along -x, the landing at dx dy dz
    static ArrayWorld world(int runway, byte floor, int dx, int dy, int dz, byte landing) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX - runway, SY, SZ, SX, SY, SZ, floor);
        w.set(SX + dx, SY + dy, SZ + dz, landing);
        return w;
    }

    static Landing land(int dx, int dy, int dz) {
        return new Landing(SX + dx, SY + 1 + dy, SZ + dz);
    }

    private static int flights(ArrayWorld w, Landing l, JumpPolicy.Rollout plan, StringBuilder fails, String name) {
        int landed = 0;
        for (long seed = 0; seed < 30; seed++) {
            JumpPilotTest.Outcome o = JumpPilotTest.fly(w, ReachTableTest.standing(w), l, plan.variant(), seed, 2.0);
            if (o.done) {
                landed++;
            } else {
                fails.append(name).append(" seed ").append(seed).append(": ").append(o.why).append('\n');
            }
        }
        return landed;
    }

    // the four block gap (and the four and one) on stone and off ice, with the aim jittering: planned, and flown every time
    @Test
    public void fourBlockGapsWithARunUp() {
        int[][] jumps = {{5, 0, 0}, {5, 0, 1}, {5, 0, -1}, {4, 0, 2}, {5, -1, 1}};
        StringBuilder fails = new StringBuilder();
        for (byte floor : new byte[]{ArrayWorld.STONE, ArrayWorld.PACKED_ICE}) {
            for (int[] j : jumps) {
                ArrayWorld w = world(4, floor, j[0], j[1], j[2], ArrayWorld.STONE);
                Landing l = land(j[0], j[1], j[2]);
                JumpPolicy.Rollout plan = JumpPolicy.forgiving(ReachTableTest.standing(w), l);
                String name = (floor == ArrayWorld.STONE ? "stone " : "ice ") + j[0] + " " + j[1] + " " + j[2];
                assertNotNull(name, plan);
                assertEquals(name, 30, flights(w, l, plan, fails, name));
            }
        }
        System.out.println(fails);
        // and no runway, no five block gap. (a four off a single block does go now: a step back to its far edge first and
        // the run across it, the parkour players' "1bm 4b")
        ArrayWorld w = world(0, ArrayWorld.STONE, 6, 0, 0, ArrayWorld.STONE);
        assertNull(JumpPolicy.best(ReachTableTest.standing(w), land(6, 0, 0)));
    }

    // down onto slime and bounced across to a ledge too far for any jump
    @Test
    public void slimeBounces() {
        int[][] cases = {{5, 4, 7, -1}, {5, 4, 7, -2}, {4, 4, 6, -3}, {6, 4, 7, -2}};
        StringBuilder fails = new StringBuilder();
        for (int[] c : cases) {
            ArrayWorld w = new ArrayWorld(40, 80, 40);
            w.set(SX, SY, SZ, ArrayWorld.STONE);
            w.fill(SX + c[1] - 1, SY - c[0], SZ - 1, SX + c[1] + 1, SY - c[0], SZ + 1, ArrayWorld.SLIME);
            w.set(SX + c[2], SY + c[3], SZ, ArrayWorld.STONE);
            Landing l = land(c[2], c[3], 0);
            String name = "slime " + c[0] + " down at " + c[1] + ", ledge " + c[2] + " " + c[3];
            JumpPolicy.Rollout plan = JumpPolicy.forgiving(ReachTableTest.standing(w), l);
            assertNotNull(name, plan);
            boolean bounced = false;
            for (SimState st : plan.states()) {
                bounced |= st.onGround && st.vy > 0.1;
            }
            assertTrue(name + " never bounced", bounced);
            assertEquals(name + "\n" + fails, 30, flights(w, l, plan, fails, name));
        }
    }
}
