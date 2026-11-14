package adris.altoclef.tasks.speedrun;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.Test;

public class DragonBreathTrackerTest {

    @Test
    public void nearMeansLessThanThatManyBlocksFromAnyCell() {
        List<BlockPos> cells = List.of(new BlockPos(0, 60, 0), new BlockPos(1, 60, 0));
        assertTrue(DragonBreathTracker.anyWithin(cells, new BlockPos(1, 60, 0), 3));
        assertTrue(DragonBreathTracker.anyWithin(cells, new BlockPos(3, 60, 0), 3));
        assertFalse(DragonBreathTracker.anyWithin(cells, new BlockPos(4, 60, 0), 3));
        assertFalse(DragonBreathTracker.anyWithin(List.of(), new BlockPos(0, 60, 0), 3));
    }

    @Test
    public void runLooksAgainWhenTheCloudsMoved() {
        Set<BlockPos> old = Set.of(new BlockPos(-1, 61, 0), new BlockPos(0, 61, 0));
        Set<BlockPos> moved = Set.of(new BlockPos(3, 60, 11), new BlockPos(4, 60, 11));
        // a resumed or long running dodge gets the clouds of now, not the ones it started on
        assertTrue(DragonBreathTracker.wantsNewGoal(old, moved, DragonBreathTracker.REGOAL_TICKS));
        assertTrue(DragonBreathTracker.wantsNewGoal(old, moved, 500));
        // but not every tick
        assertFalse(DragonBreathTracker.wantsNewGoal(old, moved, DragonBreathTracker.REGOAL_TICKS - 1));
        // a new world's clock went backwards, that is long enough ago
        assertTrue(DragonBreathTracker.wantsNewGoal(old, moved, -40));
    }

    @Test
    public void runKeepsItsGoalWhenNothingChangedOrNothingIsLeft() {
        Set<BlockPos> cells = Set.of(new BlockPos(-1, 61, 0), new BlockPos(0, 61, 0));
        assertFalse(DragonBreathTracker.wantsNewGoal(cells, Set.copyOf(cells), 500));
        // GoalRunAway throws on an empty list, the old goal is the best we have
        assertFalse(DragonBreathTracker.wantsNewGoal(cells, Set.of(), 500));
    }
}
