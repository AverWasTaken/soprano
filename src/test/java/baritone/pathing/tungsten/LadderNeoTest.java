package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

// round the end of a wall and onto a ladder (or vine) hung on its far side: a ladder neo
public class LadderNeoTest {

    private static final int SX = 8, SY = 63, SZ = 8;
    static boolean DEBUG = false;

    // a two wide runway, three long, ending at x = SX: the wall's row (SZ) and the open row beside it (SZ + 1). the wall is one
    // thick at x = SX + 1 from z = SZ - 3 to SZ, and the ladder is on its far face in the SZ column
    // the ladder two wide (SZ and SZ - 1), the way a parkour map builds one: catching either half counts
    static ArrayWorld world(boolean vine) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX - 2, SY, SZ, SX, SY, SZ + 1, ArrayWorld.STONE);
        w.fill(SX + 1, SY - 2, SZ - 3, SX + 1, SY + 4, SZ, ArrayWorld.STONE);
        w.fill(SX + 2, SY - 1, SZ - 1, SX + 2, SY + 4, SZ, vine ? ArrayWorld.VINE : ArrayWorld.LADDER_ON_WEST);
        return w;
    }

    static PlayerSim standing(ArrayWorld w, int dz) {
        PlayerSim sim = ReachTableTest.standing(w);
        sim.z += dz;
        return sim;
    }

    // from the open row. from right behind the wall there's no room to swing out, the planner takes off from the open row
    @Test
    public void theRuleGoesRoundTheWall() {
        for (boolean vine : new boolean[]{false, true}) {
            for (int dz = 1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    ArrayWorld w = world(vine);
                    Landing l = Landing.grab(SX + 2, SY + 1 + dy, SZ);
                    PlayerSim start = standing(w, dz);
                    assertTrue(JumpPolicy.blocked(w, start.x, start.y, start.z, l.centerX(), l.y(), l.centerZ()));
                    JumpPolicy.Rollout r = JumpPolicy.forgiving(start, l);
                    assertNotNull((vine ? "vine" : "ladder") + " from row " + dz + " to " + dy + " up", r);
                    assertTrue(r.last().climbing);
                }
            }
        }
    }

    @Test
    public void andTheBotFliesItWithItsAimWobbling() {
        int flights = 0, landed = 0;
        StringBuilder fails = new StringBuilder();
        for (boolean vine : new boolean[]{false, true}) {
            for (int dz = 1; dz <= 1; dz++) {
                ArrayWorld w = world(vine);
                Landing l = Landing.grab(SX + 2, SY + 1, SZ);
                for (long seed = 0; seed < 40; seed++) {
                    flights++;
                    String why = fly(w, standing(w, dz), l, SX + 0.5, SZ + dz + 0.5, seed);
                    if (why == null) {
                        landed++;
                    } else {
                        fails.append(vine ? "vine" : "ladder").append(" row ").append(dz).append(" seed ").append(seed).append(": ").append(why).append('\n');
                    }
                }
            }
        }
        System.out.println("ladder neos: " + landed + "/" + flights + "\n" + fails);
        assertEquals(fails.toString(), flights, landed);
    }

    // the pilot against a player whose aim is off the way LookBehavior's is. null when it's hanging on the ladder in its cell
    static String fly(ArrayWorld w, PlayerSim real, Landing l, double sx, double sz, long seed) {
        Random rnd = new Random(seed);
        JumpPilot pilot = new JumpPilot(l, sx, sz, JumpPolicy.Variant.EDGE);
        float yaw = 0F;
        for (int t = 0; t < 300; t++) {
            JumpPilot.Decision d = pilot.tick(real);
            if (d.status() == JumpPilot.Status.DONE) {
                return null;
            }
            if (d.status() != JumpPilot.Status.PRESS) {
                return d.status() + " " + d.why() + " at " + SimState.of(real);
            }
            PlayerSim.Controls k = d.keys();
            if (DEBUG && seed == 0) {
                System.out.println(t + " " + SimState.of(real) + (real.climbing ? " C" : "") + " " + k);
            }
            double r = rnd.nextDouble() - 0.5;
            if (Math.abs(r) < 0.1) {
                r *= 4;
            }
            float desired = k.moveYaw() + (float) (r * 2.0);
            yaw += Math.round(net.minecraft.util.Mth.wrapDegrees(desired - yaw) / 0.15F) * 0.15F;
            if (!Keys.step(real, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw))) {
                return "sim: " + real.failure();
            }
        }
        return "timeout";
    }
}
