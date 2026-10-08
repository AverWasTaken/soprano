package adris.altoclef.tasks.construction;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;

import java.util.Objects;

// GoalNear(pos, 1) counts the square on top of the block as arrived, and for a block that is
// dangerous to break from above that is exactly the one square we are trying to get off of.
// this is GoalNear minus the whole column above the target: sides (and diagonals) at any of
// y-1..y+1, plus the shaft two below for digging up. the pather can still cross the top, it
// just doesn't get to stop there
public class GoalMineFromSide implements Goal, IGoalRenderPos {

    private final int x;
    private final int y;
    private final int z;
    // digging up from the shaft two below is out when there is a column of sand or gravel (or a stalactite) hanging on the
    // target: the whole thing comes down the shaft and onto us
    private final boolean allowBelow;

    public GoalMineFromSide(BlockPos target) {
        this(target, true);
    }

    public GoalMineFromSide(BlockPos target, boolean allowBelow) {
        this.x = target.getX();
        this.y = target.getY();
        this.z = target.getZ();
        this.allowBelow = allowBelow;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int dx = x - this.x;
        int dy = y - this.y;
        int dz = z - this.z;
        if (dx == 0 && dz == 0) {
            // below only. y-1 would put the ore where our head is, so really it's y-2 or lower
            return allowBelow && dy == -2;
        }
        // up to one sideways step plus a diagonal, one up or down. this is GoalNear range sqrt(2)
        return dy >= -1 && dy <= 1 && dx * dx + dz * dz <= 2;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // same as GoalNear. a hair optimistic about the excluded column, a* doesn't mind
        return GoalBlock.calculate(x - this.x, y - this.y, z - this.z);
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GoalMineFromSide that = (GoalMineFromSide) o;
        return x == that.x && y == that.y && z == that.z && allowBelow == that.allowBelow;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, allowBelow);
    }

    @Override
    public String toString() {
        return "GoalMineFromSide{x=" + x + ", y=" + y + ", z=" + z + (allowBelow ? "" : ", sides only") + "}";
    }
}
