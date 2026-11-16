package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.PortalExit;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;

// out of a portal we never meant to stand in, before its four seconds are up. the miners have their own "getting out" bit, but
// it only runs while baritone is idle and the next task grabs the wheel a tick later, which is plenty of time to get teleported.
// this one holds the wheel and nothing else: GamerTask hands it out every tick we stand in a portal while the keep-out is on
public class StepOutOfPortalTask extends Task {

    @Override
    protected void onStart(AltoClef mod) {
        stopPathing(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // a path somebody started from inside the portal would walk us right back in
        stopPathing(mod);
        PortalExit.step(mod);
        return null;
    }

    private static void stopPathing(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SNEAK);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return !WorldHelper.isStandingInPortal(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof StepOutOfPortalTask;
    }

    @Override
    protected String toDebugString() {
        return "Stepping out of the portal";
    }

    @Override
    protected String toHudString() {
        return "Stepping out of the portal";
    }
}
