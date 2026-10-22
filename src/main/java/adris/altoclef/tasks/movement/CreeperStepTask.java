package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CreeperEscape;
import adris.altoclef.util.helpers.CreeperStep;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.input.Input;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

// out of one lit creeper's blast, then stand there. two flavours (see CreeperStep): the bait backs off to just past the
// blast and waits for the pop, the defuse goes eight blocks through baritone's own planner (so no walking off ledges or into
// lava) so it stops swelling. never finishes by itself: CreeperStep says when the fuse is over and the chain lets go.
// the fuse is 30 ticks and the first path can take a third of that to show up, so we sprint away ourselves first
public class CreeperStepTask extends CustomBaritoneGoalTask {

    // a goal we are already standing in comes back from baritone at once, asking again every tick is a search per tick
    // for nothing. same idea as CommittedRunTask.REISSUE_GAP_TICKS, just shorter, the whole step is 2.5 s
    static final long REISSUE_GAP_TICKS = 10;

    // how long we steer ourselves before giving up on the path showing up. 10 ticks of sprinting from a standstill is two
    // and a bit blocks. baritone searches from the block we were in, and walking out of that block can cancel the search
    // or orphan its result (PathingBehavior), so every extra tick of sprint is a tick the path may have to restart. it
    // restarts from the new block and short searches finish in a tick or two, but it is why this is not 50
    static final long RAW_TICKS = 10;
    // the bait steers itself the whole way out unless the ground says no. 3 to 6.2 is a dozen and a bit ticks of sprinting from
    // a standstill (RAW_TICKS gets about 5.5 with the slide, short of the line), and baritone is only asked once this runs out,
    // so the orphaned search from RAW_TICKS is not a thing here. still well inside the fuse
    static final long BAIT_RAW_TICKS = 25;

    // blocks that hurt to stand on or in, or hold you there, and that have no collision to tell us so
    private static final Set<Block> DANGEROUS = Set.of(
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.COBWEB, Blocks.WITHER_ROSE);

    private final int _creeperId;
    private final boolean _bait;
    // the bait is past the line and has let go of everything
    private boolean _standing;
    private long _lastIssue = Long.MIN_VALUE / 2;
    private long _startTick;
    // the raw phase is over for good once the path runs or the ticks are up, it never comes back mid step
    private boolean _raw;
    private boolean _rawLogged;
    private boolean _issued;
    private boolean _failLogged;

    public CreeperStepTask(int creeperId) {
        this(creeperId, false);
    }

    public CreeperStepTask(int creeperId, boolean bait) {
        // no wander: standing at the goal is the point
        super(false);
        _creeperId = creeperId;
        _bait = bait;
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // step NOW, not after the user's path finishes its segment
        cancelPath(mod);
        Entity creeper = mod.getWorld().getEntity(_creeperId);
        if (_bait && creeper instanceof Creeper) return new GoalBaitRing(creeper.getX(), creeper.getY(), creeper.getZ());
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
        _standing = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // before the baritone bit, so the first tick already moves us
        if (_bait) {
            if (bait(mod)) return null;
        } else {
            sprintAway(mod);
        }
        return super.onTick(mod);
    }

    // true = this tick is ours (sprinting out, or standing still for the pop), false = baritone walks us to the ring.
    // live distance every tick and not a block goal: the line is a fraction of a block wide and the slide is part of the plan
    private boolean bait(AltoClef mod) {
        Entity creeper = mod.getWorld().getEntity(_creeperId);
        if (creeper == null) {
            // popped or gone, the chain lets go next tick
            releaseRaw(mod);
            return true;
        }
        long now = mod.getWorld().getGameTime();
        if (CreeperStep.baitStands(mod.getPlayer().distanceTo(creeper), _standing)) {
            if (!_standing) {
                _standing = true;
                _raw = false;
                releaseRaw(mod);
            }
            // any path (ours to the ring, or the user's that was not safe to drop at the start) walks us on, so it goes.
            // past 7 counts too: walking back at a lit creeper is not the move, it deflates and the step ends
            if (mod.getClientBaritone().getPathingBehavior().isPathing() || mod.getClientBaritone().getCustomGoalProcess().isActive()) {
                cancelPath(mod);
            }
            LookHelper.lookAt(mod, creeper.getEyePosition());
            return true;
        }
        if (_standing) {
            // it deflated, walked in and lit again inside the same step: out again, sprint first like at the start, and the
            // ring gets drawn around where it stands now
            _standing = false;
            _raw = true;
            _startTick = now;
            _cachedGoal = null;
            cancelPath(mod);
        }
        if (_raw && now - _startTick >= BAIT_RAW_TICKS) endRaw(mod);
        if (!_raw) return false;
        if (steer(mod, creeper)) return true;
        // nowhere safe to sprint: the ring path has it from here
        endRaw(mod);
        return false;
    }

