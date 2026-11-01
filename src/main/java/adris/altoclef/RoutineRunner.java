package adris.altoclef;

import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commands.RoutineStep;
import java.util.List;

// runs one saved routine, step after step. lives here and not in the gui: the screen builds a new facade whenever it
// feels like it and a run has to outlive that. a step only moves on when the one before it really got there, a cancel,
// a crash or another task taking the bot ends the whole run, and nothing ever resumes on its own (a death or a
// disconnect halfway through a list is a fresh Run). main thread only, like everything that starts tasks
public final class RoutineRunner {

    public enum State {
        IDLE,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
        REPLACED
    }

    // step is 1-based, the strip says "step 2/5". after the run it stays on the step it ended on
    public record Status(String id, String name, int step, int total, String line, State state, String message) {
        static final Status IDLE = new Status(null, null, 0, 0, null, State.IDLE, null);
    }

    // how a step's start went: it failed on the spot, it is already done, or a user task carries it from here
    public record Start(boolean failed, boolean waiting, String error) {
        public static final Start DONE = new Start(false, false, null);
        public static final Start WAITING = new Start(false, true, null);

        public static Start failed(String why) {
            return new Start(true, false, why == null ? "that step failed" : why);
        }
    }

    // a step's task is over. failure is set when the task finished but says it didn't make it (a goto that gave up)
    public interface StepEnd {
        void ended(UserTaskChain.Outcome outcome, String failure);
    }

    // the game side, so the tests can drive a run without one
    public interface Host {
        // null when the step may run, else why not
        String check(RoutineStep step);

        Start start(RoutineStep step, StepEnd end);

        // the user chain's task right now. the watchdog compares it to the step's
        Object currentTask();

        // cancels the user task, for a death or a run that got replaced by a step that doesn't start a task of its own
        void cancelTask();

        // somebody's whisper had the bot, the owner taking it back tells them
        void ownerTook();
    }

    private static RoutineRunner shared;

    private final Host host;
    private Run run;
    private int runs;
    // a death cancels the step's task on the next tick, not inside the death packet
    private Object cancelOnTick;

    RoutineRunner(Host host) {
        this.host = host;
    }

    public static synchronized RoutineRunner get() {
        if (shared == null) {
            shared = new RoutineRunner(new RoutineHost());
        }
        return shared;
    }

    private static final class Run {
        final int number;
        final String id;
        final String name;
        final List<RoutineStep> steps;
        int index;
        State state = State.RUNNING;
        String message;
        // the task the current step waits on, null between steps
        Object task;
        boolean starting;
        // an end that came back while start() was still running (a command that is done right away)
        UserTaskChain.Outcome early;
        String earlyFailure;

        Run(int number, String id, String name, List<RoutineStep> steps) {
            this.number = number;
            this.id = id;
            this.name = name;
            this.steps = steps;
        }
    }

    public boolean isRunning() {
        return run != null && run.state == State.RUNNING;
    }

    public Status status() {
        Run r = run;
        if (r == null) {
            return Status.IDLE;
        }
        int total = r.steps.size();
        int at = Math.min(r.index, Math.max(0, total - 1));
        String line = total == 0 ? null : r.steps.get(at).line();
        return new Status(r.id, r.name, total == 0 ? 0 : at + 1, total, line, r.state, r.message);
    }

    // a copy of the steps goes in, so an edit in the tab while it runs changes the next run and not this one
    public void start(String id, String name, List<RoutineStep> steps) {
        if (isRunning()) {
            Run old = run;
            Object oldTask = old.task;
            end(old, State.REPLACED, "replaced by " + name);
            // the new run's first step may not start a task of its own, and the old one's gather must not keep going
            if (oldTask != null && host.currentTask() == oldTask) {
                host.cancelTask();
            }
        }
        Run r = new Run(++runs, id, name, List.copyOf(steps));
        run = r;
        if (r.steps.isEmpty()) {
            end(r, State.DONE, "there are no steps in it");
            return;
        }
        // all of them before the first one runs, a typo in step 5 should not cost the four before it
        for (int i = 0; i < r.steps.size(); i++) {
            String why = host.check(r.steps.get(i));
            if (why != null) {
                r.index = i;
                end(r, State.FAILED, why);
                return;
            }
        }
        host.ownerTook();
        advance(r);
    }

    // the stop button, #stop, a disconnect. the task itself is somebody else's to cancel (stop does that already)
    public void cancel(String why) {
        if (isRunning()) {
            end(run, State.CANCELLED, why);
        }
    }

    // the death packet. no resume, and the step's task goes too, or a gather would carry on after the respawn while the
    // strip says cancelled
    public void onDeath() {
        if (!isRunning()) {
            return;
        }
        Object task = run.task;
        end(run, State.CANCELLED, "died, run it again when you're ready");
        cancelOnTick = task;
    }

    public void tick() {
        Object pending = cancelOnTick;
        cancelOnTick = null;
        if (pending != null && host.currentTask() == pending) {
            host.cancelTask();
        }
        // a task can be dropped without its callback (the runner switching off under it). waiting on it forever is worse
        // than calling the run off
        Run r = run;
        if (r != null && r.state == State.RUNNING && r.task != null && host.currentTask() != r.task) {
            end(r, State.CANCELLED, "the step's task went away");
        }
    }

    private void end(Run r, State state, String message) {
        r.state = state;
        r.message = message;
        r.task = null;
    }

    private void advance(Run r) {
        while (r == run && r.state == State.RUNNING) {
            if (r.index >= r.steps.size()) {
                r.index = r.steps.size() - 1;
                end(r, State.DONE, null);
                return;
            }
            int index = r.index;
            r.task = null;
            r.early = null;
            r.earlyFailure = null;
            r.starting = true;
            Start s;
            try {
                s = host.start(r.steps.get(index), (outcome, failure) -> onEnded(r, index, outcome, failure));
            } catch (RuntimeException e) {
                Debug.logWarning("routine " + r.id + " step " + (index + 1) + " threw: " + e);
                s = Start.failed("that step broke, the log has the details");
            } finally {
                r.starting = false;
            }
            // the step itself can end the run (a stop line, a death in the same tick)
            if (r != run || r.state != State.RUNNING) {
                return;
            }
            if (s.failed()) {
                end(r, State.FAILED, s.error());
                return;
            }
            if (!s.waiting()) {
                r.index++;
                continue;
            }
            if (r.early != null) {
                if (!stepEnded(r, r.early, r.earlyFailure)) {
                    return;
                }
                continue;
            }
            r.task = host.currentTask();
            return;
        }
    }

    private void onEnded(Run r, int index, UserTaskChain.Outcome outcome, String failure) {
        // an old run, or an old step of this one. whoever it was, nobody is waiting on it anymore
        if (r != run || r.state != State.RUNNING || index != r.index) {
            return;
        }
        if (r.starting) {
            if (r.early == null) {
                r.early = outcome;
                r.earlyFailure = failure;
            }
            return;
        }
        if (stepEnded(r, outcome, failure)) {
            advance(r);
        }
    }

    // true when the step made it and the next one is up
    private boolean stepEnded(Run r, UserTaskChain.Outcome outcome, String failure) {
        r.task = null;
        switch (outcome) {
            case FINISHED -> {
                if (failure != null) {
                    end(r, State.FAILED, failure);
                    return false;
                }
                r.index++;
                return true;
            }
            case CANCELLED -> end(r, State.CANCELLED, "stopped");
            case REPLACED -> end(r, State.REPLACED, "another task took over");
        }
        return false;
    }
}
