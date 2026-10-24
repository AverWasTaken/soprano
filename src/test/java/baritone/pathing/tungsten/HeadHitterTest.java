package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

// four block gaps under a ceiling: hopping along the runway, every hop bonking the ceiling, is what gets there
public class HeadHitterTest {

    @BeforeClass
    public static void boot() {
        adris.altoclef.util.helpers.ItemFixture.boot(); // (run on its own, nothing else has started the game's registries)
    }

    private static final int SX = 8, SY = 63, SZ = 8;

    // a runway of `runway` blocks behind the takeoff under a ceiling `ceil` over the feet, the ceiling running on over the gap
    // to `over` blocks past the takeoff
    static ArrayWorld world(int runway, int ceil, int over, int dx, int dy, int dz) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX - runway, SY, SZ, SX, SY, SZ, ArrayWorld.STONE);
        w.fill(SX - runway, SY + 1 + ceil, SZ - 1, SX + over, SY + 1 + ceil, SZ + 1, ArrayWorld.STONE);
        w.set(SX + dx, SY + dy, SZ + dz, ArrayWorld.STONE);
        return w;
    }

    // a ceiling one over the head along the runway and the takeoff (and three up over the gap, or nothing): hop along under
    // it and take the four block gap, the 4+1, and five down one
    @Test
    public void hoppingUnderTheCeiling() {
        int[][] jumps = {{5, 0, 0, 3}, {5, 0, 1, 3}, {6, -1, 0, 0}, {6, -1, 0, 3}};
        StringBuilder fails = new StringBuilder();
        for (int[] j : jumps) {
            ArrayWorld w = world(5, 2, 0, j[0], j[1], j[2]);
            if (j[3] > 0) {
                w.fill(SX + 1, SY + 1 + j[3], SZ - 1, SX + j[0] + 1, SY + 1 + j[3], SZ + 2, ArrayWorld.STONE);
            }
            Landing l = new Landing(SX + j[0], SY + 1 + j[1], SZ + j[2]);
            JumpPolicy.Rollout plan = JumpPolicy.forgiving(ReachTableTest.standing(w), l);
            String name = j[0] + " " + j[1] + " " + j[2] + (j[3] > 0 ? " under a ceiling" : "");
            assertNotNull(name, plan);
            assertTrue(name + " " + plan.variant(), plan.variant().hop());
            for (long seed = 0; seed < 20; seed++) {
                JumpPilotTest.Outcome o = JumpPilotTest.fly(w, ReachTableTest.standing(w), l, plan.variant(), seed, 2.0);
                if (!o.done) {
                    fails.append(name).append(" seed ").append(seed).append(": ").append(o.why).append('\n');
                }
            }
        }
        assertEquals(fails.toString(), 0, fails.length());
    }
}
