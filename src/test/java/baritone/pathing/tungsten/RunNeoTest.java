package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

// the triple neo from right behind the wall, off a run of bunny hops (RunNeo): planned, and then played with the aim
// wobbling the way LookBehavior wobbles it, landing nearly every time
public class RunNeoTest {

    @BeforeClass
    public static void boot() {
        adris.altoclef.util.helpers.ItemFixture.boot();
    }

    private static final int SX = 20, SY = 63, SZ = 8;

    // a runway in line with a wall three thick (its end on our row, reaching three rows to -z), the open row +z beside it,
    // and the landing block right after it: the Parkour Lab's, section 15
    static ArrayWorld world(int runway) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX - runway, SY, SZ, SX, SY, SZ, ArrayWorld.STONE);
        w.fill(SX + 1, SY - 2, SZ - 3, SX + 3, SY + 3, SZ, ArrayWorld.STONE);
        w.set(SX + 4, SY, SZ, ArrayWorld.STONE);
        return w;
    }

    @Test
    public void aTripleNeoFromBehindOffARunOfHops() {
        int runway = 13;
        ArrayWorld w = world(runway);
        int landed = 0;
        StringBuilder fails = new StringBuilder();
        for (long seed = 0; seed < 20; seed++) {
            RunNeo rn = new RunNeo(w, SX, SZ, SY + 1, 1, 0, 0, 1, 3);
            assertTrue("no plan", rn.plan(runway));
            PlayerSim s = new PlayerSim(PlayerSim.Params.vanilla(), w);
            s.x = rn.worldX(rn.startU, RunNeo.lane());
            s.z = rn.worldZ(rn.startU, RunNeo.lane());
            s.y = SY + 1;
            s.vy = -0.0784;
            s.onGround = true;
            Random rnd = new Random(seed);
            float yaw = rn.heading(0);
            String result = "timeout";
            for (int t = 0; t < 200; t++) {
                PlayerSim.Controls k = rn.decide(s);
                double r = rnd.nextDouble() - 0.5;
                if (Math.abs(r) < 0.1) {
                    r *= 4;
                }
                float desired = k.moveYaw() + (float) ((rnd.nextDouble() - 0.5) * 0.01 + r * NeoSearches.JITTER);
                yaw += Math.round(Mth.wrapDegrees(desired - yaw) / 0.15F) * 0.15F;
                assertTrue(Keys.step(s, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw)));
                double rx = s.x - SX;
                if (s.onGround && rx > 1.0) {
                    result = rx > 4.0 && rx < 5.3 && Math.abs(s.y - (SY + 1)) < 1e-6 ? "ok" : String.format("came down at %.3f %.3f %.3f", rx, s.y - SY, s.z - SZ);
                    break;
                }
                if (s.y < SY - 1) {
                    result = String.format("fell at %.3f %.3f", rx, s.z - SZ);
                    break;
                }
            }
            if (result.equals("ok")) {
                landed++;
            } else {
                fails.append("seed ").append(seed).append(": ").append(result).append('\n');
            }
        }
        assertTrue(landed + "/20\n" + fails, landed >= 18);
    }
}
