package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.end.PillarBed.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

public class PillarBedTest {
    private static final BlockPos TOP = new BlockPos(0, 67, 0);

    @Test
    public void theStandIsTuckedWestOfThePillarOneBelowItsTop() {
        assertEquals(new BlockPos(-1, 66, 0), PillarBed.stand(TOP));
        assertEquals(new BlockPos(0, 68, 0), PillarBed.bed(TOP));
    }

    @Test
    public void atTheStandIsFlatAndFeet() {
        BlockPos stand = PillarBed.stand(TOP);
        assertTrue(PillarBed.atStand(stand, -0.5, 0.5, 66));
        assertTrue(PillarBed.atStand(stand, -0.2, 0.6, 66));
        // a jump is still at the stand
        assertTrue(PillarBed.atStand(stand, -0.5, 0.5, 67));
        assertFalse(PillarBed.atStand(stand, 0.1, 0.5, 66));
        // the well floor right under it passes the flat check and the bed is out of reach from there
        assertFalse(PillarBed.atStand(stand, -0.5, 0.5, 65));
    }

    @Test
    public void sittingOrLowIsSat() {
        assertTrue(PillarBed.sat(true, 90, 67));
        assertTrue(PillarBed.sat(false, 68.9, 67));
        assertFalse(PillarBed.sat(false, 69, 67));
    }

    @Test
    public void flyingGoesToTheStandAndWaitsThere() {
        assertEquals(Step.STAND, PillarBed.step(false, false, false, false));
        assertEquals(Step.WAIT, PillarBed.step(false, false, true, false));
        // a bed left over from the last landing waits too, no clicks in the air
        assertEquals(Step.WAIT, PillarBed.step(false, false, true, true));
    }

    @Test
    public void landingPlacesThenClicksAndStillWalksIfItHasTo() {
        assertEquals(Step.STAND, PillarBed.step(false, true, false, false));
        assertEquals(Step.PLACE, PillarBed.step(false, true, true, false));
        assertEquals(Step.CLICK, PillarBed.step(false, true, true, true));
    }

    @Test
    public void satIsTheRingWhateverElseIsTrue() {
        assertEquals(Step.RING, PillarBed.step(true, false, true, true));
        assertEquals(Step.RING, PillarBed.step(true, true, false, false));
    }

    @Test
    public void placesOnlyFacingEastEveryOtherTick() {
        assertTrue(PillarBed.placeNow(Direction.EAST, 100));
        assertFalse(PillarBed.placeNow(Direction.EAST, 101));
        assertFalse(PillarBed.placeNow(Direction.NORTH, 100));
        assertFalse(PillarBed.placeNow(Direction.WEST, 100));
    }

    @Test
    public void onlyAnEastFootIsTheRightWay() {
        assertTrue(PillarBed.rightWay(Direction.EAST, true));
        for (Direction other : Direction.values()) {
            if (other != Direction.EAST) {
                assertFalse(other.name(), PillarBed.rightWay(other, true));
            }
        }
        // the head half on the pillar means the foot is hanging off it, whichever way that reads
        assertFalse(PillarBed.rightWay(Direction.EAST, false));
    }

    @Test
    public void clicksAreSpacedPastTheInvulnerableWindow() {
        assertTrue(PillarBed.clickNow(true, 5, 100, 89));
        assertFalse(PillarBed.clickNow(true, 5, 100, 90));
        assertTrue(PillarBed.clickNow(true, 5, 100, Long.MIN_VALUE / 2));
    }

    @Test
    public void clicksWantTheHeadCloseAndOurFeetDown() {
        assertTrue(PillarBed.clickNow(true, 5.29, 100, 0));
        assertFalse(PillarBed.clickNow(true, 5.3, 100, 0));
        assertFalse(PillarBed.clickNow(false, 5, 100, 0));
    }
}
