package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlWalkWallTest {
    private static final double[][] BRUTE = {{100, 520, 24}};

    @Test
    public void aDeathZoneIsAWallOnTheWayToTheForest() {
        // the straight line from the portal to the forest runs through the brute's spot
        assertTrue(PearlWalkWall.blocks(110, 525, BRUTE, 48, 24, 144, 772));
        assertFalse(PearlWalkWall.blocks(140, 525, BRUTE, 48, 24, 144, 772));
    }

    @Test
    public void theZoneWeStandInIsNoWall() {
        // the hunt's own snapshot of where we stand: inside the zone, the way out has to exist
        assertFalse(PearlWalkWall.blocks(110, 525, BRUTE, 105, 515, 144, 772));
    }

    @Test
    public void theZoneTheForestIsInIsNoWall() {
        assertFalse(PearlWalkWall.blocks(110, 525, BRUTE, 48, 24, 101, 530));
        // NaN = no goal known, the zone is a wall
        assertTrue(PearlWalkWall.blocks(110, 525, BRUTE, 48, 24, Double.NaN, Double.NaN));
    }

    @Test
    public void eachZoneIsExemptOnItsOwn() {
        // standing in one zone does not open the other one
        double[][] two = {{0, 0, 24}, {100, 0, 48}};
        assertFalse(PearlWalkWall.blocks(5, 5, two, 0, 0, 500, 500));
        assertTrue(PearlWalkWall.blocks(110, 0, two, 0, 0, 500, 500));
    }

    @Test
    public void noZonesNoWall() {
        assertFalse(PearlWalkWall.blocks(0, 0, new double[0][], 0, 0, 1, 1));
    }

    @Test
    public void aPearlInsideADeathZoneIsLeftAlone() {
        List<double[]> zones = List.of(new double[]{98, 528, 24});
        assertTrue(PearlWalkWall.inAny(80, 540, zones));
        // no exemptions here: where we stand does not make the pearl safer to fetch
        assertTrue(PearlWalkWall.inAny(98, 528, zones));
        assertFalse(PearlWalkWall.inAny(98, 553, zones));
        assertFalse(PearlWalkWall.inAny(0, 0, List.of()));
    }

    @Test
    public void bastionsBecomeZonesOfTheAvoidRadius() {
        List<double[]> z = PearlWalkWall.around(List.of(new RunState.Pos(10, 70, -20), new RunState.Pos(-5, 40, 7)), 48);
        assertEquals(2, z.size());
        assertArrayEquals(new double[]{10, -20, 48}, z.get(0), 0);
        assertArrayEquals(new double[]{-5, 7, 48}, z.get(1), 0);
    }
}
