package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// the pilot flying jumps against a "real" player whose aim is off the way LookBehavior's is: randomLooking113 (up to a degree
// either side, every tick) and the mouse's 0.15 degree steps at the default sensitivity
public class JumpPilotTest {

    private static final int SX = 8, SY = 63, SZ = 8;

    // dx dy dz of the landing from a single block takeoff
    private static final int[][] JUMPS = {
            {2, 0, 2}, {3, 0, 2}, {3, 0, 3}, {2, 1, 2}, {3, -1, 3}, {-2, 0, 3}, {4, 0, 1}, {1, 0, -4}, {2, 0, 0}, {4, 0, 0},
            {3, 1, 1}, {2, -2, 2}, {4, -1, 3},
    };

    static final class Outcome {
        boolean done;
        String why;
        int ticks;
        SimState end;
    }

    // fly it. jitter 0 is a perfect aim
    static Outcome fly(ArrayWorld w, PlayerSim real, Landing land, JumpPolicy.Variant planned, long seed, double jitter) {
        Random rnd = new Random(seed);
        JumpPilot pilot = new JumpPilot(land, SX + 0.5, SZ + 0.5, planned);
        float yaw = 0F;
        Outcome out = new Outcome();
        for (int t = 0; t < 200; t++) {
            JumpPilot.Decision d = pilot.tick(real);
            if (d.status() == JumpPilot.Status.DONE) {
                out.done = true;
                out.ticks = t;
                out.end = SimState.of(real);
                return out;
            }
            if (d.status() != JumpPilot.Status.PRESS) {
                out.why = d.status() + " " + d.why() + " at " + SimState.of(real);
                return out;
            }
            PlayerSim.Controls k = d.keys();
            double r = rnd.nextDouble() - 0.5;
            if (Math.abs(r) < 0.1) {
                r *= 4;
            }
            float desired = k.moveYaw() + (float) ((rnd.nextDouble() - 0.5) * 0.01 + r * jitter);
            float delta = Mth.wrapDegrees(desired - yaw);
            yaw += Math.round(delta / 0.15F) * 0.15F;
            if (!Keys.step(real, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw))) {
                out.why = "sim: " + real.failure();
                return out;
            }
        }
        out.why = "timeout";
        return out;
    }

    private static ArrayWorld world(int[] j) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.set(SX, SY, SZ, ArrayWorld.STONE);
        w.set(SX + j[0], SY + j[1], SZ + j[2], ArrayWorld.STONE);
        return w;
    }

    private static Landing land(int[] j) {
        return new Landing(SX + j[0], SY + 1 + j[1], SZ + j[2]);
    }

    @Test
    public void everyJumpTheRuleSignsOffOnIsFlownWithAPerfectAim() {
        for (int[] j : JUMPS) {
            ArrayWorld w = world(j);
            JumpPolicy.Rollout plan = JumpPolicy.best(ReachTableTest.standing(w), land(j));
            assertTrue(j[0] + " " + j[1] + " " + j[2], plan != null);
            Outcome o = fly(w, ReachTableTest.standing(w), land(j), plan.variant(), 1, 0);
            assertTrue(j[0] + " " + j[1] + " " + j[2] + ": " + o.why, o.done);
            // the pilot may pick a slower variant than the planner's quickest, one that forgives a bad aim more
            assertTrue(j[0] + " " + j[1] + " " + j[2] + " took " + o.ticks, o.ticks <= plan.ticks() + 12);
        }
    }

    @Test
    public void andWithTheAimJitteringLikeTheRealThing() {
        int flights = 0, landed = 0;
        StringBuilder fails = new StringBuilder();
        for (int[] j : JUMPS) {
            ArrayWorld w = world(j);
            JumpPolicy.Rollout plan = JumpPolicy.best(ReachTableTest.standing(w), land(j));
            for (long seed = 0; seed < 40; seed++) {
                flights++;
                Outcome o = fly(w, ReachTableTest.standing(w), land(j), plan.variant(), seed, 2.0);
                if (o.done) {
                    landed++;
                } else {
                    fails.append(j[0]).append(' ').append(j[1]).append(' ').append(j[2]).append(" seed ").append(seed).append(": ").append(o.why).append('\n');
                }
            }
        }
        System.out.println("jittered: " + landed + "/" + flights + "\n" + fails);
        assertTrue(fails.toString(), landed == flights);
    }

    @Test
    public void aRunUpTheWrongWayIsTurnedRound() {
        // the movement before handed us over moving fast sideways: the pilot plans from that, or stops and starts over
        int[] j = {3, 0, 3};
        ArrayWorld w = world(j);
        w.fill(SX - 3, SY, SZ, SX, SY, SZ, ArrayWorld.STONE); // a runway coming in from -x
        PlayerSim real = ReachTableTest.standing(w);
        real.x = SX - 1.5;
        real.vx = 0.28;
        real.sprinting = true;
        Outcome o = fly(w, real, land(j), JumpPolicy.Variant.EDGE, 7, 2.0);
        assertTrue(o.why, o.done);
    }
}
