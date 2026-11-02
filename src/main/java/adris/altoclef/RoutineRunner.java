package adris.altoclef;

import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commands.RoutineStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

// runs one saved routine, step after step. lives here and not in the gui: the screen builds a new facade whenever it
// feels like it and a run has to outlive that. a step only moves on when the one before it really got there, a cancel,
// a crash or another task taking the bot ends the whole run, and nothing ever resumes on its own (a death or a
// disconnect halfway through a list is a fresh Run). what comes next is the Program's call: a list walks down, a graph
// follows wires. main thread only, like everything that starts tasks
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

    // same, for a wait that can come back with more than made it or not
    public interface WaitEnd {
        void ended(UserTaskChain.Outcome outcome, Result result);
    }

    // how something the program asked for came out. a cancel or a replace isn't one of these: those end the run and
    // never go down a failed wire, the bot being told to stop isn't a thing to route around
    public record Result(Kind kind, String message) {
        public enum Kind {
            OK,
            FAILED,
            TIMEOUT,
            YES,
            NO
        }

        public static final Result OK = new Result(Kind.OK, null);
        public static final Result TIMEOUT = new Result(Kind.TIMEOUT, null);
        public static final Result YES = new Result(Kind.YES, null);
        public static final Result NO = new Result(Kind.NO, null);

        public static Result failed(String why) {
            return new Result(Kind.FAILED, why == null ? "that failed" : why);
        }
    }

    // the one thing to do next
    public sealed interface Op {
        // null on End, which has nothing to report back about
        String node();

        // wait for the next tick before doing this one
        default boolean yieldFirst() {
            return false;
        }

        record Step(String node, RoutineStep step) implements Op {
        }

        // answered on the spot, no task
        record Inline(String node, Supplier<Result> eval, boolean yieldFirst) implements Op {
        }

        // poll says null until it's over
        record WaitUntil(String node, Supplier<Result> poll, double timeoutSeconds, String label) implements Op {
        }

        record End(State state, String message) implements Op {
            @Override
            public String node() {
                return null;
            }
        }
    }

    // what a graph run has to show on its cards: where it is, the last few nodes, how each node last came out, the
    // repeat counters and the wires that ran. a list has none of this, null
    public record Trace(String at, List<String> recent, Map<String, String> outcomes, Map<String, Integer> repeats, Set<String> taken) {
    }

    public interface Program {
        // why it can't start, null when it can. check is the host's last look at one step
        String precheck(Function<RoutineStep, String> check);

        Op first();

        Op next(String node, Result result);

        // how many ops one advance may do before it waits for the next tick
        int inlineBudget();

        // the per step task lines stay out of chat
        boolean quiet();

        int step();

        int total();

        String line();

        Trace trace();
    }

    // the game side, so the tests can drive a run without one
    public interface Host {
        // null when the step may run, else why not
        String check(RoutineStep step);

        Start start(RoutineStep step, StepEnd end);

        // same, without the chat lines a user task normally gets
        default Start startQuiet(RoutineStep step, StepEnd end) {
            return start(step, end);
        }

        // the until kind of wait. a user task, so the bot's stop and death rules all still apply to it
        default Start startWait(Supplier<Result> poll, double timeoutSeconds, String label, WaitEnd end) {
            return Start.failed("this can't wait on a condition");
        }

        // the user chain's task right now. the watchdog compares it to the step's
        Object currentTask();

        // cancels the user task, for a death or a run that got replaced by a step that doesn't start a task of its own
        void cancelTask();

        // somebody's whisper had the bot, the owner taking it back tells them
        void ownerTook();

        // a quiet run is over. it kept out of chat the whole way, this is the one line it gets
        default void quietRunEnded(String name, State state, String message) {
        }
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
        final Program program;
        State state = State.RUNNING;
        String message;
        Op op;
        // bumped per op: a loop comes back to the same node, so the node alone can't tell an old callback from a new one
        int seq;
        // the task the current step waits on, null between steps
        Object task;
        boolean starting;
        // an end that came back while start() was still running (a command that is done right away)
        UserTaskChain.Outcome early;
        Result earlyResult;
        // what a ";" line does next, once it ended well. a failed or stopped run takes the rest of the line with it, same
        // as a failed line of a list
        Runnable onDone;
        // waiting for the next tick. parkedTask = the user task at that point, a different one on the way back in means
        // somebody took the bot in between
        boolean parked;
        Object parkedTask;
        boolean yieldPaid;

        Run(int number, String id, String name, Program program) {
            this.number = number;
            this.id = id;
            this.name = name;
            this.program = program;
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
        return new Status(r.id, r.name, r.program.step(), r.program.total(), r.program.line(), r.state, r.message);
    }

    // null when there's no run or it's a list
    public Trace trace() {
        return run == null ? null : run.program.trace();
    }

    // a copy of the steps goes in, so an edit in the tab while it runs changes the next run and not this one
    public void start(String id, String name, List<RoutineStep> steps) {
        start(id, name, new LinearProgram(steps));
    }

    public void start(String id, String name, Program program) {
        start(id, name, program, null);
    }

    public void start(String id, String name, Program program, Runnable onDone) {
        if (isRunning()) {
            Run old = run;
            Object oldTask = old.task;
            end(old, State.REPLACED, "replaced by " + name);
            // the new run's first step may not start a task of its own, and the old one's gather must not keep going
            if (oldTask != null && host.currentTask() == oldTask) {
                host.cancelTask();
            }
        }
        Run r = new Run(++runs, id, name, program);
        r.onDone = onDone;
        run = r;
        // all of it before the first one runs, a typo in step 5 should not cost the four before it
        String why = program.precheck(host::check);
        if (why != null) {
            end(r, State.FAILED, why);
            return;
        }
        r.op = program.first();
        if (r.op instanceof Op.End e) {
            end(r, e.state(), e.message());
            return;
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
        Run r = run;
        if (r == null || r.state != State.RUNNING) {
            return;
        }
        // a task can be dropped without its callback (the runner switching off under it). waiting on it forever is worse
        // than calling the run off
        if (r.task != null && host.currentTask() != r.task) {
            end(r, State.CANCELLED, "the step's task went away");
        } else if (r.parked) {
            resume(r);
        }
    }

    private void resume(Run r) {
        r.parked = false;
        Object now = host.currentTask();
        if (now != null && now != r.parkedTask) {
            end(r, State.REPLACED, "another task took over");
            return;
        }
        r.yieldPaid = true;
        advance(r);
    }

    private void end(Run r, State state, String message) {
        r.state = state;
        r.message = message;
        r.task = null;
        r.parked = false;
        if (r.program.quiet()) {
            host.quietRunEnded(r.name, state, message);
        }
        Runnable done = r.onDone;
        r.onDone = null;
        if (done != null && state == State.DONE) {
            done.run();
        }
    }

    private void advance(Run r) {
        int budget = r.program.inlineBudget();
        while (r == run && r.state == State.RUNNING) {
            Op op = r.op;
            if (op instanceof Op.End e) {
                end(r, e.state(), e.message());
                return;
            }
            if (budget <= 0 || (op.yieldFirst() && !r.yieldPaid)) {
                r.parked = true;
                r.parkedTask = host.currentTask();
                return;
            }
            r.yieldPaid = false;
            budget--;
            Result result = runOp(r, op);
            // the step itself can end the run (a stop line, a death in the same tick)
            if (r != run || r.state != State.RUNNING) {
                return;
            }
            if (result == null) {
                if (r.early == null) {
                    r.task = host.currentTask();
                    return;
                }
                result = settle(r, r.early, r.earlyResult);
                if (result == null) {
                    return;
                }
            }
            r.op = r.program.next(op.node(), result);
        }
    }

    // the op's result, null when a user task carries it from here
    private Result runOp(Run r, Op op) {
        if (op instanceof Op.Inline inline) {
            try {
                return inline.eval().get();
            } catch (RuntimeException e) {
                Debug.logWarning("routine " + r.id + " node " + op.node() + " threw: " + e);
                return Result.failed("that broke, the log has the details");
            }
        }
        int seq = ++r.seq;
        r.task = null;
        r.early = null;
        r.earlyResult = null;
        r.starting = true;
        Start s;
        try {
            s = startOp(r, op, seq);
        } catch (RuntimeException e) {
            Debug.logWarning("routine " + r.id + " step " + r.program.step() + " threw: " + e);
            s = Start.failed("that step broke, the log has the details");
        } finally {
            r.starting = false;
        }
        if (s.failed()) {
            return Result.failed(s.error());
        }
        return s.waiting() ? null : Result.OK;
    }

    private Start startOp(Run r, Op op, int seq) {
        if (op instanceof Op.WaitUntil wait) {
            return host.startWait(wait.poll(), wait.timeoutSeconds(), wait.label(), (outcome, result) -> onEnded(r, seq, outcome, result));
        }
        RoutineStep step = ((Op.Step) op).step();
        StepEnd end = (outcome, failure) -> onEnded(r, seq, outcome, failure == null ? Result.OK : Result.failed(failure));
        return r.program.quiet() ? host.startQuiet(step, end) : host.start(step, end);
    }

    private void onEnded(Run r, int seq, UserTaskChain.Outcome outcome, Result result) {
        // an old run, or an old step of this one. whoever it was, nobody is waiting on it anymore
        if (r != run || r.state != State.RUNNING || seq != r.seq) {
            return;
        }
        if (r.starting) {
            if (r.early == null) {
                r.early = outcome;
                r.earlyResult = result;
            }
            return;
        }
        Result settled = settle(r, outcome, result);
        if (settled != null) {
            r.op = r.program.next(r.op.node(), settled);
            advance(r);
        }
    }

    // what the finished task amounts to, null when it ended the run instead
    private Result settle(Run r, UserTaskChain.Outcome outcome, Result result) {
        r.task = null;
        switch (outcome) {
            case FINISHED -> {
                return result == null ? Result.failed("that step ended without saying how") : result;
            }
            case CANCELLED -> end(r, State.CANCELLED, "stopped");
            case REPLACED -> end(r, State.REPLACED, "another task took over");
        }
        return null;
    }
}
