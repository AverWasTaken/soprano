package adris.altoclef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.commands.RoutineStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

// the runner against a pretend game: every step that waits gets a task object and its end callback kept, the test says
// how each one ends
public class RoutineRunnerTest {

    private static final class Host implements RoutineRunner.Host {
        final List<String> started = new ArrayList<>();
        final List<RoutineRunner.StepEnd> ends = new ArrayList<>();
        // per line: what start() says. missing = a waiting task
        final Map<String, RoutineRunner.Start> answers = new HashMap<>();
        final Map<String, String> problems = new HashMap<>();
        // lines whose task is over before start() even returns
        final Map<String, UserTaskChain.Outcome> instant = new HashMap<>();
        Object current;
        int cancels;
        int owner;

        @Override
        public String check(RoutineStep step) {
            return problems.get(step.line());
        }

        @Override
        public RoutineRunner.Start start(RoutineStep step, RoutineRunner.StepEnd end) {
            started.add(step.line());
            ends.add(end);
            RoutineRunner.Start a = answers.get(step.line());
            if (a != null) {
                return a;
            }
            current = new Object();
            UserTaskChain.Outcome now = instant.get(step.line());
            if (now != null) {
                end.ended(now, null);
            }
            return RoutineRunner.Start.WAITING;
        }

        @Override
        public Object currentTask() {
            return current;
        }

        @Override
        public void cancelTask() {
            cancels++;
            current = null;
        }

        @Override
        public void ownerTook() {
            owner++;
        }

        void end(UserTaskChain.Outcome o) {
            ends.get(ends.size() - 1).ended(o, null);
        }
    }

    private Host host;
    private RoutineRunner runner;

    @Before
    public void setUp() {
        host = new Host();
        runner = new RoutineRunner(host);
    }

    private static List<RoutineStep> steps(String... lines) {
        List<RoutineStep> out = new ArrayList<>();
        for (String l : lines) {
            out.add(new RoutineStep(RoutineStep.Kind.RAW, l));
        }
        return out;
    }

    private RoutineRunner.State state() {
        return runner.status().state();
    }

    @Test
    public void stepsGoOneAfterTheOtherAndOnlyOnAFinish() {
        runner.start("gear", "diamond gear", steps("get a", "get b", "get c"));
        assertEquals(List.of("get a"), host.started);
        assertEquals(1, runner.status().step());
        assertEquals(3, runner.status().total());
        assertEquals("get a", runner.status().line());
        host.end(UserTaskChain.Outcome.FINISHED);
        assertEquals(List.of("get a", "get b"), host.started);
        assertEquals(2, runner.status().step());
        host.end(UserTaskChain.Outcome.FINISHED);
        host.end(UserTaskChain.Outcome.FINISHED);
        assertEquals(RoutineRunner.State.DONE, state());
        assertEquals(3, runner.status().step());
        assertEquals(1, host.owner);
    }

    @Test
    public void aFailedStepStopsTheRest() {
        host.answers.put("get b", RoutineRunner.Start.failed("no such item"));
        runner.start("x", "x", steps("get a", "get b", "get c"));
        host.end(UserTaskChain.Outcome.FINISHED);
        assertEquals(RoutineRunner.State.FAILED, state());
        assertEquals("no such item", runner.status().message());
        assertEquals(2, runner.status().step());
        assertEquals(List.of("get a", "get b"), host.started);
    }

    @Test
    public void aTaskThatFinishedButGaveUpIsAFailure() {
        runner.start("x", "x", steps("goto 1 2 3", "get c"));
        host.ends.get(0).ended(UserTaskChain.Outcome.FINISHED, "couldn't get there");
        assertEquals(RoutineRunner.State.FAILED, state());
        assertEquals(List.of("goto 1 2 3"), host.started);
    }

    @Test
    public void aCancelledTaskEndsTheRunAndNothingMoves() {
        runner.start("x", "x", steps("get a", "get b"));
        host.end(UserTaskChain.Outcome.CANCELLED);
        assertEquals(RoutineRunner.State.CANCELLED, state());
        assertEquals(List.of("get a"), host.started);
    }

