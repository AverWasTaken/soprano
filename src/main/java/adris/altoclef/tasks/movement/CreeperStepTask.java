package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CreeperEscape;
import adris.altoclef.util.helpers.CreeperStep;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// eight blocks away from one lit creeper, through baritone's own planner (so no walking off ledges or into lava),
// then stand there. never finishes by itself: CreeperStep says when the fuse is over and the chain lets go.
// the fuse is 30 ticks and the first path can take a third of that to show up, so until it does we just sprint away
public class CreeperStepTask extends CustomBaritoneGoalTask {

    // a goal we are already standing in comes back from baritone at once, asking again every tick is a search per tick
    // for nothing. same idea as CommittedRunTask.REISSUE_GAP_TICKS, just shorter, the whole step is 2.5 s
    static final long REISSUE_GAP_TICKS = 10;

    // how long we steer ourselves before giving up on the path showing up. 10 ticks of sprinting from a standstill is two
    // and a bit blocks, and a path that starts where we were still counts us as on it up to 3 blocks off (PathExecutor),
    // so any longer and the path we are waiting for throws itself away the moment it arrives
    static final long RAW_TICKS = 10;

    // blocks that hurt to stand on or in, or hold you there, and that have no collision to tell us so
    private static final Set<Block> DANGEROUS = Set.of(
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.COBWEB, Blocks.WITHER_ROSE);

    private final int _creeperId;
    private long _lastIssue = Long.MIN_VALUE / 2;
    private long _startTick;
    // the raw phase is over for good once the path runs or the ticks are up, it never comes back mid step
    private boolean _raw;
    private boolean _rawLogged;
    private boolean _issued;
    private boolean _failLogged;

    public CreeperStepTask(int creeperId) {
        // no wander: standing at the goal is the point
        super(false);
        _creeperId = creeperId;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // step NOW, not after the user's path finishes its segment
        cancelPath(mod);
        return new GoalAwayFromCreeper(mod, _creeperId);
    }

    // soft in the air, same as the run: a hard cancel mid fall drops the clutch or the bucket
    @Override
    protected void cancelPath(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (CommittedRunTask.mayCancelHard(player.onGround(), player.isInWater(), player.onClimbable())) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        } else {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        super.onStart(mod);
        _lastIssue = Long.MIN_VALUE / 2;
        _startTick = mod.getWorld().getGameTime();
        _raw = true;
        _rawLogged = false;
        _issued = false;
        _failLogged = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // before the baritone bit, so the first tick already moves us
        sprintAway(mod);
        return super.onTick(mod);
    }

    // the keys are ours until the path takes over (it brings its own inputs) or the ticks run out. every tick looks at the
    // ground again, so a ledge or a wall that shows up mid sprint turns us or stops us
    private void sprintAway(AltoClef mod) {
        if (!_raw) return;
        if (mod.getClientBaritone().getPathingBehavior().isPathing()
                || mod.getWorld().getGameTime() - _startTick >= RAW_TICKS) {
            endRaw(mod);
            return;
        }
        Entity creeper = mod.getWorld().getEntity(_creeperId);
        if (creeper == null) {
            endRaw(mod);
            return;
        }
        LocalPlayer player = mod.getPlayer();
        // airborne means the cells under us are not the ones we will land on, keep what we were doing
        if (!player.onGround() && !player.isInWater()) {
            mod.getInputControls().release(Input.JUMP);
            return;
        }
        CreeperEscape.Heading heading = CreeperEscape.choose(player.getX(), player.getY(), player.getZ(),
                creeper.getX(), creeper.getZ(), cells(mod.getWorld()));
        if (!_rawLogged) {
            _rawLogged = true;
            Debug.logInternal(heading == null
                    ? CombatLog.creeperNoHeading(WorldHelper.getCurrentDimension())
                    : CombatLog.creeperSprint(WorldHelper.getCurrentDimension(), heading.degrees()));
        }
        if (heading == null) {
            // nowhere safe to run: let go, standing still and waiting on the path is better than the wrong way
            releaseRaw(mod);
            return;
        }
        mod.getInputControls().forceLook(heading.yaw(), player.getXRot());
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        mod.getInputControls().hold(Input.SPRINT);
        if (heading.jump()) {
            mod.getInputControls().hold(Input.JUMP);
        } else {
            mod.getInputControls().release(Input.JUMP);
        }
    }

    private void endRaw(AltoClef mod) {
        _raw = false;
        releaseRaw(mod);
    }

    private static void releaseRaw(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _raw = false;
        releaseRaw(mod);
        super.onStop(mod, interruptTask);
    }

    // what a cell is for the heading chooser. hazards go by block, they mostly have no collision to go by
    private static CreeperEscape.Cells cells(Level level) {
        return (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA) || DANGEROUS.contains(state.getBlock())) {
                return CreeperEscape.Kind.DANGER;
            }
            if (state.getFluidState().is(FluidTags.WATER)) return CreeperEscape.Kind.WATER;
            return state.getCollisionShape(level, pos).isEmpty() ? CreeperEscape.Kind.OPEN : CreeperEscape.Kind.SOLID;
        };
    }

    @Override
    protected boolean mayIssueGoal(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now >= _lastIssue && now - _lastIssue < REISSUE_GAP_TICKS) return false;
        _lastIssue = now;
        // asked again while not standing in the goal = the search came back with nothing. said once, it is a diagnosis
        // and not a heartbeat
        if (!_issued) {
            _issued = true;
            Debug.logInternal(CombatLog.creeperPath(WorldHelper.getCurrentDimension(), false));
        } else if (!_failLogged && _cachedGoal != null && !_cachedGoal.isInGoal(mod.getPlayer().blockPosition())) {
            _failLogged = true;
            Debug.logInternal(CombatLog.creeperPath(WorldHelper.getCurrentDimension(), true));
        }
        return true;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CreeperStepTask task && task._creeperId == _creeperId;
    }

    @Override
    protected String toHudString() {
        return "Stepping out of a creeper blast";
    }

    @Override
    protected String toDebugString() {
        return "Step " + (int) CreeperStep.SAFE + " blocks from creeper " + _creeperId;
    }

    private static class GoalAwayFromCreeper extends GoalRunAwayFromEntities {
        private final int _id;

        GoalAwayFromCreeper(AltoClef mod, int id) {
            // 3d: a creeper on the ledge above is still a creeper next to us. the 10 is the penalty factor (how
            // hard the search sticks to straight away), not a distance
            super(mod, CreeperStep.SAFE, false, 10);
            _id = id;
        }

        @Override
        protected Optional<Entity> getEntities(AltoClef mod) {
            Entity e = mod.getWorld().getEntity(_id);
            return e instanceof Creeper ? Optional.of(e) : Optional.empty();
        }
    }
}
