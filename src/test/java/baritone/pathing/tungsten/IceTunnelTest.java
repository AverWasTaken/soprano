package baritone.pathing.tungsten;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

// a packed ice runway under a ceiling at head height that stops two blocks short of the edge: hop along it (every hop's push
// stays on ice) and out into gaps no stone run up gets anywhere near
public class IceTunnelTest {

    @BeforeClass
    public static void boot() {
        adris.altoclef.util.helpers.ItemFixture.boot(); // (run on its own, nothing else has started the game's registries)
    }

    private static final int SX = 20, SY = 63, SZ = 20;

    static ArrayWorld tunnel(int runway, int gap, boolean ceiling) {
        ArrayWorld w = new ArrayWorld(60, 80, 40);
        w.fill(SX - runway + 1, SY, SZ, SX, SY, SZ, ArrayWorld.PACKED_ICE);
        if (ceiling) {
            w.fill(SX - runway + 1, SY + 3, SZ, SX - 2, SY + 3, SZ, ArrayWorld.STONE);
        }
        w.set(SX + gap + 1, SY, SZ, ArrayWorld.STONE);
        return w;
    }

    static JumpPolicy.Rollout plan(int runway, int gap, boolean ceiling) {
        ArrayWorld w = tunnel(runway, gap, ceiling);
        PlayerSim s = ReachTableTest.standing(w);
        s.x = SX + 0.5;
        s.z = SZ + 0.5;
        return JumpPolicy.forgiving(s, new Landing(SX + gap + 1, SY + 1, SZ));
    }

    @Test
    public void fiveAndSixGapsOutOfTheTunnel() {
        assertNotNull(plan(8, 5, true));
        JumpPolicy.Rollout six = plan(10, 6, true);
        assertNotNull(six);
        assertTrue(six.variant().toString(), six.variant().hop() && six.variant().back() > 3.5F);
    }

    @Test
    public void withoutTheCeilingTheIceAloneDoesntGetASix() {
        // a plain run on ice tops out about where it does on stone, it's the hops under the ceiling that carry
        assertNull(plan(10, 6, false));
    }
}