    @Test
    public void stopCancelsOnceAndALateFinishDoesNotAdvance() {
        runner.start("x", "x", steps("get a", "get b"));
        runner.cancel("stopped");
        assertEquals(RoutineRunner.State.CANCELLED, state());
        // the chain tells the old callback afterwards, nobody is listening anymore
        host.end(UserTaskChain.Outcome.FINISHED);
        host.end(UserTaskChain.Outcome.CANCELLED);
        runner.cancel("again");
        assertEquals(RoutineRunner.State.CANCELLED, state());
        assertEquals("stopped", runner.status().message());
        assertEquals(List.of("get a"), host.started);
    }

    @Test
    public void anotherTaskTakingTheBotEndsItReplaced() {
        runner.start("x", "x", steps("get a", "get b"));
        host.end(UserTaskChain.Outcome.REPLACED);
        assertEquals(RoutineRunner.State.REPLACED, state());
        assertEquals(List.of("get a"), host.started);
    }

    @Test
    public void aSecondRunReplacesTheFirstAndItsCallbacksGoDead() {
        runner.start("one", "one", steps("get a", "get b"));
        RoutineRunner.StepEnd old = host.ends.get(0);
        runner.start("two", "two", steps("get c"));
        // the first run's task was still the bot's, so it got cancelled
        assertEquals(1, host.cancels);
        assertEquals("two", runner.status().id());
        old.ended(UserTaskChain.Outcome.FINISHED, null);
        assertEquals(List.of("get a", "get c"), host.started);
        assertEquals(RoutineRunner.State.RUNNING, state());
    }

    @Test
    public void aDeathCancelsTheStepsTaskOnTheNextTickAndNeverResumes() {
        runner.start("x", "x", steps("get a", "get b"));
        runner.onDeath();
        assertEquals(RoutineRunner.State.CANCELLED, state());
        assertEquals(0, host.cancels);
        runner.tick();
        assertEquals(1, host.cancels);
        host.end(UserTaskChain.Outcome.CANCELLED);
        host.end(UserTaskChain.Outcome.FINISHED);
        runner.tick();
        assertEquals(List.of("get a"), host.started);
        assertEquals(1, host.cancels);
        assertEquals(RoutineRunner.State.CANCELLED, state());
    }

    @Test
    public void aDeathAfterSomethingElseTookTheBotLeavesThatAlone() {
        runner.start("x", "x", steps("get a"));
        runner.onDeath();
        host.current = new Object();
        runner.tick();
        assertEquals(0, host.cancels);
    }

    @Test
    public void stepsThatAreDoneRightAwayRunStraightThrough() {
        host.answers.put("set a", RoutineRunner.Start.DONE);
        host.answers.put("set b", RoutineRunner.Start.DONE);
        host.instant.put("status", UserTaskChain.Outcome.FINISHED);
        runner.start("x", "x", steps("set a", "status", "set b", "get c"));
        assertEquals(List.of("set a", "status", "set b", "get c"), host.started);
        assertEquals(RoutineRunner.State.RUNNING, state());
        assertEquals(4, runner.status().step());
    }

    @Test
    public void aBadStepIsFoundBeforeAnythingRuns() {
        host.problems.put("get b", "get what?");
        runner.start("x", "x", steps("get a", "get b"));
        assertEquals(RoutineRunner.State.FAILED, state());
        assertEquals(2, runner.status().step());
        assertTrue(host.started.isEmpty());
        assertEquals(0, host.owner);
    }

    @Test
    public void aTaskThatVanishesWithoutACallbackCallsTheRunOff() {
        runner.start("x", "x", steps("get a", "get b"));
        runner.tick();
        assertEquals(RoutineRunner.State.RUNNING, state());
        host.current = null;
        runner.tick();
        assertEquals(RoutineRunner.State.CANCELLED, state());
    }

    @Test
    public void nothingToRunIsDoneAtOnce() {
        runner.start("x", "x", List.of());
        assertEquals(RoutineRunner.State.DONE, state());
        assertFalse(runner.isRunning());
    }

    @Test
    public void theRunKeepsItsOwnCopyOfTheSteps() {
        List<RoutineStep> list = new ArrayList<>(steps("get a", "get b"));
        runner.start("x", "x", list);
        list.set(1, new RoutineStep(RoutineStep.Kind.RAW, "get z"));
        host.end(UserTaskChain.Outcome.FINISHED);
        assertEquals(List.of("get a", "get b"), host.started);
    }

    @Test
    public void idleBeforeAnyRun() {
        assertEquals(RoutineRunner.State.IDLE, state());
        assertEquals(0, runner.status().step());
    }
}
