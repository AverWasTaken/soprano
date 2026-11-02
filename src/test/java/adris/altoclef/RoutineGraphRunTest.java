package adris.altoclef;

import static adris.altoclef.commands.graph.GraphTestKit.forever;
import static adris.altoclef.commands.graph.GraphTestKit.graph;
import static adris.altoclef.commands.graph.GraphTestKit.hasLogs;
import static adris.altoclef.commands.graph.GraphTestKit.iff;
import static adris.altoclef.commands.graph.GraphTestKit.night;
import static adris.altoclef.commands.graph.GraphTestKit.raw;
import static adris.altoclef.commands.graph.GraphTestKit.start;
import static adris.altoclef.commands.graph.GraphTestKit.stop;
import static adris.altoclef.commands.graph.GraphTestKit.times;
import static adris.altoclef.commands.graph.GraphTestKit.until;
import static adris.altoclef.commands.graph.GraphTestKit.waitSecs;
import static adris.altoclef.commands.graph.GraphTestKit.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.RoutineRunner.Result;
import adris.altoclef.RoutineRunner.State;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.commands.graph.Conditions;
import adris.altoclef.commands.graph.GraphProgram;
import adris.altoclef.commands.graph.GraphTestKit;
import adris.altoclef.commands.graph.GraphValidator;
import adris.altoclef.commands.graph.RoutineGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;

// the interpreter walked with fake actions: every line either comes back done on the spot or sits on a pretend task until
// the test says how it ended
public class RoutineGraphRunTest {

    private static final class Host implements RoutineRunner.Host {
        final List<String> started = new ArrayList<>();
        final List<RoutineRunner.StepEnd> ends = new ArrayList<>();
        // what a line does. missing = done on the spot, like a raw command
        final Map<String, RoutineRunner.Start> answers = new HashMap<>();
        final List<Supplier<Result>> polls = new ArrayList<>();
        final List<RoutineRunner.WaitEnd> waitEnds = new ArrayList<>();
        final List<String> endedRuns = new ArrayList<>();
        Object current;
        int quietStarts;
        int loudStarts;
        Runnable onStart;

        @Override
        public String check(RoutineStep step) {
            return null;
        }

        @Override
        public RoutineRunner.Start start(RoutineStep step, RoutineRunner.StepEnd end) {
            loudStarts++;
            return begin(step, end);
        }

        @Override
        public RoutineRunner.Start startQuiet(RoutineStep step, RoutineRunner.StepEnd end) {
            quietStarts++;
            return begin(step, end);
        }

        private RoutineRunner.Start begin(RoutineStep step, RoutineRunner.StepEnd end) {
            started.add(step.line());
            ends.add(end);
            if (onStart != null) {
                onStart.run();
            }
            RoutineRunner.Start a = answers.getOrDefault(step.line(), RoutineRunner.Start.DONE);
            if (a.waiting()) {
                current = new Object();
            }
            return a;
        }

        @Override
        public RoutineRunner.Start startWait(Supplier<Result> poll, double timeoutSeconds, String label, RoutineRunner.WaitEnd end) {
            polls.add(poll);
            waitEnds.add(end);
            current = new Object();
            return RoutineRunner.Start.WAITING;
        }

        @Override
        public Object currentTask() {
            return current;
        }

        @Override
        public void cancelTask() {
            current = null;
        }

        @Override
        public void ownerTook() {
        }

        @Override
        public void quietRunEnded(String name, State state, String message) {
            endedRuns.add(state.name());
        }

        void endStep(UserTaskChain.Outcome o, String failure) {
            current = null;
            ends.get(ends.size() - 1).ended(o, failure);
        }

        void endWait(UserTaskChain.Outcome o, Result r) {
            current = null;
            waitEnds.get(waitEnds.size() - 1).ended(o, r);
        }
    }

    private Host host;
    private RoutineRunner runner;
    private GraphTestKit.FakeWorld world;
    private final List<String> logged = new ArrayList<>();

    @Before
    public void setUp() {
        host = new Host();
        runner = new RoutineRunner(host);
        world = new GraphTestKit.FakeWorld();
    }

    private void run(RoutineGraph g) {
        runner.start("test", "test", new GraphProgram(g, world, GraphValidator.Checks.SHAPE_ONLY, logged::add));
    }

    private State state() {
        return runner.status().state();
    }

    private void waits(String line) {
        host.answers.put(line, RoutineRunner.Start.WAITING);
    }

