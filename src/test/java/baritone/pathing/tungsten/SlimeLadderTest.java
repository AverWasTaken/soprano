package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

// off a ledge, down onto slime, and the bounce up onto a ladder: slime to ladder
public class SlimeLadderTest {
    private static final int SX = 8, SY = 63, SZ = 8;

    // the ladder hangs on the east face of a wall at SX + 1, so it's in the SX + 2 column. the slime is `drop` under the ledge,
    // the ledge (three long) starts `dist` blocks east of the ladder
    static ArrayWorld world(int dist, int drop) {
        ArrayWorld w = new ArrayWorld(40, 80, 40);
        w.fill(SX + 1, SY - drop - 2, SZ - 1, SX + 1, SY + 5, SZ + 1, ArrayWorld.STONE);
        w.fill(SX + 2, SY - drop + 2, SZ, SX + 2, SY + 4, SZ, ArrayWorld.LADDER_ON_WEST);
        w.fill(SX + 2 + dist, SY, SZ, SX + 4 + dist, SY, SZ, ArrayWorld.STONE);
        w.fill(SX + 3, SY - drop, SZ - 1, SX + 1 + dist, SY - drop, SZ + 1, ArrayWorld.SLIME);
        return w;
    }

    static PlayerSim start(ArrayWorld w, int dist) {
        PlayerSim s = ReachTableTest.standing(w);
        s.x = SX + 2 + dist + 0.5;
        s.z = SZ + 0.5;
        return s;
    }

    @Test
    public void bounceOntoTheLadder() {
        StringBuilder report = new StringBuilder();
        boolean any = false;
        for (int dist = 4; dist <= 9; dist++) {
            for (int drop : new int[]{4, 6, 8}) {
                for (int dy = 0; dy <= 2; dy++) {
                    ArrayWorld w = world(dist, drop);
                    Landing l = Landing.grab(SX + 2, SY + 1 + dy, SZ);
                    JumpPolicy.Rollout r = JumpPolicy.forgiving(start(w, dist), l);
                    JumpPolicy.Rollout b = JumpPolicy.best(start(w, dist), l);
                    report.append(String.format("dist %d drop %d up %d: forgiving %s, best %s%n", dist, drop, dy,
                            r == null ? "-" : r.variant() + " " + r.ticks(), b == null ? "-" : b.variant() + " " + b.ticks()));
                    any |= r != null;
                }
            }
        }
        System.out.println(report);
        assertTrue(report.toString(), any);
    }
}
