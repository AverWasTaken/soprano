package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

public class BedPlaceGateTest {

    private static final BlockPos FOOT = new BlockPos(0, 61, 0);

    @Test
    public void theHeadHalfGoesWhereWeFace() {
        assertEquals(new BlockPos(1, 61, 0), BedPlaceGate.headCell(FOOT, Direction.EAST));
        assertEquals(new BlockPos(0, 61, -1), BedPlaceGate.headCell(FOOT, Direction.NORTH));
    }

    // flaming is still the sit: it used to drop back to waiting, which put us in the ring right inside the wings
    @Test
    public void everySittingPhaseIsAPerchFlamingToo() {
        assertTrue(BedPlaceGate.perch(true, false, false));
        assertTrue(BedPlaceGate.perch(false, true, false));
        assertTrue(BedPlaceGate.perch(false, false, true));
        assertFalse(BedPlaceGate.perch(false, false, false));
    }

    @Test
    public void theYawFreezesFromTheRoarOn() {
        assertFalse(BedPlaceGate.yawFrozen(false, false));
        assertTrue(BedPlaceGate.yawFrozen(true, false));
        assertTrue(BedPlaceGate.yawFrozen(false, true));
    }

    @Test
    public void lostReasonFirstMatchWins() {
        assertEquals("no bed", BedPlaceGate.lostReason(false, true, true, true, true));
        assertEquals("pathing", BedPlaceGate.lostReason(true, true, true, true, true));
        assertEquals("breath", BedPlaceGate.lostReason(true, false, true, true, true));
        assertEquals("mobdef", BedPlaceGate.lostReason(true, false, true, false, true));
        assertEquals("clutch", BedPlaceGate.lostReason(true, false, true, false, false));
        assertEquals("no click window", BedPlaceGate.lostReason(true, false, false, false, false));
    }
}
