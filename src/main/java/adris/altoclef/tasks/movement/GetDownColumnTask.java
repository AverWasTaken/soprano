package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalColumnDown;
import baritone.api.pathing.goals.Goal;

// dig down a fixed xz column to floorY. GetToYTask has no column, so it digs down wherever baritone fancies
public class GetDownColumnTask extends CustomBaritoneGoalTask {
    private final int x;
    private final int z;
    private final int floorY;

    public GetDownColumnTask(int x, int z, int floorY) {
        this.x = x;
        this.z = z;
        this.floorY = floorY;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalColumnDown(x, z, floorY);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetDownColumnTask t && t.x == x && t.z == z && t.floorY == floorY;
    }

    @Override
    protected String toDebugString() {
        return "Digging down at " + x + ", " + z + " to y=" + floorY;
    }

    @Override
    protected String toHudString() {
        return "Digging down to y " + floorY;
    }
}
