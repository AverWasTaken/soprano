package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.LavaExit;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class EscapeFromLavaTask extends CustomBaritoneGoalTask {

    private final float _strength;

    public EscapeFromLavaTask(float strength) {
        _strength = strength;
    }

    public EscapeFromLavaTask() {
        this(100);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getFoodChain().shouldStop(true);
        mod.getBehaviour().push();
        mod.getClientBaritone().getExploreProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        boolean inLava = mod.getPlayer().isInLava();
        _outOfLava = inLava ? 0 : LAVA_GRACE_TICKS;
        _lavaAllowed = lavaPathingAllowed(inLava, _outOfLava);
        mod.getBehaviour().allowSwimThroughLava(_lavaAllowed);
        // Encourage placing of all blocks!
        mod.getBehaviour().setBlockPlacePenalty(0);
        mod.getBehaviour().setBlockBreakAdditionalPenalty(0); // Normally 2
        // do NOT ever wander
        _checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);
    }

    // swimming through lava is how you get out of a lake you're in. this task also runs when we're only on fire, and then a
    // path through the lava is a path back into it: with this on the planner lets lava be walked through. so it's only on while
    // we're really in lava, plus a few ticks for the surface flicker at the shore. a flip rebuilds PrecomputedData, which
    // is fine at a couple of flips per escape
    private static final int LAVA_GRACE_TICKS = 10;
    private int _outOfLava;
    private boolean _lavaAllowed;

    static boolean lavaPathingAllowed(boolean inLava, int ticksOutOfLava) {
        return inLava || ticksOutOfLava < LAVA_GRACE_TICKS;
    }

    private void updateLavaRule(AltoClef mod) {
        boolean inLava = mod.getPlayer().isInLava();
        _outOfLava = inLava ? 0 : Math.min(_outOfLava + 1, LAVA_GRACE_TICKS);
        boolean allow = lavaPathingAllowed(inLava, _outOfLava);
        if (allow == _lavaAllowed) {
            return;
        }
        _lavaAllowed = allow;
        mod.getBehaviour().allowSwimThroughLava(allow);
        if (!allow && mod.getClientBaritone().getPathingBehavior().isPathing()) {
            // the path we're on was planned with lava allowed, the next one won't be
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        updateLavaRule(mod);
        // Sprint through lava + jump, it's faster
        if (mod.getPlayer().isInLava() || mod.getWorld().getBlockState(mod.getPlayer().blockPosition().below()).getBlock() == Blocks.LAVA) {
            mod.getInputControls().hold(Input.JUMP);
            faceTheShore(mod);
            mod.getInputControls().hold(Input.SPRINT);
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            if (hopToNearExit(mod)) {
                setDebugState("Hopping out of the lava.");
                return null;
            }
        } else {
            _hopTicks = 0;
        }
        return super.onTick(mod);
    }

    // a shore one or two cells away is faster jumped at than planned: a path through lava is a crawl however short, and
    // the hop is the same crawl with no turns in it. baritone owns the inputs while it has a path (PlayerMovementInput
    // reads its forced state and drops ours), so it has to let go first. lava is ~25 ticks a block, so the far end of a two
    // cell reach needs about 30 ticks to touch the wall, and a hop that still hasn't got us out by 50 hands back to baritone
    private static final int HOP_GIVE_UP_TICKS = 50;
    private int _hopTicks;

    private boolean hopToNearExit(AltoClef mod) {
        if (_hopTicks >= HOP_GIVE_UP_TICKS || !mod.getPlayer().isInLava()) return false;
        BlockPos at = mod.getPlayer().blockPosition();
        LavaExit.Cell exit = LavaExit.nearest(at.getX(), at.getY(), at.getZ(), ItemPickupRules.terrain(mod.getWorld()), ItemPickupRules.lava(mod.getWorld()));
        if (!LavaExit.withinHop(exit, at.getX(), at.getY(), at.getZ())) return false;
        _hopTicks++;
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        float yaw = LookHelper.getLookRotation(mod, new Vec3(exit.x() + 0.5, exit.y() + 0.5, exit.z() + 0.5)).getYaw();
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getXRot());
        return true;
    }

    // baritone steers while it has a path. with none (the first ticks, or a search that failed) holding forward swims
    // wherever we happened to face, which was usually deeper into the lake, so turn to the closest dry cell first. nothing
    // standable in range = a blind swim is all that is left
    private void faceTheShore(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) return;
        BlockPos at = mod.getPlayer().blockPosition();
        LavaExit.Cell exit = LavaExit.nearest(at.getX(), at.getY(), at.getZ(), ItemPickupRules.terrain(mod.getWorld()), ItemPickupRules.lava(mod.getWorld()));
        if (exit == null) return;
        float yaw = LookHelper.getLookRotation(mod, new Vec3(exit.x() + 0.5, exit.y() + 0.5, exit.z() + 0.5)).getYaw();
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getXRot());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getFoodChain().shouldStop(false);
        mod.getBehaviour().pop();
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.MOVE_FORWARD);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new EscapeFromLavaGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeFromLavaTask task && task._strength == _strength;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return !mod.getPlayer().isInLava() && !mod.getPlayer().isOnFire();
    }

    @Override
    protected String toHudString() {
        return "Getting out of the lava";
    }

    @Override
    protected String toDebugString() {
        return "Escaping lava";
    }

    // the air right over a lake is dry and has no lava beside it, and it is no place to be: the path to it is a one cell pillar
    // we can never finish, the lava just bobs us under the block we want to place. only a cell with something to stand on counts
    static boolean isSafeCell(boolean lava, boolean lavaAdjacent, boolean hasFloor) {
        return !lava && !lavaAdjacent && hasFloor;
    }

    private class EscapeFromLavaGoal implements Goal {

        private static boolean isLava(int x, int y, int z) {
            if (Minecraft.getInstance().level == null) return false;
            return MovementHelper.isLava(Minecraft.getInstance().level.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean isLavaAdjacent(int x, int y, int z) {
            return isLava(x + 1, y, z) || isLava(x - 1, y, z) || isLava(x, y, z + 1) || isLava(x, y, z - 1)
                    || isLava(x + 1, y, z - 1) || isLava(x + 1, y, z + 1) || isLava(x - 1, y, z - 1)
                    || isLava(x - 1, y, z + 1);
        }

        private static boolean isWater(int x, int y, int z) {
            if (Minecraft.getInstance().level == null) return false;
            return MovementHelper.isWater(Minecraft.getInstance().level.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean hasFloor(int x, int y, int z) {
            var level = Minecraft.getInstance().level;
            if (level == null) return true;
            BlockPos below = new BlockPos(x, y - 1, z);
            var state = level.getBlockState(below);
            return state.getFluidState().isEmpty() && state.isFaceSturdy(level, below, Direction.UP);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return isSafeCell(isLava(x, y, z), isLavaAdjacent(x, y, z), hasFloor(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isLava(x, y, z)) {
                return _strength;
            } else if (isLavaAdjacent(x, y, z)) {
                return _strength * 0.5f;
            }
            if (isWater(x, y, z)) {
                return -100;
            }
            return 0;
        }
    }
}
