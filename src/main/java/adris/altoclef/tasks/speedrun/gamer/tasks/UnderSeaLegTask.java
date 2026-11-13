package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.NoPathScope;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;

// the under sea leg (SeaLeg) as a task, so the room search and the portal dig after an end death walk the same tunnel. the owner
// asks surfaced / arrived / failed before handing it out each tick and decides what each one means for it
public final class UnderSeaLegTask extends Task {
    private final GamerContext ctx;
    private final String who;
    private final SeaLeg leg;
    private final NoPathScope.Watch noPath = new NoPathScope.Watch();
    private Task goal;
    private int failX;
    private int failZ;
    private boolean failSaid;

    public UnderSeaLegTask(GamerContext ctx, String who, int tx, int ty, int tz) {
        this.ctx = ctx;
        this.who = who;
        this.leg = new SeaLeg(tx, ty, tz);
    }

    // same tunnel, new end (the ring turned up while we were on the way to the start column)
    public void target(int x, int y, int z) {
        if (leg.tx() == x && leg.ty() == y && leg.tz() == z) {
            return;
        }
        ctx.log(who + ": under the sea: the ring is at " + x + ", " + y + ", " + z + ", tunnelling there instead");
        leg.target(x, y, z);
        goal = null;
    }

    public boolean arrived(AltoClef mod) {
        return leg.arrived(mod.getPlayer().getX(), mod.getPlayer().getZ());
    }

    public boolean failed() {
        return leg.failed();
    }

    public int failX() {
        return failX;
    }

    public int failZ() {
        return failZ;
    }

    // back up out of the rock: in a boat, or feet over the sea floor of the column we're in. the leg's own goals never go up there
    // (a fourteen block goal is cheaper through the rock), so this is an interrupt, a fight or a relog. the owner sends us back
    // down the shaft, it's not the shore's fault
    public static boolean surfaced(AltoClef mod) {
        if (mod.getPlayer().isPassenger()) {
            return true;
        }
        BlockPos at = mod.getPlayer().blockPosition();
        return RoomRetarget.shoreWalk(StrongholdRoomSearchTask.probe(mod), at.getX(), at.getZ(), mod.getPlayer().getY());
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the old goal is from before whoever had the wheel, the leg checks if its waypoint still makes sense
        goal = null;
        noPath.disarm();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        var pathing = mod.getClientBaritone().getPathingBehavior();
        boolean exhausted = noPath.armed()
                && noPath.poll(pathing.calcFailures(), pathing.calcExhausted(), pathing.isPathing()) == NoPathScope.Verdict.EXHAUSTED;
        double px = mod.getPlayer().getX();
        double py = mod.getPlayer().getY();
        double pz = mod.getPlayer().getZ();
        SeaLeg.Step step = leg.tick(StrongholdRoomSearchTask.probe(mod), px, py, pz, mod.getWorld().getGameTime() / 20.0, exhausted);
        if (step.progressed()) {
            // a hundred and sixty blocks of iron pick tunnel is five minutes, the phase's stall clock is three
            ctx.progress("tunnelled under the sea");
        }
        if (step.kind() == SeaLeg.Kind.FAILED) {
            if (!failSaid) {
                failSaid = true;
                failX = (int) Math.floor(px);
                failZ = (int) Math.floor(pz);
                ctx.log(who + ": under the sea: the tunnel is blocked at " + failX + ", " + failZ + " (deeper and both sides tried)");
            }
            goal = null;
            return null;
        }
        if (step.kind() == SeaLeg.Kind.ARRIVED) {
            return null;
        }
        if (step.fresh() || goal == null) {
            int[] w = step.waypoint();
            if (step.retry() > 0 && step.fresh()) {
                ctx.log(who + ": under the sea: no way on from " + (int) px + ", " + (int) py + ", " + (int) pz + ", trying "
                        + (step.retry() == 1 ? "deeper" : "off to the side") + " (" + step.retry() + "/" + SeaLeg.MAX_RETRIES + ")");
            } else if (step.fresh()) {
                ctx.log(who + ": under the sea: tunnelling to " + w[0] + ", " + w[1] + ", " + w[2] + ", "
                        + Math.round(leg.left(px, pz)) + " left");
            }
            goal = new WaypointTask(new BlockPos(w[0], w[1], w[2]));
            noPath.arm(pathing.calcFailures(), pathing.calcExhausted());
        }
        setDebugState("Tunnelling under the sea, " + Math.round(leg.left(px, pz)) + " left");
        return goal;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        noPath.disarm();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return arrived(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        // retargeted in place, so the instance is the leg
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Tunnelling under the sea to " + leg.tx() + ", " + leg.ty() + ", " + leg.tz();
    }

    @Override
    protected String toHudString() {
        return "Tunnelling under the sea";
    }

    // GoalNear with no wander: a failed progress check under the sea floor wanders off into whatever cave is nearest
    private static final class WaypointTask extends CustomBaritoneGoalTask {
        private final BlockPos at;

        WaypointTask(BlockPos at) {
            super(false);
            this.at = at;
        }

        @Override
        protected Goal newGoal(AltoClef mod) {
            return new GoalNear(at, SeaLeg.GOAL_RANGE);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof WaypointTask t && t.at.equals(at);
        }

        @Override
        protected String toDebugString() {
            return "Tunnelling to " + at.toShortString();
        }
    }
}
