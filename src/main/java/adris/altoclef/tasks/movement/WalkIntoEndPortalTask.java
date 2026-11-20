package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// walk straight at the middle of an end portal by hand. same idea as StepIntoPortalTask minus the jump: falling in is the
// whole point, and a jump off the lip could carry us over the 3x3 onto the far frame
public class WalkIntoEndPortalTask extends Task {

    private final BlockPos middle;

    public WalkIntoEndPortalTask(BlockPos middle) {
        this.middle = middle;
    }

    public BlockPos middle() {
        return middle;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        float yaw = LookHelper.getLookRotation(mod, new Vec3(middle.getX() + 0.5, middle.getY() + 0.5, middle.getZ() + 0.5)).getYaw();
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getXRot());
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof WalkIntoEndPortalTask t && t.middle.equals(middle);
    }

    @Override
    protected String toHudString() {
        return "Stepping into the End portal";
    }

    @Override
    protected String toDebugString() {
        return "Walking into the end portal at " + middle.toShortString();
    }
}