    @Test
    public void aChainRunsStartToFinish() {
        run(graph(List.of(start(), raw("a", "one"), raw("b", "two")), "s.out>a", "a.ok>b"));
        assertEquals(List.of("one", "two"), host.started);
        assertEquals(State.DONE, state());
        assertEquals(List.of("DONE"), host.endedRuns);
        assertEquals(List.of("s", "a", "b"), runner.trace().recent());
    }

    @Test
    public void graphStepsAreQuietAndListStepsArent() {
        run(graph(List.of(start(), raw("a", "one")), "s.out>a"));
        assertEquals(1, host.quietStarts);
        assertEquals(0, host.loudStarts);
        runner.start("list", "list", List.of(new RoutineStep(RoutineStep.Kind.RAW, "two")));
        assertEquals(1, host.loudStarts);
        assertNull("a list has no trace", runner.trace());
    }

    @Test
    public void anEmptyEndIsDoneNotAFailure() {
        run(graph(List.of(start(), iff("i", night())), "s.out>i"));
        assertEquals(State.DONE, state());
    }

    @Test
    public void aFailureGoesDownItsWire() {
        host.answers.put("bad", RoutineRunner.Start.failed("nope"));
        run(graph(List.of(start(), raw("a", "bad"), raw("b", "fallback")), "s.out>a", "a.failed>b"));
        assertEquals(List.of("bad", "fallback"), host.started);
        assertEquals(State.DONE, state());
        assertEquals("FAILED", runner.trace().outcomes().get("a"));
    }

    @Test
    public void aFailureWithoutAWireFailsTheRun() {
        host.answers.put("bad", RoutineRunner.Start.failed("nope"));
        run(graph(List.of(start(), raw("a", "bad"), raw("b", "next")), "s.out>a", "a.ok>b"));
        assertEquals(List.of("bad"), host.started);
        assertEquals(State.FAILED, state());
        assertTrue(runner.status().message(), runner.status().message().contains("nope"));
        assertEquals("a", runner.trace().at());
    }

    @Test
    public void aTaskThatGaveUpIsAFailure() {
        waits("goto");
        run(graph(List.of(start(), raw("a", "goto"), raw("b", "fallback")), "s.out>a", "a.failed>b"));
        assertEquals(State.RUNNING, state());
        assertEquals("a", runner.trace().at());
        host.endStep(UserTaskChain.Outcome.FINISHED, "couldn't get there");
        assertEquals(List.of("goto", "fallback"), host.started);
    }

    @Test
    public void aCancelOrAReplaceNeverGoesDownTheFailedWire() {
        waits("goto");
        run(graph(List.of(start(), raw("a", "goto"), raw("b", "fallback")), "s.out>a", "a.failed>b"));
        host.endStep(UserTaskChain.Outcome.CANCELLED, null);
        assertEquals(State.CANCELLED, state());
        assertEquals(List.of("goto"), host.started);
        run(graph(List.of(start(), raw("a", "goto"), raw("b", "fallback")), "s.out>a", "a.failed>b"));
        host.endStep(UserTaskChain.Outcome.REPLACED, null);
        assertEquals(State.REPLACED, state());
        assertEquals(List.of("goto", "goto"), host.started);
    }

    @Test
    public void anIfPicksItsWireWithFreshNumbers() {
        world.day = false;
        run(graph(List.of(start(), iff("i", night()), raw("a", "hide"), raw("b", "mine")), "s.out>i", "i.yes>a", "i.no>b"));
        assertEquals(List.of("hide"), host.started);
        assertEquals(1, world.freshens);
        assertEquals("YES", runner.trace().outcomes().get("i"));
        world.day = true;
        host.started.clear();
        run(graph(List.of(start(), iff("i", night()), raw("a", "hide"), raw("b", "mine")), "s.out>i", "i.yes>a", "i.no>b"));
        assertEquals(List.of("mine"), host.started);
    }

    @Test
    public void anUnreadableIfFailsTheRun() {
        world.unreadable = new Conditions.Unreadable("there's no day or night here");
        run(graph(List.of(start(), iff("i", night()), raw("a", "x")), "s.out>i", "i.yes>a", "i.no>a"));
        assertEquals(State.FAILED, state());
        assertTrue(runner.status().message(), runner.status().message().contains("no day or night"));
        assertTrue(host.started.isEmpty());
    }

    @Test
    public void aBackWireYieldsEveryLap() {
        run(graph(List.of(start(), times("r", 3), raw("a", "x"), raw("e", "after")), "s.out>r", "r.body>a", "a.ok>>r", "r.done>e"));
        // first lap goes right away, the back wire waits
        assertEquals(List.of("x"), host.started);
        assertEquals(State.RUNNING, state());
        runner.tick();
        assertEquals(List.of("x", "x"), host.started);
        runner.tick();
        assertEquals(List.of("x", "x", "x"), host.started);
        assertEquals(Integer.valueOf(3), runner.trace().repeats().get("r"));
        runner.tick();
        assertEquals(List.of("x", "x", "x", "after"), host.started);
        assertEquals(State.DONE, state());
    }

