package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IdleRimStepTest {
    // floor under y = 0 everywhere, except a lake at y = -2 in the columns x >= 1: the lip of a nether lake, one or two down
    private static final Cells LIP = (x, y, z) -> {
        if (x >= 1) return y == -2 ? Cell.LAVA : y > -2 ? Cell.OPEN : Cell.SOLID;
        return y >= 0 ? Cell.OPEN : Cell.SOLID;
    };
    private static final Cells FLAT = (x, y, z) -> y >= 0 ? Cell.OPEN : Cell.SOLID;

    @Test
    public void standingStillOnTheLipWithAHoglinCloseSteps() {
        assertTrue(IdleRimStep.gate(true, true, false, false, IdleRimStep.STILL_TICKS, 6));
        assertNotNull(IdleRimStep.why(LIP, 0.5, 0, 0.5));
    }

    @Test
    public void theGateSaysNoToEachMissingPiece() {
        int still = IdleRimStep.STILL_TICKS;
        // overworld
        assertFalse(IdleRimStep.gate(false, true, false, false, still, 6));
        // a fight or a run is already on
        assertFalse(IdleRimStep.gate(true, false, false, false, still, 6));
        // a path is walking us somewhere
        assertFalse(IdleRimStep.gate(true, true, true, false, still, 6));
        // swimming already, the escape has it
        assertFalse(IdleRimStep.gate(true, true, false, true, still, 6));
        // moving
        assertFalse(IdleRimStep.gate(true, true, false, false, still - 1, 6));
        // nobody close
        assertFalse(IdleRimStep.gate(true, true, false, false, still, IdleRimStep.FOE_RANGE + 0.1));
        assertFalse(IdleRimStep.gate(true, true, false, false, still, Double.POSITIVE_INFINITY));
    }

    @Test
    public void inlandIsNoLip() {
        assertNull(IdleRimStep.why(FLAT, 0.5, 0, 0.5));
        assertNull(IdleRimStep.why(LIP, -5.5, 0, 0.5));
    }

    @Test
    public void theHoldEndsWhenTheFoeLeavesThenCoolsDown() {
        IdleRimStep step = new IdleRimStep();
        assertTrue(step.ready(100));
        step.start(100);
        assertFalse(step.ready(101));
        assertEquals(IdleRimStep.End.NONE, step.check(150, true, 5));
        assertEquals(IdleRimStep.End.FOE_GONE, step.check(160, true, IdleRimStep.FOE_RANGE + 1));
        assertFalse(step.holding());
        assertFalse(step.ready(160 + IdleRimStep.COOLDOWN_TICKS - 1));
        assertTrue(step.ready(160 + IdleRimStep.COOLDOWN_TICKS));
    }

    @Test
    public void theHoldIsCapped() {
        IdleRimStep step = new IdleRimStep();
        step.start(0);
        assertEquals(IdleRimStep.End.NONE, step.check(IdleRimStep.HOLD_CAP_TICKS - 1, true, 3));
        assertEquals(IdleRimStep.End.CAPPED, step.check(IdleRimStep.HOLD_CAP_TICKS, true, 3));
        assertFalse(step.ready(IdleRimStep.HOLD_CAP_TICKS + 1));
        assertTrue(step.ready(IdleRimStep.HOLD_CAP_TICKS * 2L));
    }

    @Test
    public void aFightTakingOverSkipsTheCooldown() {
        IdleRimStep step = new IdleRimStep();
        step.start(0);
        assertEquals(IdleRimStep.End.COMMITTED, step.check(20, false, 2));
        assertTrue(step.ready(21));
    }

    @Test
    public void nowhereToGoIsAskedAgainLater() {
        IdleRimStep step = new IdleRimStep();
        step.noCell(10);
        assertFalse(step.ready(10 + IdleRimStep.NO_CELL_RETRY_TICKS - 1));
        assertTrue(step.ready(10 + IdleRimStep.NO_CELL_RETRY_TICKS));
    }

    @Test
    public void theLipHasGroundToWalkTo() {
        // the same walk the brain asks for: off the lip, out of knock range of the lake
        int[] cell = LavaShore.groundCell(LIP, 0.5, 0, 0.5, 5.5, 0.5);
        assertNotNull(cell);
        assertTrue(cell[0] <= -3);
    }

    @Test
    public void arrivedGoesByTheColumn() {
        assertTrue(IdleRimStep.arrived(-2.5, 33.0, 4.5, -3, 33, 4));
        // soul sand: feet an eighth down, in the cell under the one we asked for
        assertTrue(IdleRimStep.arrived(-2.5, 32.875, 4.5, -3, 33, 4));
        assertFalse(IdleRimStep.arrived(-1.5, 33.0, 4.5, -3, 33, 4));
        assertFalse(IdleRimStep.arrived(-2.5, 30.0, 4.5, -3, 33, 4));
    }
}
