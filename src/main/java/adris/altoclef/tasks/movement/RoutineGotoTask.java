package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.tasksystem.Task;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

// a routine's goto <block> / goto x y z / wp goto <name>. those are baritone's and return the moment the goal is set,
// so this holds the routine until baritone has stopped for a bit: standing in the goal is done, stopped anywhere else
// is baritone giving up
public class RoutineGotoTask extends Task {

    // a path that just ended and the next one being calculated look idle for a tick or two
    private static final int IDLE_TICKS = 10;
    // a second of somebody else's goal before we call it theirs. we tick before baritone picks its process for the
    // tick, so the first look after our own command still shows the last one, and the inventory pauser wins a tick here
    // and there too
    private static final int FOREIGN_TICKS = 20;

    private final String line;
    // goto <block> retargets as it finds closer ones, so its goal moves on its own and only the process says it's ours
    private final boolean block;
    private boolean issued;
    private boolean done;
    private String failure;
    private Goal goal;
    private int idle;
    private int foreign;

    public RoutineGotoTask(String line) {
        this.line = line;
        String rest = RoutineLines.rest(line);
        this.block = !RoutineLines.isWaypointGoto(line) && !rest.contains(" ") && !rest.matches("[+-]?\\d+|~.*");
    }

    // null when it got there
    public String failure() {
        return failure;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (done) {
            return;
        }
        idle = 0;
        foreign = 0;
        // back from an interrupt (a fight, a snack): the goal we already had, not the line again. "goto ~100 ~ ~" run a
        // second time would be 100 blocks from wherever the fight left us
        if (issued && goal != null && !block) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }
        issued = true;
        String why = AltoClefCommands.runStock(line);
        if (why != null) {
            fail(why);
        } else if (!block) {
            // coords and waypoints land in the custom goal process right away, so that's the goal we own
            goal = mod.getClientBaritone().getCustomGoalProcess().getGoal();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done) {
            return null;
        }
        IBaritone b = mod.getClientBaritone();
        IBaritoneProcess process = b.getPathingControlManager().mostRecentInControl().orElse(null);
        boolean busy = b.getPathingBehavior().isPathing() || (process != null && process.isActive());
        if (busy) {
            idle = 0;
            foreign = stillOurs(b, process) ? 0 : foreign + 1;
            if (foreign >= FOREIGN_TICKS) {
                fail("something else took baritone (" + line + ")");
                return null;
            }
            setDebugState("Going: " + line);
            return null;
        }
        if (++idle < IDLE_TICKS) {
            return null;
        }
        done = true;
        if (block) {
            // next to the block we asked for, whatever goal baritone ended on. covers standing there already (no goal
            // ever shows up) and a #goto furnace from chat that took over our crafting table
            if (!nextToBlock(mod, b.getPlayerContext().playerFeet())) {
                failure = "couldn't get to a " + RoutineLines.rest(line) + ", baritone gave up";
            }
        } else if (goal == null) {
            failure = "baritone never started going there (" + line + ")";
        } else if (!goal.isInGoal(b.getPlayerContext().playerFeet())) {
            failure = "couldn't get there, baritone gave up (" + line + ")";
        }
        return null;
    }

    // GetToBlock stops next to the block (or on it), two blocks out is plenty
    private boolean nextToBlock(AltoClef mod, BetterBlockPos feet) {
        ClientLevel level = mod.getWorld();
        if (level == null) {
            return false;
        }
        BlockOptionalMeta want;
        try {
            want = new BlockOptionalMeta(RoutineLines.rest(line));
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-2, -2, -2), feet.offset(2, 3, 2))) {
            if (want.matches(level.getBlockState(p))) {
                return true;
            }
        }
        return false;
    }

    // a different goal later (a #goto from chat while we run) means somebody else has baritone now, and arriving there
    // must not count as this step being done. other processes winning a tick (the inventory pauser) are not that
    private boolean stillOurs(IBaritone b, IBaritoneProcess process) {
        if (block) {
            // another #goto <block> on the same process is caught at the end by nextToBlock
            return process == null || process != b.getCustomGoalProcess();
        }
        Goal now = b.getCustomGoalProcess().getGoal();
        if (goal == null) {
            goal = now;
            return true;
        }
        if (now == null) {
            // no custom goal and baritone still busy: a #mine or some other process is the one driving
            return process == null || process == b.getCustomGoalProcess() || !process.isActive();
        }
        return now.equals(goal);
    }

    private void fail(String why) {
        failure = why;
        done = true;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // the goal is ours while we run. an interrupt sets it again on the way back in, a stop or a replace wants it gone
        if (!done) {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Routine " + line;
    }
}
