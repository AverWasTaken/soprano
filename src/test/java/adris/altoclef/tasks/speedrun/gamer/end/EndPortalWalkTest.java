package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// which portal block the walk aims at, and when it gives up on the goal and steers by hand
public class EndPortalWalkTest {

    // a lit stronghold portal: 3x3 of portal at y 30, the frame ring around it is not portal
    private static Set<BlockPos> stronghold(int x, int y, int z) {
        Set<BlockPos> portal = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                portal.add(new BlockPos(x + dx, y, z + dz));
            }
        }
        return portal;
    }

    // the exit fountain at the origin: 5x5 minus its corners minus the bedrock pillar
    private static Set<BlockPos> fountain(int y) {
        Set<BlockPos> portal = new HashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz < 6.25 && !(dx == 0 && dz == 0)) {
                    portal.add(new BlockPos(dx, y, dz));
                }
            }
        }
        return portal;
    }

    private static int interiorCount(Set<BlockPos> portal) {
        int n = 0;
        for (BlockPos p : portal) {
            if (EndPortalWalk.interior(p, portal::contains)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void onlyTheMiddleOfTheStrongholdPortal() {
        Set<BlockPos> portal = stronghold(100, 30, -50);
        assertTrue(EndPortalWalk.interior(new BlockPos(101, 30, -49), portal::contains));
        assertEquals(1, interiorCount(portal));
        // the rim block the eye was holding us over, and a corner
        assertFalse(EndPortalWalk.interior(new BlockPos(101, 30, -50), portal::contains));
        assertFalse(EndPortalWalk.interior(new BlockPos(100, 30, -50), portal::contains));
    }

    @Test
    public void fourBlocksOffTheFountainPillar() {
        Set<BlockPos> portal = fountain(62);
        assertEquals(20, portal.size());
        assertEquals(4, interiorCount(portal));
        assertTrue(EndPortalWalk.interior(new BlockPos(1, 62, 1), portal::contains));
        assertTrue(EndPortalWalk.interior(new BlockPos(-1, 62, 1), portal::contains));
        // next to the pillar, and out on the rim
        assertFalse(EndPortalWalk.interior(new BlockPos(0, 62, 1), portal::contains));
        assertFalse(EndPortalWalk.interior(new BlockPos(2, 62, 1), portal::contains));
    }

    @Test
    public void standingOnTheEyeIsTheLip() {
        // over the rim block at x+0.5, z+0.03, on the eye at +1.0
        assertTrue(EndPortalWalk.onTheLip(0, 1.0, -0.47));
        // on a frame next to it, and the fountain's bedrock rim
        assertTrue(EndPortalWalk.onTheLip(0, 0.8125, -1.0));
        assertTrue(EndPortalWalk.onTheLip(1.0, 1.0, 1.0));
    }

    @Test
    public void notTheLip() {
        // two blocks off, under the portal, up on a ledge
        assertFalse(EndPortalWalk.onTheLip(0, 1.0, -2.0));
        assertFalse(EndPortalWalk.onTheLip(0, -1.0, 0));
        assertFalse(EndPortalWalk.onTheLip(0, 3.0, -0.5));
    }

    @Test
    public void steersAfterThreeStillSecondsOnTheLip() {
        int ticks = 0;
        for (int i = 0; i < EndPortalWalk.STEER_AFTER - 1; i++) {
            ticks = EndPortalWalk.stillTicks(ticks, true, 0);
        }
        assertFalse(EndPortalWalk.steer(ticks));
        ticks = EndPortalWalk.stillTicks(ticks, true, 0.001);
        assertTrue(EndPortalWalk.steer(ticks));
    }

    @Test
    public void movingOrLeavingTheLipStartsOver() {
        assertEquals(0, EndPortalWalk.stillTicks(59, true, 0.2));
        assertEquals(0, EndPortalWalk.stillTicks(59, false, 0));
        assertEquals(1, EndPortalWalk.stillTicks(0, true, 0));
    }
}
