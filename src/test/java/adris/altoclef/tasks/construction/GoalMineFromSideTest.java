package adris.altoclef.tasks.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the goal that stops the bot from standing on the ore it is trying to break from the side
public class GoalMineFromSideTest {

    private static final BlockPos ORE = new BlockPos(260, 67, 16);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static boolean in(GoalMineFromSide goal, int dx, int dy, int dz) {
        return goal.isInGoal(ORE.getX() + dx, ORE.getY() + dy, ORE.getZ() + dz);
    }

    @Test
    public void standingOnTopDoesNotCount() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        // GoalNear(pos, 1) says yes to the first one, which is the whole bug
        assertFalse(in(goal, 0, 1, 0));
        assertFalse(in(goal, 0, 2, 0));
        assertFalse(in(goal, 0, 0, 0));
        assertFalse(in(goal, 0, 5, 0));
    }

    @Test
    public void sidesAtEveryLevelCount() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] s : sides) {
            for (int dy = -1; dy <= 1; ++dy) {
                assertTrue("side " + s[0] + "," + dy + "," + s[1], in(goal, s[0], dy, s[1]));
            }
        }
    }

    @Test
    public void belowItCounts() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        assertTrue(in(goal, 0, -2, 0));
    }

    @Test
    public void farAwayDoesNotCount() {
        GoalMineFromSide goal = new GoalMineFromSide(ORE);
        assertFalse(in(goal, 2, 0, 0));
        assertFalse(in(goal, 1, 2, 0));
        assertFalse(in(goal, 1, -2, 0));
        assertFalse(in(goal, 0, -3, 0));
    }

    @Test
    public void equalsGoesByTarget() {
        assertEquals(new GoalMineFromSide(ORE), new GoalMineFromSide(ORE.immutable()));
        assertFalse(new GoalMineFromSide(ORE).equals(new GoalMineFromSide(ORE.above())));
    }
}
