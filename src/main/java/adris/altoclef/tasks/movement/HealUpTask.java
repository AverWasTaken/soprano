package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LavaShore;
import baritone.pathing.movement.CliffAvoidance;
import net.minecraft.core.BlockPos;

// the heal-up hold's wheel (RecoverHold): stand still and let the food chain and regen do their thing. never finishes,
// the machine says when it is over. the whole point is that the user task does not get to walk us back to the skeletons
public class HealUpTask extends Task {

    // one step, five seconds to make it. same edge test as the fight's rim step (LavaShore), no distance of our own
    private static final int STEP_TICKS = 100;

    // the hold is a final instance in three places, so onStart is the reset. a hold that comes back after the lava escape
    // chain took over goes through onStart again, which is also why it gets a fresh step
    private int stepTicks;
    private BlockPos stepCell;

    @Override
    protected void onStart(AltoClef mod) {
        stepTicks = 0;
        stepCell = null;
        stopWalking(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Task step = stepOffEdge(mod);
        // our own step is a path: cancelling it every tick would be the hold fighting itself
        if (step != null) return step;
        // the user task's path can still be going when we take over (or its search about to land), and a stand still that
        // walks is not one
        var pathing = mod.getClientBaritone().getPathingBehavior();
        if (pathing.isPathing() || pathing.getInProgress().isPresent()) stopWalking(mod);
        return null;
    }

    // standing on a lava rim to heal is how a magma cube bite becomes a death, so one bounded step off it first
    private Task stepOffEdge(AltoClef mod) {
        if (stepTicks >= STEP_TICKS) return null;
        var us = mod.getPlayer();
        BlockPos here = us.blockPosition();
        if (stepCell == null) {
            int[] s = LavaShore.edgeStep(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ());
            if (s != null) stepCell = here.offset(s[0], 0, s[1]);
        }
        // in the block is not off the rim yet: the cell's near half can still be inside the edge radius
        boolean arrived = here.equals(stepCell) && !LavaShore.edgeNear(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ());
        if (!wantsStep(stepCell != null, arrived, stepTicks)) {
            // fine where we are, nowhere better, or there: done for this hold
            stepTicks = STEP_TICKS;
            return null;
        }
        stepTicks++;
        return new GetToBlockTask(stepCell);
    }

    // the soft cancel: a movement that is not safe to stop (a fall, a bucket) finishes first, same as everyone else
    private static void stopWalking(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().cancelEverything();
    }

    // step while there is a cell to go to, we are not in it yet, and the budget is not spent
    static boolean wantsStep(boolean haveCell, boolean arrived, int ticksUsed) {
        return haveCell && !arrived && ticksUsed < STEP_TICKS;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof HealUpTask;
    }

    @Override
    protected String toHudString() {
        return "Healing up before going back in";
    }

    @Override
    protected String toDebugString() {
        return "Healing up before going back in";
    }
}
