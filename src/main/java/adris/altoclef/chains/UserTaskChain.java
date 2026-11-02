package adris.altoclef.chains;

import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import adris.altoclef.AltoSettings;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.RoutineRunner;
import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
@SuppressWarnings("ALL")
public class UserTaskChain extends SingleTaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    private Listener _currentOnFinish = null;
    // true while cancel() runs, so the finish it calls knows the task did not get there on its own
    private boolean _cancelling;

    private boolean _runningIdleTask;
    private boolean _nextTaskIdleFlag;
    // a graph routine starts a user task per node, a loop of those would fill the chat with task set / finished lines
    private boolean _runningQuiet;
    private boolean _nextTaskQuietFlag;
    // a parked graph held the idle command back, and if the run ends on a tick with no task nobody else starts it
    private boolean _idleOwed;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    // how a user task ended. the old callback could not tell these apart: a cancel, a crash and a real finish all
    // looked the same, and a task that got replaced never called anything at all
    public enum Outcome {
        FINISHED,
        CANCELLED,
        REPLACED
    }

    public interface Listener {
        void ended(Outcome outcome);
    }

    // the old Runnable callbacks keep their exact old meaning: run on a finish and on a cancel, never on a replace. the
    // ";" lines lean on that (a stop bumps the generation and the line ends itself), and a replaced "a;b" must not go
    // on to start b over whatever replaced it
    public static Listener legacy(Runnable onFinish) {
        if (onFinish == null) {
            return null;
        }
        return outcome -> {
            if (outcome != Outcome.REPLACED) {
                onFinish.run();
            }
        };
    }

    // a task the chain dropped on its own is only finished when it was not stopped. the tick path gets here for
    // isFinished || stopped, and a stopped root never got there by itself. pure so a test can ask
    static Outcome outcomeOf(boolean cancelling, Task oldTask) {
        if (cancelling || oldTask == null || oldTask.stopped()) {
            return Outcome.CANCELLED;
        }
        return Outcome.FINISHED;
    }

    // the callback a new one pushes out, if it never got to run. that one gets told it was replaced
    static Listener pushedOut(Listener current, Listener next) {
        return current != null && current != next ? current : null;
    }

    private static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.equals("")) {
            result += "and ";
        }
        result += String.format("%.3f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick(AltoClef mod) {

        // Pause if we're not loaded into a world.
        if (!mod.inGame()) return;

        super.onTick(mod);
    }

    public void cancel(AltoClef mod) {
        // installed but not ticked yet is not active, and that one has to be cancellable too
        if (_mainTask != null) {
            boolean before = _cancelling;
            _cancelling = true;
            try {
                stop(mod);
                onTaskFinish(mod);
            } finally {
                _cancelling = before;
            }
        }
    }

    @Override
    public float getPriority(AltoClef mod) {
        return 50;
    }

    @Override
    public String getName() {
        return "User Tasks";
    }

    // what you asked for is the title, "User Tasks" says nothing to the person who typed the command
    @Override
    public String getHudName() {
        return null;
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        runTask(mod, task, legacy(onFinish));
    }

    public void runTask(AltoClef mod, Task task, Listener onFinish) {
        _runningIdleTask = _nextTaskIdleFlag;
        _nextTaskIdleFlag = false;
        _runningQuiet = _nextTaskQuietFlag;
        _nextTaskQuietFlag = false;

        // whoever was waiting on the old task hears about it now, or it waits forever. told before the new task goes in,
        // so it can't mistake the new one for its own
        Listener replaced = pushedOut(_currentOnFinish, onFinish);
        _currentOnFinish = onFinish;
        if (replaced != null) {
            try {
                replaced.ended(Outcome.REPLACED);
            } catch (Throwable t) {
                AltoClefBridge.onHookError(t);
            }
        }

        if (!_runningIdleTask) {
            logTask("User Task Set: " + task.toString());
        }
        mod.getTaskRunner().enable();
        _taskStopwatch.begin();
        setTask(task);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // asked before anything below: the runner disable stops every chain, which stops and drops our task, and a
        // finished task would read as cancelled afterwards
        Outcome outcome = outcomeOf(_cancelling, _mainTask);
        boolean shouldIdle = AltoSettings.shouldRunIdleCommandWhenNotActive();
        if (!shouldIdle) {
            // Stop. (with the idle gate on the runner stays up so the survival chains keep going)
            if (!Baritone.settings().altoRunsWhenIdle.value) {
                try {
                    mod.getTaskRunner().disable();
                } catch (Throwable t) {
                    // disable puts everything back before it throws, so the rest of the finish (clearing the task, telling
                    // whoever waits for it) still has to happen or the next command would find a half dead task
                    AltoClefBridge.onHookError(t);
                }
            }
            // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        }
        double seconds = _taskStopwatch.time();
        Task oldTask = _mainTask;
        // same as the finished branch in SingleTaskChain. cancel and the runner shutdown stop the task first, so there
        // it has nothing left to do, it is cheap insurance for the paths that drop the root without a stop
        if (oldTask != null) {
            oldTask.letGoOfKeys(mod);
        }
        _mainTask = null;
        // let go of it before it runs: the callback may start the next task, and that one must not find this callback
        // still waiting and tell it it was replaced
        Listener onFinish = _currentOnFinish;
        _currentOnFinish = null;
        if (onFinish != null) {
            onFinish.ended(outcome);
        }
        // our `onFinish` might have triggered more tasks.
        boolean actuallyDone = _mainTask == null;
        if (actuallyDone) {
            if (!_runningIdleTask) {
                logTask("User task FINISHED. Took " + prettyPrintTimeDuration(seconds) + " seconds.");
                EventBus.publish(new TaskFinishedEvent(seconds, oldTask));
            }
            // a graph routine parked between two nodes isn't idle, the next node is one tick away and the idle command
            // would run on every lap
            if (shouldIdle && RoutineRunner.get().isRunning()) {
                _idleOwed = true;
            } else if (shouldIdle) {
                _idleOwed = false;
                startIdleCommand();
            }
        }
    }

    private void startIdleCommand() {
        AltoClefCommands.executeTrusted(Baritone.settings().altoIdleCommand.value);
        signalNextTaskToBeIdleTask();
        _runningIdleTask = true;
    }

    // right after the routine runner's tick, which is where a parked graph finds out it's over
    public void runOwedIdle() {
        if (!_idleOwed || RoutineRunner.get().isRunning()) {
            return;
        }
        _idleOwed = false;
        if (idleStillWanted(_mainTask != null, AltoSettings.shouldRunIdleCommandWhenNotActive())) {
            startIdleCommand();
        }
    }

    // a task that got in first has the bot, and its own finish starts the idle command when it's time
    static boolean idleStillWanted(boolean hasTask, boolean shouldIdle) {
        return !hasTask && shouldIdle;
    }

    private void logTask(String line) {
        if (_runningQuiet) {
            Debug.logInternal(line);
        } else {
            Debug.logMessage(line);
        }
    }

    public boolean isRunningIdleTask() {
        return isActive() && _runningIdleTask;
    }

    // the next task logs to the log file only. clear it again if the command turned out not to start one
    public void signalNextTaskQuiet() {
        _nextTaskQuietFlag = true;
    }

    public void clearNextTaskQuiet() {
        _nextTaskQuietFlag = false;
    }

    // The next task will be an idle task.
    public void signalNextTaskToBeIdleTask() {
        _nextTaskIdleFlag = true;
    }
}
