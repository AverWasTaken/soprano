package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.RoutineRunner.Result;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerReal;
import java.util.function.Supplier;

// stands there until a routine's condition comes true or the timeout runs out. a user task and not a poll in the runner:
// the trackers are fresh in onTick, and #stop, a death or another task end it the way they end anything else. same clock
// deal as TimedWaitTask, eating halfway through doesn't push the timeout back
public class ConditionWaitTask extends Task {

    private final Supplier<Result> poll;
    private final double timeoutSeconds;
    private final String label;
    private TimerReal timer;
    private Result result;

    public ConditionWaitTask(Supplier<Result> poll, double timeoutSeconds, String label) {
        this.poll = poll;
        this.timeoutSeconds = timeoutSeconds;
        this.label = label;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (timer == null) {
            timer = new TimerReal(timeoutSeconds);
            timer.reset();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        setDebugState("Waiting until " + label);
        step(timer.elapsed());
        return null;
    }

    // the condition gets the last word: true on the very tick the time runs out still counts
    void step(boolean timedOut) {
        if (result == null) {
            Result now = poll.get();
            result = now == null && timedOut ? Result.TIMEOUT : now;
        }
    }

    // null until it's over
    public Result result() {
        return result;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return result != null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Wait until " + label;
    }
}
