package adris.altoclef.ui.gui.mc;

import adris.altoclef.AltoClef;
import adris.altoclef.RoutineRunner;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasks.speedrun.gamer.GamerHudState;
import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.Wording;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.IExploreProcess;
import baritone.api.process.IFarmProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.process.IGetToBlockProcess;
import baritone.api.process.IMineProcess;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

// what the status strip says. reads the same places the task hud reads, one level less detailed
final class McStatus implements StatusAccess {

    private static final Activity IDLE = new Activity(false, "Idle", "", null);

    @Override
    public Activity activity() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            AltoClef mod = AltoClef.getInstance();
            String phase = gamerPhase(mod);
            // a routine run says which step it's on, the step's own task is one level too deep for the strip. the
            // strip's Stop is #stop, which ends the run too
            RoutineRunner.Status run = RoutineRunner.get().status();
            if (run.state() == RoutineRunner.State.RUNNING) {
                // a graph of nothing but ifs and repeats has no steps to count
                String count = run.total() == 0 ? "Running" : "Step " + run.step() + "/" + run.total();
                String step = count + (run.line() == null || run.line().isBlank() ? "" : ": " + run.line());
                return new Activity(true, step, run.name() == null ? "" : run.name(), phase);
            }
            String line = null;

            if (mod != null && mod.getTaskRunner().isActive()) {
                UserTaskChain user = mod.getUserTaskChain();
                Task userTask = user.isActive() && !user.isRunningIdleTask() ? user.getCurrentTask() : null;
                if (userTask != null) {
                    line = userTask.getHudName();
                } else {
                    // eating, mob defense and friends have the wheel, say so
                    TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
                    List<Task> tasks = chain == null || chain == user ? List.of() : chain.getTasks();
                    if (!tasks.isEmpty()) {
                        line = tasks.get(tasks.size() - 1).getHudName();
                    }
                }
            }

            if (line != null && line.isBlank()) {
                line = null;
            }
            boolean pathing = baritone.getPathingBehavior().isPathing();
            Optional<IBaritoneProcess> process = baritone.getPathingControlManager().mostRecentInControl();
            if (line == null && process.isPresent()) {
                line = processLabel(process.get());
            }
            if (line == null && pathing) {
                line = "Walking";
            }
            if (line == null || line.isBlank()) {
                return phase == null ? IDLE : new Activity(true, "Running", "", phase);
            }
            return new Activity(true, Wording.soprano(line), goalText(baritone.getPathingBehavior().getGoal()), phase);
        } catch (Throwable t) {
            Safe.report("status", t);
            return IDLE;
        }
    }

    // only while a run is live, the card lingers after a win and that is not "running"
    private static String gamerPhase(AltoClef mod) {
        if (mod == null || !mod.getTaskRunner().isActive()) {
            return null;
        }
        if (mod.getUserTaskChain().getCurrentTask() instanceof GamerTask run) {
            GamerHudState state = run.hudSnapshot();
            return state == null ? null : Wording.soprano(state.phase().hud());
        }
        return null;
    }

    // displayName is for logs and #proc, it says "Custom Goal GoalBlock{x=1,y=2,z=3}". the strip gets the plain
    // version, the goal itself already sits next to it in goalText
    static String processLabel(IBaritoneProcess process) {
        if (process instanceof ICustomGoalProcess) {
            return "Walking to the goal";
        }
        if (process instanceof IMineProcess) {
            return "Mining";
        }
        if (process instanceof IFollowProcess) {
            return "Following";
        }
        if (process instanceof IExploreProcess) {
            return "Exploring";
        }
        if (process instanceof IElytraProcess) {
            return "Flying";
        }
        if (process instanceof IGetToBlockProcess) {
            return "Looking for a block";
        }
        if (process instanceof IFarmProcess) {
            return "Farming";
        }
        if (process instanceof IBuilderProcess b) {
            return b.isPaused() ? "Building, paused" : "Building";
        }
        return cleanName(process.displayName());
    }

    // the long tail (torches, backfill, self defense, whatever a mod adds) keeps its own words minus the java bits
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        if (name.equals("inventory pauser")) {
            return "Waiting on the inventory";
        }
        // the \w* takes the type glued to the braces too, "BlockPos{...}" goes as one piece
        String s = name.replaceAll("\\w*\\{[^}]*}", "").replace("minecraft:", "").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String goalText(Goal goal) {
        if (goal == null) {
            return "";
        }
        if (goal instanceof GoalXZ xz) {
            return "Goal " + xz.getX() + " " + xz.getZ();
        }
        if (goal instanceof GoalYLevel y) {
            return "Goal y " + y.level;
        }
        if (goal instanceof GoalNear near) {
            BlockPos p = near.getGoalPos();
            return "Near " + p.getX() + " " + p.getY() + " " + p.getZ();
        }
        // GoalBlock, GoalGetToBlock and GoalTwoBlocks all know their spot
        if (goal instanceof IGoalRenderPos pos) {
            BlockPos p = pos.getGoalPos();
            return "Goal " + p.getX() + " " + p.getY() + " " + p.getZ();
        }
        if (goal instanceof GoalComposite) {
            return "Nearest of several goals";
        }
        // GoalRunAway{...} and friends mean nothing to a player, the activity line already says what's going on
        return "";
    }

    @Override
    public Result stop() {
        // the command itself: it also clears a pause, which is not something to copy out of it
        Result r = new McCommands().run("stop");
        return r.ok() ? Result.ok("Stopped", "stop") : r;
    }
}
