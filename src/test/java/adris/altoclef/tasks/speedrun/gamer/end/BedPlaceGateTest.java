package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.Test;

public class BedPlaceGateTest {

    private static final BlockPos FOOT = new BlockPos(0, 65, 0);
    private static final BlockPos HEAD = FOOT.relative(Direction.EAST);

    private static AABB box(double x, double y, double z) {
        return new AABB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    @Test
    public void thePlaceGateWantsTheHeadWithinEight() {
        assertTrue(BedPlaceGate.headNear(7.9));
        assertTrue(BedPlaceGate.headNear(8));
        assertFalse(BedPlaceGate.headNear(8.1));
    }

    @Test
    public void headCellFollowsTheFacing() {
        assertEquals(new BlockPos(1, 65, 0), BedPlaceGate.headCell(FOOT, Direction.EAST));
        assertEquals(new BlockPos(0, 65, -1), BedPlaceGate.headCell(FOOT, Direction.NORTH));
    }

    @Test
    public void aFarAwayBoxIsClear() {
        assertTrue(BedPlaceGate.clear(List.of(box(10, 70, 10)), FOOT, HEAD, false));
    }

    @Test
    public void aBoxOnTheFootOrTheHeadIsNot() {
        assertFalse(BedPlaceGate.clear(List.of(box(0.5, 65.5, 0.5)), FOOT, HEAD, true));
        assertFalse(BedPlaceGate.clear(List.of(box(2.5, 65.5, 0.5)), FOOT, HEAD, true));
    }

    @Test
    public void aMovingDragonGetsMoreRoomThanASittingOne() {
        // box edge 0.7 off the head cell (x 2): clear sitting (0.5 grow), not clear descending (1.0 grow)
        List<AABB> parts = List.of(new AABB(2.7, 64, -1, 5, 67, 1));
        assertTrue(BedPlaceGate.clear(parts, FOOT, HEAD, true));
        assertFalse(BedPlaceGate.clear(parts, FOOT, HEAD, false));
    }

    @Test
    public void descentPerchesOnlyAboveThePillarTop() {
        assertTrue(BedPlaceGate.perch(true, false, false, false, false, true));
        assertFalse(BedPlaceGate.perch(true, false, false, true, false, true));
        assertFalse(BedPlaceGate.perch(false, false, false, false, false, true));
    }

    @Test
    public void aSittingDragonPerchesWhateverItsHeight() {
        assertTrue(BedPlaceGate.perch(false, true, false, true, false, true));
    }

    @Test
    public void aFlamingDragonIsNotAPerch() {
        assertFalse(BedPlaceGate.perch(false, true, true, true, false, true));
        assertFalse(BedPlaceGate.perch(false, true, true, true, true, true));
    }

    @Test
    public void aSitterOverThePillarWithNoBedGoesBackToWaiting() {
        assertFalse(BedPlaceGate.perch(false, true, false, true, false, false));
        // a bed that is down stays ours to click
        assertTrue(BedPlaceGate.perch(false, true, false, true, true, false));
    }

    @Test
    public void foodOnlyStepsAsideForABed() {
        assertFalse(BedPlaceGate.holdFood(false, false));
        assertTrue(BedPlaceGate.holdFood(true, false));
        assertTrue(BedPlaceGate.holdFood(false, true));
    }
}
