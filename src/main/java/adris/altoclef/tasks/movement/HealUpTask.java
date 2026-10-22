package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;

// the heal-up hold's wheel (RecoverHold): stand still and let the food chain and regen do their thing. never finishes,
// the machine says when it is over. the whole point is that the user task does not get to walk us back to the skeletons
public class HealUpTask extends Task {

    @Override
    protected void onStart(AltoClef mod) {
        stopWalking(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // the user task's path can still be going when we take over (or its search about to land), and a stand still that
        // walks is not one
        var pathing = mod.getClientBaritone().getPathingBehavior();
        if (pathing.isPathing() || pathing.getInProgress().isPresent()) stopWalking(mod);
        return null;
    }

    // the soft cancel: a movement that is not safe to stop (a fall, a bucket) finishes first, same as everyone else
    private static void stopWalking(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().cancelEverything();
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