    @Test
    public void aForeverLoopOfQuickCommandsDoesOneLapPerTickAndNeverFreezes() {
        run(graph(List.of(start(), forever("r"), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        for (int i = 0; i < 5000; i++) {
            runner.tick();
        }
        assertEquals(5001, host.started.size());
        assertEquals(State.RUNNING, state());
        assertEquals(32, runner.trace().recent().size());
        assertEquals(Integer.valueOf(5001), runner.trace().repeats().get("r"));
        // a log line per lap would bury the log
        assertTrue(logged.toString(), logged.size() < 5);
    }

    @Test
    public void theBudgetStopsALongInlineStretch() {
        List<RoutineGraph.Node> nodes = new ArrayList<>();
        nodes.add(start());
        List<String> wires = new ArrayList<>();
        wires.add("s.out>n0");
        for (int i = 0; i < 100; i++) {
            nodes.add(raw("n" + i, "line" + i));
            if (i > 0) {
                wires.add("n" + (i - 1) + ".ok>n" + i);
            }
        }
        run(graph(nodes, wires.toArray(new String[0])));
        // the start node counts, so 63 raws made it
        assertEquals(GraphProgram.INLINE_BUDGET - 1, host.started.size());
        assertEquals(State.RUNNING, state());
        runner.tick();
        assertEquals(100, host.started.size());
        assertEquals(State.DONE, state());
    }

    @Test
    public void aLongListStillRunsInOneGo() {
        List<RoutineStep> steps = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            steps.add(new RoutineStep(RoutineStep.Kind.RAW, "line" + i));
        }
        runner.start("list", "list", steps);
        assertEquals(100, host.started.size());
        assertEquals(State.DONE, state());
    }

    @Test
    public void onDoneFiresOnceWhenARunEndsOnAParkedTick() {
        int[] done = {0};
        RoutineGraph g = graph(List.of(start(), times("r", 1), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r");
        runner.start("t", "t", new GraphProgram(g, world, GraphValidator.Checks.SHAPE_ONLY, logged::add), () -> done[0]++);
        assertEquals(0, done[0]);
        runner.tick();
        assertEquals(State.DONE, state());
        assertEquals(1, done[0]);
        runner.tick();
        assertEquals(1, done[0]);
    }

    @Test
    public void onDoneMayStartTheNextRun() {
        waits("next");
        RoutineGraph first = graph(List.of(start(), raw("a", "x")), "s.out>a");
        RoutineGraph second = graph(List.of(start(), raw("a", "next")), "s.out>a");
        Runnable onDone = () -> runner.start("two", "two", new GraphProgram(second, world, GraphValidator.Checks.SHAPE_ONLY, logged::add));
        runner.start("one", "one", new GraphProgram(first, world, GraphValidator.Checks.SHAPE_ONLY, logged::add), onDone);
        assertEquals("two", runner.status().id());
        assertEquals(State.RUNNING, state());
        assertEquals(List.of("x", "next"), host.started);
    }

    @Test
    public void aForeignTaskDuringTheYieldEndsTheRun() {
        run(graph(List.of(start(), forever("r"), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        host.current = new Object();
        runner.tick();
        assertEquals(State.REPLACED, state());
        assertEquals(1, host.started.size());
    }

    @Test
    public void aTaskARawLineLeftRunningIsNotForeign() {
        host.onStart = () -> host.current = "the raw line's task";
        run(graph(List.of(start(), times("r", 2), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        runner.tick();
        assertEquals(2, host.started.size());
        assertEquals(State.RUNNING, state());
        // and it ending by itself during the yield is fine too
        host.onStart = null;
        host.current = null;
        runner.tick();
        assertEquals(State.DONE, state());
    }

    @Test
    public void onDoneOnlyRunsOnceAndOnlyWhenItEndedWell() {
        int[] done = {0};
        Runnable onDone = () -> done[0]++;
        RoutineGraph quick = graph(List.of(start(), raw("a", "x")), "s.out>a");
        runner.start("t", "t", new GraphProgram(quick, world, GraphValidator.Checks.SHAPE_ONLY, logged::add), onDone);
        assertEquals(1, done[0]);
        runner.tick();
        assertEquals(1, done[0]);

        host.answers.put("bad", RoutineRunner.Start.failed("nope"));
        RoutineGraph failing = graph(List.of(start(), raw("a", "bad")), "s.out>a");
        runner.start("t", "t", new GraphProgram(failing, world, GraphValidator.Checks.SHAPE_ONLY, logged::add), onDone);
        assertEquals(1, done[0]);

        waits("slow");
        RoutineGraph slow = graph(List.of(start(), raw("a", "slow")), "s.out>a");
        runner.start("t", "t", new GraphProgram(slow, world, GraphValidator.Checks.SHAPE_ONLY, logged::add), onDone);
        runner.cancel("stopped");
        assertEquals(1, done[0]);
        runner.start("t", "t", new GraphProgram(slow, world, GraphValidator.Checks.SHAPE_ONLY, logged::add), onDone);
        runner.start("t", "t", new GraphProgram(quick, world, GraphValidator.Checks.SHAPE_ONLY, logged::add));
        assertEquals(1, done[0]);
    }

    @Test
    public void aStopDuringTheYieldStaysStopped() {
        run(graph(List.of(start(), forever("r"), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        runner.cancel("stopped");
        runner.tick();
        assertEquals(1, host.started.size());
        assertEquals(State.CANCELLED, state());
    }

    @Test
    public void aDeathInALoopEndsItForGood() {
        waits("x");
        run(graph(List.of(start(), forever("r"), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        runner.onDeath();
        assertEquals(State.CANCELLED, state());
        runner.tick();
        assertNull("the step's task goes on the next tick", host.current);
        runner.tick();
        assertEquals(1, host.started.size());
    }

    @Test
    public void aSecondRunReplacesAParkedOne() {
        run(graph(List.of(start(), forever("r"), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        run(graph(List.of(start(), raw("a", "new")), "s.out>a"));
        runner.tick();
        assertEquals(List.of("x", "new"), host.started);
        assertEquals(State.DONE, state());
    }

    @Test
    public void anOldCallbackFromAnEarlierLapIsIgnored() {
        waits("x");
        run(graph(List.of(start(), times("r", 3), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        RoutineRunner.StepEnd firstLap = host.ends.get(0);
        host.endStep(UserTaskChain.Outcome.FINISHED, null);
        runner.tick();
        assertEquals(2, host.started.size());
        // same node, older lap
        firstLap.ended(UserTaskChain.Outcome.FINISHED, null);
        runner.tick();
        assertEquals(2, host.started.size());
        assertEquals(State.RUNNING, state());
    }

    @Test
    public void anInnerRepeatStartsOverOnEveryOuterLap() {
        run(graph(List.of(start(), times("o", 2), times("i", 2), raw("a", "x")),
                "s.out>o", "o.body>i", "i.body>a", "a.ok>>i", "i.done>>o"));
        for (int i = 0; i < 10; i++) {
            runner.tick();
        }
        assertEquals(4, host.started.size());
        assertEquals(State.DONE, state());
        assertEquals(Integer.valueOf(2), runner.trace().repeats().get("o"));
    }

    @Test
    public void wiringOutOfTheBodyIsABreak() {
        host.answers.put("x", RoutineRunner.Start.failed("nope"));
        run(graph(List.of(start(), forever("r"), raw("a", "x"), raw("b", "out")), "s.out>r", "r.body>a", "a.ok>>r", "a.failed>b"));
        assertEquals(List.of("x", "out"), host.started);
        assertEquals(State.DONE, state());
    }

    @Test
    public void untilChecksBeforeEachLapAndGivesUpAtTheCap() {
        run(graph(List.of(start(), until("r", hasLogs(2), 3), raw("a", "x"), raw("e", "after")), "s.out>r", "r.body>a", "a.ok>>r", "r.done>e"));
        assertEquals(1, host.started.size());
        world.logs = 2;
        runner.tick();
        assertEquals(List.of("x", "after"), host.started);
        assertEquals(State.DONE, state());
        assertTrue("an until reads fresh numbers", world.freshens >= 2);

        host.started.clear();
        world.logs = 0;
        run(graph(List.of(start(), until("r", hasLogs(2), 3), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        for (int i = 0; i < 10; i++) {
            runner.tick();
        }
        assertEquals(3, host.started.size());
        assertEquals(State.FAILED, state());
        assertTrue(runner.status().message(), runner.status().message().contains("gave up"));
    }

    @Test
    public void untilAlreadyTrueSkipsTheBody() {
        world.logs = 9;
        run(graph(List.of(start(), until("r", hasLogs(2), 3), raw("a", "x")), "s.out>r", "r.body>a", "a.ok>>r"));
        assertTrue(host.started.isEmpty());
        assertEquals(State.DONE, state());
    }

    @Test
    public void waitUntilIsATaskThatAsksTheWorld() {
        run(graph(List.of(start(), waitUntil("w", hasLogs(1), 60), raw("a", "got them")), "s.out>w", "w.ok>a"));
        assertEquals(1, host.polls.size());
        assertNull(host.polls.get(0).get());
        world.logs = 1;
        assertEquals(Result.OK, host.polls.get(0).get());
        host.endWait(UserTaskChain.Outcome.FINISHED, Result.OK);
        assertEquals(List.of("got them"), host.started);
    }

    @Test
    public void aTimeoutTakesItsWireOrFailsTheRun() {
        run(graph(List.of(start(), waitUntil("w", night(), 60), raw("a", "too late")), "s.out>w", "w.timeout>a"));
        host.endWait(UserTaskChain.Outcome.FINISHED, Result.TIMEOUT);
        assertEquals(List.of("too late"), host.started);
        assertEquals(State.DONE, state());

        run(graph(List.of(start(), waitUntil("w", night(), 60)), "s.out>w"));
        host.endWait(UserTaskChain.Outcome.FINISHED, Result.TIMEOUT);
        assertEquals(State.FAILED, state());
        assertTrue(runner.status().message(), runner.status().message().contains("timeout"));
    }

    @Test
    public void anUnreadableWaitFailsTheRun() {
        run(graph(List.of(start(), waitUntil("w", night(), 60), raw("a", "x")), "s.out>w", "w.timeout>a"));
        world.unreadable = new Conditions.Unreadable("no idea");
        Result polled = host.polls.get(0).get();
        assertEquals(Result.Kind.FAILED, polled.kind());
        host.endWait(UserTaskChain.Outcome.FINISHED, polled);
        assertEquals(State.FAILED, state());
        assertTrue(host.started.isEmpty());
    }

    @Test
    public void aWaitForSecondsIsTheSameStepAListRuns() {
        waits("wait 2.5");
        run(graph(List.of(start(), waitSecs("w", "2.5"), raw("a", "x")), "s.out>w", "w.ok>a"));
        assertEquals(List.of("wait 2.5"), host.started);
        host.endStep(UserTaskChain.Outcome.FINISHED, null);
        assertEquals(List.of("wait 2.5", "x"), host.started);
    }

    @Test
    public void stopEndsTheRunBothWays() {
        run(graph(List.of(start(), stop("e", true), raw("a", "x")), "s.out>e"));
        assertEquals(State.DONE, state());
        run(graph(List.of(start(), stop("e", false)), "s.out>e"));
        assertEquals(State.FAILED, state());
        assertEquals("e", runner.trace().at());
        assertEquals("FAILED", runner.trace().outcomes().get("e"));
    }

    @Test
    public void aGraphThatDoesntValidateNeverStarts() {
        run(graph(List.of(start(), raw("a", "x"), raw("b", "")), "s.out>a", "a.ok>b"));
        assertEquals(State.FAILED, state());
        assertTrue(host.started.isEmpty());
        assertEquals("b", runner.trace().at());
    }

    @Test
    public void theStatusReadsLikeTheEditorNeedsIt() {
        waits("x");
        run(graph(List.of(start(), times("r", 2), raw("a", "x"), raw("q", "y")), "s.out>r", "r.body>a", "a.ok>>r", "r.done>q"));
        RoutineRunner.Status s = runner.status();
        assertEquals(1, s.step());
        assertEquals(2, s.total());
        assertEquals("Raw command: x", s.line());
        RoutineRunner.Trace t = runner.trace();
        assertEquals("a", t.at());
        assertEquals("OK", t.outcomes().get("s"));
        assertEquals("YES", t.outcomes().get("r"));
        assertFalse("not done yet", t.outcomes().containsKey("a"));
        assertEquals(Integer.valueOf(1), t.repeats().get("r"));
        assertTrue(t.taken().toString(), t.taken().contains("s:out:r:in"));
        assertTrue(t.taken().toString(), t.taken().contains("r:body:a:in"));
        host.endStep(UserTaskChain.Outcome.FINISHED, null);
        assertTrue(runner.trace().taken().contains("a:ok:r:again"));
    }

    @Test
    public void aStatusDoesntChangeUnderTheEditor() {
        waits("x");
        run(graph(List.of(start(), raw("a", "x")), "s.out>a"));
        RoutineRunner.Trace t = runner.trace();
        int before = t.outcomes().size();
        host.endStep(UserTaskChain.Outcome.FINISHED, null);
        assertEquals(before, t.outcomes().size());
    }
}
