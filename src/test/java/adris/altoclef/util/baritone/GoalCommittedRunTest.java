package adris.altoclef.util.baritone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// the goal of a committed run with nobody chasing: the only thing left is "far from where it started". the crowd half is
// CrowdRepulsion's and tested there
public class GoalCommittedRunTest {

    private static GoalCommittedRun quietRun() {
        GoalCommittedRun goal = new GoalCommittedRun(100, 200, 24, 14, List::of);
        goal.refresh();
        return goal;
    }

    @Test
    public void notDoneWhereItStarted() {
        assertFalse(quietRun().isInGoal(100, 64, 200));
    }

    @Test
    public void notDoneAtTwentyThreeBlocks() {
        assertFalse(quietRun().isInGoal(100, 64, 200 + 23));
    }

    @Test
    public void doneAtTwentyFourBlocksInAnyDirection() {
        GoalCommittedRun goal = quietRun();
        assertTrue(goal.isInGoal(100, 64, 200 - 25));
        assertTrue(goal.isInGoal(100 + 18, 64, 200 + 18));
    }

    // block centers, so an origin on a block center puts the line exactly on a whole number
    @Test
    public void exactlyTwentyFourIsDoneAndTwentyThreeIsNot() {
        GoalCommittedRun goal = new GoalCommittedRun(100.5, 200.5, 24, 14, List::of);
        goal.refresh();
        assertTrue(goal.isInGoal(100, 64, 224));
        assertFalse(goal.isInGoal(100, 64, 223));
        assertTrue(goal.isInGoal(124, 64, 200));
        assertFalse(goal.isInGoal(123, 64, 200));
    }

    // up a hill is not further away, the run is flat
    @Test
    public void heightDoesNotCount() {
        assertFalse(quietRun().isInGoal(100, 120, 200 + 20));
    }

    @Test
    public void everyBlockAwayFromTheOriginHelps() {
        GoalCommittedRun goal = quietRun();
        double last = Double.MAX_VALUE;
        for (int z = 200; z <= 224; z += 6) {
            double h = goal.heuristic(100, 64, z);
            assertTrue("z " + z, h < last);
            last = h;
        }
        assertEquals(0, goal.heuristic(100, 64, 225), 0.01);
    }

    @Test
    public void theSearchIsPulledToZeroExactlyWhereTheGoalSaysYes() {
        GoalCommittedRun goal = quietRun();
        assertEquals(0, goal.heuristic(100, 64, 234), 0);
        assertTrue(goal.isInGoal(100, 64, 234));
        assertTrue(goal.heuristic(100, 64, 210) > 0);
    }
}
