package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalDigTest {
    private static final int PORTAL_Y = -20;
    private static final int MINUTE = 1200;

    // flat stone at y 63, nothing wet. loaded flips so the retry window has something to retry for
    private static final class World implements RoomRetarget.Probe {
        boolean loaded = true;

        @Override
        public boolean loaded(int x, int z) {
            return loaded;
        }

        @Override
        public int topY(int x, int z) {
            return 63;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y <= 63;
        }

        @Override
        public boolean fluid(int x, int y, int z) {
            return false;
        }
    }

    private final World world = new World();
    private final PortalDig dig = new PortalDig();

    private int at(double x, double y, double z) {
        return dig.plan(x, y, z, 0, PORTAL_Y, 0, world);
    }

    // run the clock with the player standing still at the same spot
    private int stand(int ticks, double x, double y, double z) {
        int last = PortalDig.WALK;
        for (int i = 0; i < ticks; i++) {
            last = at(x, y, z);
        }
        return last;
    }

    @Test
    public void nearPortalJustWalks() {
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 5, 10));
        assertFalse(dig.hasColumn());
    }

    @Test
    public void farBelowDigsFromDryLand() {
        assertEquals(64 - 8, at(10, 64, 10));
        assertTrue(dig.hasColumn());
        assertTrue(Math.hypot(dig.digX(), dig.digZ()) >= RoomRetarget.KEEP_OFF_RING);
    }

    // right above it counts as far: being close sideways does not make 80 blocks of rock any shorter
    @Test
    public void straightAboveTheRingStillDigs() {
        assertEquals(64 - 8, at(0, 64, 0));
    }

    @Test
    public void portalLevelButFarSidewaysDigsOnlyToTheFloor() {
        assertEquals(PORTAL_Y, at(120, PORTAL_Y + 5, 0));
    }

    @Test
    public void noDryLandWaitsOutTheWindowThenLooksAgain() {
        world.loaded = false;
        assertEquals(PortalDig.WALK, at(10, 64, 10));
        world.loaded = true;
        assertEquals(PortalDig.WALK, stand(30 * 20 - 1, 10, 64, 10));
        assertFalse(dig.hasColumn());
        assertEquals(64 - 8, at(10, 64, 10));
    }

    @Test
    public void segmentStepsDownOnTheColumnAndStaysPutOffIt() {
        assertEquals(56, at(10, 64, 10));
        assertEquals(56, at(30, 56, 30));
        assertEquals(48, at(dig.digX() + 0.5, 56, dig.digZ() + 0.5));
    }

    @Test
    public void segmentNeverGoesUnderThePortal() {
        at(10, 64, 10);
        // near kicks in 12 above it, so getting this far under it takes a fall down a cave
        assertEquals(PORTAL_Y, at(dig.digX() + 0.5, PORTAL_Y - 30, dig.digZ() + 0.5));
    }

    @Test
    public void aNewLowKeepsTheColumn() {
        at(10, 64, 10);
        int x = dig.digX();
        int z = dig.digZ();
        double y = 63;
        for (int i = 0; i < 4; i++) {
            stand(MINUTE - 1, x + 0.5, y, z + 0.5);
            y--;
            at(x + 0.5, y, z + 0.5);
        }
        assertEquals(x, dig.digX());
        assertEquals(z, dig.digZ());
    }

    @Test
    public void stuckColumnShiftsThenGivesUpForGood() {
        at(10, 64, 10);
        int x = dig.digX();
        int z = dig.digZ();
        // FOOTPRINT has 5 cells, the first is the column itself
        for (int shift = 1; shift < RoomCoverage.FOOTPRINT.length; shift++) {
            assertTrue(stand(MINUTE, x + 0.5, 64, z + 0.5) != PortalDig.WALK);
            assertEquals(x + RoomCoverage.FOOTPRINT[shift][0], dig.digX());
            assertEquals(z + RoomCoverage.FOOTPRINT[shift][1], dig.digZ());
        }
        assertEquals(PortalDig.WALK, stand(MINUTE, x + 0.5, 64, z + 0.5));
        // a low now doesn't bring it back, this life is done digging
        assertEquals(PortalDig.WALK, at(x + 0.5, 20, z + 0.5));
        // the next life gets a new one, EndPrepPhase.resetOnDeath
        assertEquals(64 - 8, new PortalDig().plan(10, 64, 10, 0, PORTAL_Y, 0, world));
    }

    @Test
    public void aClimbOverTheNearLineStaysOnTheWalk() {
        at(10, 64, 10);
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 12, 10));
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 13, 10));
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 20, 10));
    }

    @Test
    public void properlyAwayDigsAgain() {
        at(10, 64, 10);
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 12, 10));
        assertTrue(at(10, PORTAL_Y + 21, 10) != PortalDig.WALK);
    }

    @Test
    public void aStalledWalkInTheSlackGoesBackToDigging() {
        at(10, 64, 10);
        at(10, PORTAL_Y + 12, 10);
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 18, 10));
        dig.walkStalled();
        assertTrue(at(10, PORTAL_Y + 18, 10) != PortalDig.WALK);
    }

    @Test
    public void aStalledWalkInsideNearStaysOnTheWalk() {
        at(10, 64, 10);
        at(10, PORTAL_Y + 5, 10);
        dig.walkStalled();
        assertEquals(PortalDig.WALK, at(10, PORTAL_Y + 5, 10));
    }

    // coming down the shaft isn't a hand over until the line is actually crossed
    @Test
    public void justAboveTheLineOnTheWayDownStillDigs() {
        at(10, 64, 10);
        assertTrue(at(10, PORTAL_Y + 13, 10) != PortalDig.WALK);
    }

    // the time spent next to the portal isn't time the column failed
    @Test
    public void timeNearThePortalDoesNotCountAsStuck() {
        at(10, 64, 10);
        int x = dig.digX();
        stand(5 * MINUTE, 10, PORTAL_Y + 5, 10);
        assertEquals(x, dig.digX());
        at(10, 64, 10);
        assertEquals(x, dig.digX());
    }

    // open sea out to 150 around the portal, land past it, the room search's shore at 163
    private static final class Sea implements RoomRetarget.Probe {
        @Override
        public boolean loaded(int x, int z) {
            return true;
        }

        @Override
        public int topY(int x, int z) {
            return 63;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y <= 63 && !(wet(x, z) && y > 40);
        }

        @Override
        public boolean fluid(int x, int y, int z) {
            return wet(x, z) && y > 40 && y <= 63;
        }

        private static boolean wet(int x, int z) {
            return Math.hypot(x, z) < 150;
        }
    }

    private static final List<int[]> SHORE = List.of(new int[]{163, 64, 0});

    @Test
    public void noShoreInRangeDigsFromTheRememberedOne() {
        Sea sea = new Sea();
        assertTrue(dig.plan(163.5, 64, 0.5, 0, PORTAL_Y, 0, sea, SHORE) != PortalDig.WALK);
        assertEquals(163, dig.digX());
        assertEquals(0, dig.digZ());
        // and without it there's nothing to dig from, the plain walk it is
        assertEquals(PortalDig.WALK, new PortalDig().plan(163.5, 64, 0.5, 0, PORTAL_Y, 0, sea, List.of()));
    }

    // the bottom of a shaft 163 out still counts as near once it's down at the portal, not 12 above it in the water band
    @Test
    public void aFarShaftHandsOverAtThePortalLevel() {
        Sea sea = new Sea();
        dig.plan(163.5, 64, 0.5, 0, PORTAL_Y, 0, sea, SHORE);
        assertTrue(dig.plan(163.5, PORTAL_Y + 12, 0.5, 0, PORTAL_Y, 0, sea, SHORE) != PortalDig.WALK);
        assertEquals(PortalDig.WALK, dig.plan(163.5, PORTAL_Y + RoomRetarget.UNDER_SEA_TURN_ABOVE, 0.5, 0, PORTAL_Y, 0, sea, SHORE));
        // on the way over it climbs a bit, still the walk
        assertEquals(PortalDig.WALK, dig.plan(100, PORTAL_Y + 8, 0, 0, PORTAL_Y, 0, sea, SHORE));
    }

    // a respawn in the room (up on its stairs, even) is just the walk, sea over it or not
    @Test
    public void inTheRoomUnderTheSeaJustWalks() {
        assertEquals(PortalDig.WALK, dig.plan(4, PORTAL_Y + 6, 4, 0, PORTAL_Y, 0, new Sea(), SHORE));
    }

    // handed over at the bottom of a shaft 163 out with the portal under the sea: the leg, not the plain walk across the top
    @Test
    public void aFarPortalUnderTheSeaTakesTheLeg() {
        Sea sea = new Sea();
        dig.plan(163.5, 64, 0.5, 0, PORTAL_Y, 0, sea, SHORE);
        assertEquals(PortalDig.WALK, dig.plan(163.5, PORTAL_Y + 1, 0.5, 0, PORTAL_Y, 0, sea, SHORE));
        assertTrue(dig.legWanted(163.5, PORTAL_Y + 1, 0.5, 0, PORTAL_Y, 0, sea));
        // within a room of it the plain walk is a short goal again
        assertFalse(dig.legWanted(SeaLeg.HAND_OFF, PORTAL_Y + 1, 0, 0, PORTAL_Y, 0, sea));
    }

    // dry land over the portal: exactly the walk it always was
    @Test
    public void aLandPortalNeverTakesTheLeg() {
        assertEquals(PortalDig.WALK, stand(1, 100, PORTAL_Y + 1, 0));
        assertFalse(dig.legWanted(100, PORTAL_Y + 1, 0, 0, PORTAL_Y, 0, world));
    }

    // back up on the waves in the middle of the leg: the shaft again, nothing counted against anyone
    @Test
    public void surfacingGoesBackToTheShaft() {
        Sea sea = new Sea();
        dig.plan(163.5, 64, 0.5, 0, PORTAL_Y, 0, sea, SHORE);
        dig.plan(163.5, PORTAL_Y + 1, 0.5, 0, PORTAL_Y, 0, sea, SHORE);
        dig.surfaced();
        assertFalse(dig.legWanted(100, 62, 0, 0, PORTAL_Y, 0, sea));
        assertTrue(dig.plan(100, 62, 0, 0, PORTAL_Y, 0, sea, SHORE) != PortalDig.WALK);
        assertEquals(163, dig.digX());
    }

    // a phase restart in the tunnel, already down at the portal's level once: still the walk, not back to the shaft
    @Test
    public void aRestartInTheTunnelStaysOnTheWalk() {
        Sea sea = new Sea();
        dig.plan(163.5, 64, 0.5, 0, PORTAL_Y, 0, sea, SHORE);
        dig.plan(163.5, PORTAL_Y + 1, 0.5, 0, PORTAL_Y, 0, sea, SHORE);
        dig.walkStalled();
        assertEquals(PortalDig.WALK, dig.plan(100, PORTAL_Y + 6, 0, 0, PORTAL_Y, 0, sea, SHORE));
    }
}
