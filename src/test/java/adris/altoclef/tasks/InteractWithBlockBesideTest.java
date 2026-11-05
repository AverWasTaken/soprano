package adris.altoclef.tasks;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// with the cell above the target protected, the walk ends next to the target on any of its four sides, not north alone
public class InteractWithBlockBesideTest {

    private static final BlockPos TARGET = new BlockPos(120, 64, -40);

    private static boolean in(Goal goal, int dx, int dy, int dz) {
        return goal.isInGoal(TARGET.getX() + dx, TARGET.getY() + dy, TARGET.getZ() + dz);
    }

    @Test
    public void everySideCounts() {
        Goal goal = InteractWithBlockTask.goalBesideTarget(TARGET);
        assertTrue(in(goal, 0, 0, -1));
        // north was the only one before
        assertTrue(in(goal, 0, 0, 1));
        assertTrue(in(goal, 1, 0, 0));
        assertTrue(in(goal, -1, 0, 0));
        // a step lower beside it still reaches, as GoalTwoBlocks always allowed
        assertTrue(in(goal, 0, -1, 1));
    }

    @Test
    public void notOnTheTargetNorTheProtectedCellAbove() {
        Goal goal = InteractWithBlockTask.goalBesideTarget(TARGET);
        assertFalse(in(goal, 0, 0, 0));
        assertFalse(in(goal, 0, 1, 0));
        assertFalse(in(goal, 1, 0, 1));
        assertFalse(in(goal, 0, 0, 2));
    }
}
