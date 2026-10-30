package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.Test;

// the chunk hold after a dimension change and the step back into a portal that dropped us by lava
public class ArrivalRulesTest {

    @Test
    public void holdsUntilTheBoxLoadsOrTheCapHits() {
        assertTrue(ArrivalRules.hold(0, false));
        assertTrue(ArrivalRules.hold(ArrivalRules.HOLD_CAP_TICKS - 1, false));
        assertFalse(ArrivalRules.hold(5, true));
        assertFalse(ArrivalRules.hold(ArrivalRules.HOLD_CAP_TICKS, false));
    }

    @Test
    public void stepsBackOnlyInTheNetherSoonAfterArrivingWithAPortalClose() {
        assertTrue(ArrivalRules.stepBack(true, 20, true, false, true));
        assertFalse(ArrivalRules.stepBack(false, 20, true, false, true));
        assertFalse(ArrivalRules.stepBack(true, 20, true, false, false));
        assertFalse(ArrivalRules.stepBack(true, ArrivalRules.STEP_BACK_TICKS + 1, true, false, true));
        // dry and never started: nothing to back out of
        assertFalse(ArrivalRules.stepBack(true, 20, false, false, true));
    }

    @Test
    public void aStartedStepBackKeepsGoingWhileWeStandInThePortal() {
        assertTrue(ArrivalRules.stepBack(true, 100, false, true, true));
        // a start near the end of the window still gets its teleport wait
        assertTrue(ArrivalRules.stepBack(true, ArrivalRules.STEP_BACK_TICKS + 100, false, true, true));
        assertFalse(ArrivalRules.stepBack(true, ArrivalRules.STEP_BACK_LATCH_TICKS + 1, false, true, true));
    }

    @Test
    public void nearestPortalPicksTheClosestCellInReach() {
        BlockPos at = new BlockPos(0, 70, 0);
        BlockPos far = new BlockPos(2, 70, 2);
        BlockPos near = new BlockPos(1, 71, 0);
        assertEquals(near, ArrivalRules.nearestPortal(at, p -> p.equals(far) || p.equals(near)));
        assertNull(ArrivalRules.nearestPortal(at, p -> p.equals(new BlockPos(3, 70, 0))));
    }
}
