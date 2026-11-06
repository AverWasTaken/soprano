package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// a run's wall: the floor zones in the nether, while the run lives, with the player exemption and no goal to exempt
public class ZoneWallTest {

    private static RunZones packAt(double x, double y, double z, long now) {
        RunZones zones = new RunZones();
        zones.addFloor(x, y, z, now);
        return zones;
    }

    @Test
    public void wallsThePackFloorInTheNether() {
        ZoneWall wall = new ZoneWall(packAt(0, 64, 0, 1000));
        // standing 40 out on the same floor
        wall.note(true, 1000, 40, 64, 0);
        assertTrue(wall.blocks(5, 64, 0));
        assertTrue(wall.blocks(5, 65, 0));
        // off the band and outside the circle are open
        assertFalse(wall.blocks(5, 70, 0));
        assertFalse(wall.blocks(20, 64, 0));
    }

    @Test
    public void nothingBeforeTheRunStartsOrAfterItStops() {
        ZoneWall wall = new ZoneWall(packAt(0, 64, 0, 1000));
        assertFalse(wall.blocks(5, 64, 0));
        wall.note(true, 1000, 40, 64, 0);
        assertTrue(wall.blocks(5, 64, 0));
        wall.off();
        assertFalse(wall.blocks(5, 64, 0));
    }

    @Test
    public void notAWallOutsideTheNether() {
        ZoneWall wall = new ZoneWall(packAt(0, 64, 0, 1000));
        wall.note(false, 1000, 40, 64, 0);
        assertFalse(wall.blocks(5, 64, 0));
    }

    @Test
    public void aRunFromInsideThePacksZoneCanStillLeaveIt() {
        ZoneWall wall = new ZoneWall(packAt(0, 64, 0, 1000));
        wall.note(true, 1000, 6, 64, 0);
        assertFalse(wall.blocks(5, 64, 0));
    }

    @Test
    public void aSecondPackIsStillAWallWhenWeStandInTheFirst() {
        RunZones zones = packAt(0, 64, 0, 1000);
        zones.addFloor(-40, 64, 0, 1000);
        ZoneWall wall = new ZoneWall(zones);
        wall.note(true, 1000, 5, 64, 0);
        assertFalse(wall.blocks(2, 64, 0));
        assertTrue(wall.blocks(-40, 64, 0));
    }

    @Test
    public void theZoneExpiresUnderTheRunToo() {
        ZoneWall wall = new ZoneWall(packAt(0, 64, 0, 1000));
        wall.note(true, 1000 + RunZones.EXPIRY_TICKS, 40, 64, 0);
        assertFalse(wall.blocks(5, 64, 0));
    }

    @Test
    public void aRunThatGoesNowhereGivesUpOnTheWall() {
        ZoneWall wall = new ZoneWall(new RunZones());
        assertFalse(wall.gaveUp(1000, 0, 0, 100));
        assertFalse(wall.gaveUp(1099, 1, 0, 100));
        assertTrue(wall.gaveUp(1100, 1, 0, 100));
    }

    @Test
    public void movingRestartsTheClock() {
        ZoneWall wall = new ZoneWall(new RunZones());
        wall.gaveUp(1000, 0, 0, 100);
        assertFalse(wall.gaveUp(1090, ZoneWall.STILL_BLOCKS, 0, 100));
        assertFalse(wall.gaveUp(1150, ZoneWall.STILL_BLOCKS, 0, 100));
        assertTrue(wall.gaveUp(1190, ZoneWall.STILL_BLOCKS, 0, 100));
        wall.resetClock();
        assertFalse(wall.gaveUp(1200, ZoneWall.STILL_BLOCKS, 0, 100));
    }

    @Test
    public void theCommittedRunsWallNeverGivesUp() {
        // its corner rules end it instead, the default patience is forever
        ZoneWall wall = new ZoneWall(new RunZones());
        wall.gaveUp(1000, 0, 0, Long.MAX_VALUE);
        assertFalse(wall.gaveUp(1000 + 20L * 60 * 60, 0, 0, Long.MAX_VALUE));
    }
}