    // the keys are ours until the path takes over (it brings its own inputs) or the ticks run out. every tick looks at the
    // ground again, so a ledge or a wall that shows up mid sprint turns us or stops us
    private void sprintAway(AltoClef mod) {
        if (!_raw) return;
        // a path running before the goal went out is the user's old one that was not safe to cancel, not ours to wait for
        if ((_issued && mod.getClientBaritone().getPathingBehavior().isPathing())
                || mod.getWorld().getGameTime() - _startTick >= RAW_TICKS) {
            endRaw(mod);
            return;
        }
        Entity creeper = mod.getWorld().getEntity(_creeperId);
        if (creeper == null) {
            endRaw(mod);
            return;
        }
        // no safe heading: let go, standing still and waiting on the path is better than the wrong way
        steer(mod, creeper);
    }

    // one tick of sprinting away by hand. false = no heading is safe (the keys are let go)
    private boolean steer(AltoClef mod, Entity creeper) {
        LocalPlayer player = mod.getPlayer();
        // airborne means the cells under us are not the ones we will land on, keep what we were doing
        if (!player.onGround() && !player.isInWater()) {
            mod.getInputControls().release(Input.JUMP);
            return true;
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
            releaseRaw(mod);
            return false;
        }
        mod.getInputControls().forceLook(heading.yaw(), player.getXRot());
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        mod.getInputControls().hold(Input.SPRINT);
        if (heading.jump()) {
            mod.getInputControls().hold(Input.JUMP);
        } else {
            mod.getInputControls().release(Input.JUMP);
        }
        return true;
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
    public static CreeperEscape.Cells cells(Level level) {
        return (x, y, z) -> {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA) || DANGEROUS.contains(state.getBlock())) {
                return CreeperEscape.Kind.DANGER;
            }
            if (state.getFluidState().is(FluidTags.WATER)) return CreeperEscape.Kind.WATER;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) return CreeperEscape.Kind.OPEN;
            double top = shape.max(Direction.Axis.Y);
            // carpet and snow layers are walked over without a jump, fences are taller than one
            if (top <= 0.25) return CreeperEscape.Kind.OPEN;
            return top > 1.0 ? CreeperEscape.Kind.WALL : CreeperEscape.Kind.SOLID;
        };
    }

    @Override
    protected boolean mayIssueGoal(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now >= _lastIssue && now - _lastIssue < REISSUE_GAP_TICKS) return false;
        _lastIssue = now;
        // asked again while not standing in the goal = baritone dropped it without getting there (an empty search, or ours
        // cancelled by the sprint, it can't tell us which). said once, it is a diagnosis and not a heartbeat
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
        return other instanceof CreeperStepTask task && task._creeperId == _creeperId && task._bait == _bait;
    }

    @Override
    protected String toHudString() {
        return _bait ? "Letting a creeper pop out of reach" : "Stepping out of a creeper blast";
    }

    @Override
    protected String toDebugString() {
        return _bait ? "Wait just past the blast of creeper " + _creeperId
                : "Step " + (int) CreeperStep.SAFE + " blocks from creeper " + _creeperId;
    }

    // a cell whose middle is in CreeperStep's bait ring around the creeper. it stands still while it swells, so where it stood
    // when the goal was drawn is where it is
    private static final class GoalBaitRing implements Goal {
        private final double _x;
        private final double _y;
        private final double _z;

        GoalBaitRing(double x, double y, double z) {
            _x = x;
            _y = y;
            _z = z;
        }

        private double distance(int x, int y, int z) {
            double dx = x + 0.5 - _x, dy = y - _y, dz = z + 0.5 - _z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return CreeperStep.inBaitRing(distance(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double d = distance(x, y, z);
            if (CreeperStep.inBaitRing(d)) return 0;
            double gap = d < CreeperStep.BAIT_RING_MIN ? CreeperStep.BAIT_RING_MIN - d : d - CreeperStep.BAIT_RING_MAX;
            return gap * ActionCosts.WALK_ONE_BLOCK_COST;
        }

        @Override
        public String toString() {
            return String.format("GoalBaitRing{x=%.1f,y=%.1f,z=%.1f}", _x, _y, _z);
        }
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
