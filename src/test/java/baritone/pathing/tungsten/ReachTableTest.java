package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

// TungstenJumps only rolls the rule out for landings inside its REACH table, so the table had better cover every single block
// landing the rule can make from a standing start on a single block. if the rule gets better this fails, and the table grows
public class ReachTableTest {

    static final int SX = 8, SY = 63, SZ = 8;

    static PlayerSim standing(ArrayWorld w) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), w);
        sim.x = SX + 0.5;
        sim.y = SY + 1;
        sim.z = SZ + 0.5;
        sim.vy = -0.0784;
        sim.onGround = true;
        return sim;
    }

    @Test
    public void theReachTableCoversEverythingTheRuleLands() {
        int range = TungstenJumps.RANGE + 1;
        for (int dy = TungstenJumps.MAX_UP; dy >= -TungstenJumps.MAX_DOWN; dy--) {
            double furthest = 0;
            for (int dz = 0; dz <= range; dz++) {
                for (int dx = dz; dx <= range; dx++) { // symmetric, so one eighth is the lot
                    if (Math.max(dx, dz) < 2) {
                        continue;
                    }
                    ArrayWorld w = new ArrayWorld(40, 80, 40);
                    w.set(SX, SY, SZ, ArrayWorld.STONE);
                    w.set(SX + dx, SY + dy, SZ + dz, ArrayWorld.STONE);
                    if (JumpPolicy.best(standing(w), new Landing(SX + dx, SY + 1 + dy, SZ + dz)) != null) {
                        furthest = Math.max(furthest, Math.sqrt(dx * dx + dz * dz));
                    }
                }
            }
            double table = TungstenJumps.REACH[dy + TungstenJumps.MAX_DOWN];
            System.out.println("dy " + dy + ": furthest " + furthest + ", table " + table);
            assertTrue("dy " + dy + " lands " + furthest + " out, the table stops at " + table, furthest <= table);
            assertTrue("dy " + dy + " the table is way off: " + table + " for " + furthest, table - furthest < 1.5);
        }
    }
}
