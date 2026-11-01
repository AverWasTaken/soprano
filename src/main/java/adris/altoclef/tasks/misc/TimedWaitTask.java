package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerReal;

// stands there for a while and then is done. a user task like any other, so #stop, a death or another task end it the
// usual way. the clock starts on the first tick and not on every resume: eating in the middle of a 60 s wait doesn't
// make it a 70 s wait
public class TimedWaitTask extends Task {

    private final double seconds;
    private TimerReal timer;

    public TimedWaitTask(double seconds) {
        this.seconds = seconds;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (timer == null) {
            timer = new TimerReal(seconds);
            timer.reset();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        setDebugState("Waiting " + format(seconds) + " s");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return timer != null && timer.elapsed();
    }

    // two waits are never the same wait, a second one asked for while the first runs starts over
    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Wait " + format(seconds) + " s";
    }

    private static String format(double s) {
        return s == Math.floor(s) ? String.valueOf((long) s) : String.valueOf(s);
    }
}
