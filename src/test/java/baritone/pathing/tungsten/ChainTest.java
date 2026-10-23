package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

// jumps one after the other, the way the path executor hands them on: a jump that comes down where the next one can go from
// moving (JumpPilot.flow) is done on the touchdown, and the next pilot takes the player as it is, speed and all
public class ChainTest {

    private static final int SX = 6, SY = 63, SZ = 20;

    // each chain: block offsets from the one before, all single blocks floating in the air
    static final int[][][] CHAINS = {
            {{3, 0, 0}, {3, 0, 0}, {3, 0, 0}, {3, 0, 0}}, // straight threes
            {{3, 0, 0}, {4, 0, 0}, {4, 0, 0}}, // fours, which a standing start only makes with a run up
            {{3, 0, 1}, {3, 0, -1}, {3, 0, 1}, {2, 0, 2}}, // zig zag
            {{2, 1, 0}, {2, 1, 1}, {3, 0, 0}, {3, -1, 1}}, // up and down
            {{3, 0, 0}, {0, 0, 3}, {-3, 0, 0}, {0, 0, -3}}, // round a square, a right angle turn every time
    };

    static ArrayWorld world(int[][] chain, List<int[]> blocks) {
        ArrayWorld w = new ArrayWorld(48, 80, 48);
        int x = SX, y = SY, z = SZ;
        w.set(x, y, z, ArrayWorld.STONE);
        blocks.add(new int[]{x, y, z});
        for (int[] o : chain) {
            x += o[0];
            y += o[1];
            z += o[2];
            w.set(x, y, z, ArrayWorld.STONE);
            blocks.add(new int[]{x, y, z});
        }
        return w;
    }

    static final class Run {
        boolean done;
        int ticks, flowed;
        String why;
    }

    // fly the whole chain with the aim jittered like LookBehavior does it
    static Run fly(ArrayWorld w, List<int[]> blocks, boolean flow, long seed) {
        Random rnd = new Random(seed);
        PlayerSim real = ReachTableTest.standing(w);
        real.x = SX + 0.5;
        real.y = SY + 1;
        real.z = SZ + 0.5;
        float yaw = 0F;
        Run out = new Run();
        int t = 0;
        for (int i = 1; i < blocks.size(); i++) {
            int[] from = blocks.get(i - 1), to = blocks.get(i);
            Landing land = new Landing(to[0], to[1] + 1, to[2]);
            JumpPolicy.Variant planned = planned(w, from, land);
            if (planned == null) {
                out.why = "no plan for jump " + i;
                return out;
            }
            JumpPilot pilot = new JumpPilot(land, from[0] + 0.5, from[2] + 0.5, planned);
            if (flow && i + 1 < blocks.size()) {
                int[] after = blocks.get(i + 1);
                Landing next = new Landing(after[0], after[1] + 1, after[2]);
                JumpPolicy.Variant nextPlanned = planned(w, to, next);
                pilot.flow(live -> {
                    JumpPolicy.Rollout r = JumpPolicy.forgiving(live, next, nextPlanned);
                    return r != null && r.ticks() > 0;
                });
            }
            boolean landed = false;
            for (int k = 0; k < 250; k++, t++) {
                JumpPilot.Decision d = pilot.tick(real);
                if (d.status() == JumpPilot.Status.DONE) {
                    landed = true;
                    if (pilot.flowed) {
                        out.flowed++;
                    }
                    break;
                }
                if (d.status() != JumpPilot.Status.PRESS) {
                    out.why = "jump " + i + ": " + d.status() + " " + d.why() + " at " + SimState.of(real);
                    return out;
                }
                PlayerSim.Controls c = d.keys();
                double r = rnd.nextDouble() - 0.5;
                if (Math.abs(r) < 0.1) {
                    r *= 4;
                }
                float desired = c.moveYaw() + (float) ((rnd.nextDouble() - 0.5) * 0.01 + r * 2.0);
                yaw += Math.round(Mth.wrapDegrees(desired - yaw) / 0.15F) * 0.15F;
                if (!Keys.step(real, new PlayerSim.Controls(c.forward(), c.left(), c.jump(), c.sprint(), yaw, yaw))) {
                    out.why = "sim: " + real.failure();
                    return out;
                }
            }
            if (!landed) {
                out.why = "jump " + i + " timed out";
                return out;
            }
        }
        out.done = true;
        out.ticks = t;
        return out;
    }

    // what the planner would hand the movement: the rule's pick from a standing start in the middle of the takeoff block
    static JumpPolicy.Variant planned(ArrayWorld w, int[] from, Landing land) {
        PlayerSim s = ReachTableTest.standing(w);
        s.x = from[0] + 0.5;
        s.y = from[1] + 1;
        s.z = from[2] + 0.5;
        JumpPolicy.Rollout r = JumpPolicy.forgiving(s, land);
        return r == null ? null : r.variant();
    }

    @Test
    public void chainsAreFasterFlowingAndStillLandEveryTime() {
        StringBuilder report = new StringBuilder(), fails = new StringBuilder();
        boolean faster = true;
        for (int c = 0; c < CHAINS.length; c++) {
            List<int[]> blocks = new ArrayList<>();
            ArrayWorld w = world(CHAINS[c], blocks);
            long stopTicks = 0, flowTicks = 0;
            int flowed = 0, runs = 30;
            for (long seed = 0; seed < runs; seed++) {
                Run stop = fly(w, blocks, false, seed), go = fly(w, blocks, true, seed);
                if (!stop.done) {
                    fails.append("chain ").append(c).append(" stopping, seed ").append(seed).append(": ").append(stop.why).append('\n');
                }
                if (!go.done) {
                    fails.append("chain ").append(c).append(" flowing, seed ").append(seed).append(": ").append(go.why).append('\n');
                }
                stopTicks += stop.ticks;
                flowTicks += go.ticks;
                flowed += go.flowed;
            }
            report.append(String.format("chain %d: stopping %.1f ticks, flowing %.1f ticks, %.1f of %d touchdowns flowed%n",
                    c, stopTicks / (double) runs, flowTicks / (double) runs, flowed / (double) runs, CHAINS[c].length - 1));
            faster &= flowTicks < stopTicks;
        }
        System.out.println(report);
        assertTrue(fails.toString(), fails.length() == 0);
        assertTrue(report.toString(), faster);
    }
}
