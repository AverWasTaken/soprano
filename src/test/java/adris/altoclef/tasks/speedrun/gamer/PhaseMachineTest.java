package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

// whole scripted runs through the engine's brain with fake handlers and fake facts: no game, no Bootstrap
public class PhaseMachineTest {
    private static class TestHost implements PhaseMachine.Host {
        final RunState state = new RunState();
        final GamerConfig cfg = new GamerConfig();
        final FakeFacts facts = new FakeFacts();
        final List<String> said = new ArrayList<>();
        int saves;
        boolean walkOnPortal;

        @Override
        public GamerConfig cfg() {
            return cfg;
        }

        @Override
        public RunState state() {
            return state;
        }

        @Override
        public GamerFacts facts() {
            return facts;
        }

        @Override
        public void say(String line) {
            said.add(line);
        }

        @Override
        public void save() {
            saves++;
        }

        @Override
        public void walkOnEndPortal(boolean on) {
            walkOnPortal = on;
        }
    }

    private TestHost host;
    private List<FakeHandler> handlers;
    private PhaseMachine machine;

    @Before
    public void setUp() {
        host = new TestHost();
        handlers = FakeHandler.full();
        machine = new PhaseMachine(new ArrayList<PhaseHandler>(handlers), host);
    }

    private FakeHandler h(GamerPhase p) {
        return handlers.stream().filter(x -> x.phase == p).findFirst().orElseThrow();
    }

    private void start() {
        machine.begin(null);
    }

    // one engine tick, `seconds` of game time later, with no progress made
    private void tickAfter(double seconds) {
        host.facts.seconds(seconds);
        machine.tick(null);
    }

    @Test
    public void aStraightRunWalksEveryPhaseAndFinishes() {
        // each phase is done once its own item is in the bag, the way the real predicates look
        FakeHandler[] chain = handlers.toArray(new FakeHandler[0]);
        boolean[] done = new boolean[chain.length];
        for (int i = 0; i < chain.length; i++) {
            int index = i;
            chain[i].doneWhen(f -> done[index]);
        }
        start();
        List<GamerPhase> seen = new ArrayList<>();
        for (int i = 0; i < chain.length; i++) {
            machine.tick(null);
            assertEquals(chain[i].phase, host.state.phase);
            seen.add(host.state.phase);
            assertEquals(1, chain[i].ticks);
            done[i] = true;
            host.facts.seconds(30);
            host.facts.x += 50;
        }
        machine.tick(null);
        assertEquals(GamerPhase.DONE, host.state.phase);
        assertTrue(host.state.finished);
        assertTrue(machine.ended());
        assertEquals(11, seen.size());
        assertTrue(host.said.contains("Beat the game."));
        assertTrue(host.saves > 0);
        // an ended run does nothing
        assertNull(machine.tick(null));
        // every handler was entered once and left once
        for (FakeHandler fh : handlers) {
            assertEquals(fh.phase.name(), List.of("enter1", "exit"), fh.events);
        }
    }

    @Test
    public void aBudgetOverrunRetriesOnceThenGetsStuck() {
        start();
        // gather budget is 8 minutes. stall off so only the budget speaks
        h(GamerPhase.GATHER).stall = 0;
        tickAfter(8 * 60 + 1);
        assertEquals(GamerPhase.GATHER, host.state.phase);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        assertFalse(machine.ended());
        // the retry has its own fresh budget
        tickAfter(8 * 60 - 1);
        assertFalse(machine.ended());
        tickAfter(2);
        assertTrue(machine.ended());
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("8 minutes"));
        // the phase stays where it was, so #gamer resumes there
        assertEquals(GamerPhase.GATHER, host.state.phase);
        assertTrue(host.said.get(host.said.size() - 1).contains("State saved, #gamer resumes."));
        assertTrue(host.said.get(host.said.size() - 1).contains("getting started"));
        assertNull(machine.tick(null));
    }

    @Test
    public void aHandlerCanChooseToSkipItsPhase() {
        host.state.phase = GamerPhase.IRON;
        h(GamerPhase.IRON).onTimeout = Timeout.SKIP;
        h(GamerPhase.IRON).stall = 0;
        start();
        tickAfter(22 * 60 + 1);
        assertEquals(GamerPhase.PORTAL, host.state.phase);
        assertEquals(1, host.state.attemptsOf(GamerPhase.PORTAL));
        assertEquals(List.of("enter1", "timeout1", "exit"), h(GamerPhase.IRON).events);
        assertEquals(List.of("enter1"), h(GamerPhase.PORTAL).events);
        assertFalse(machine.ended());
    }

    @Test
    public void skippingTheDragonIsStuckInstead() {
        host.state.phase = GamerPhase.DRAGON;
        h(GamerPhase.DRAGON).onTimeout = Timeout.SKIP;
        h(GamerPhase.DRAGON).stall = 0;
        start();
        tickAfter(25 * 60 + 1);
        assertTrue(host.state.stuck);
        assertFalse(host.state.finished);
    }

    @Test
    public void aStalledPhaseTimesOutButProgressKeepsItAlive() {
        start();
        // 100 s of nothing, then the inventory changes: the stall timer starts over
        tickAfter(100);
        host.facts.fingerprint++;
        tickAfter(100);
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
        // 125 s of nothing since the change
        tickAfter(25);
        assertTrue(h(GamerPhase.GATHER).events.contains("timeout1"));
    }

    @Test
    public void movingAboutSixBlocksCountsAsProgressButShufflingDoesNot() {
        start();
        tickAfter(100);
        host.facts.x += 2;
        tickAfter(100);
        host.facts.x += 2;
        tickAfter(100);
        // two blocks twice never beat six, so the stall fired somewhere in there
        assertTrue(h(GamerPhase.GATHER).events.contains("timeout1"));

        TestHost host2 = new TestHost();
        // long budget, this half is about the stall timer only
        host2.cfg.budgets.gather = 10000;
        List<FakeHandler> hs = FakeHandler.full();
        PhaseMachine m2 = new PhaseMachine(new ArrayList<PhaseHandler>(hs), host2);
        m2.begin(null);
        for (int i = 0; i < 10; i++) {
            host2.facts.seconds(100);
            host2.facts.x += 7;
            m2.tick(null);
        }
        assertEquals(List.of("enter1"), hs.get(0).events);
    }

    @Test
    public void changingDimensionCountsAsProgress() {
        start();
        tickAfter(100);
        host.facts.dimension = Dimension.NETHER;
        tickAfter(100);
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void aHandlerCanReportProgressAndFailure() {
        start();
        tickAfter(100);
        machine.progress("found a tree");
        tickAfter(100);
        assertEquals(List.of("enter1"), h(GamerPhase.GATHER).events);
        machine.fail("no trees anywhere");
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        assertEquals(2, host.state.attemptsOf(GamerPhase.GATHER));
    }

    @Test
    public void aResumedRunWithEverythingSatisfiedSkipsAhead() {
        for (GamerPhase p : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.PORTAL, GamerPhase.NETHER}) {
            h(p).alwaysDone();
        }
        start();
        machine.tick(null);
        assertEquals(GamerPhase.EYES, host.state.phase);
        // the skipped phases were never entered
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.GATHER).events);
        assertEquals(List.of("enter1"), h(GamerPhase.EYES).events);
        assertTrue(h(GamerPhase.IRON).events.isEmpty());
        assertEquals(1, h(GamerPhase.EYES).ticks);
    }

    @Test
    public void regressesLimitedToOncePerSixtySecondsAndCountsNetherRevisits() {
        host.state.phase = GamerPhase.LOCATE;
        h(GamerPhase.LOCATE).regress = Optional.of(GamerPhase.NETHER);
        h(GamerPhase.LOCATE).stall = 0;
        h(GamerPhase.NETHER).stall = 0;
        h(GamerPhase.EYES).stall = 0;
        h(GamerPhase.RETURN).stall = 0;
        start();
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertEquals(1, host.state.netherRevisits);
        // nether is satisfied again and the run flows back to LOCATE
        h(GamerPhase.NETHER).alwaysDone();
        h(GamerPhase.EYES).alwaysDone();
        h(GamerPhase.RETURN).alwaysDone();
        machine.tick(null);
        assertEquals(GamerPhase.LOCATE, host.state.phase);
        // LOCATE wants to regress again straight away: not within 60 s of the first time
        host.facts.seconds(10);
        machine.tick(null);
        assertEquals(GamerPhase.LOCATE, host.state.phase);
        assertEquals(1, host.state.netherRevisits);
        host.facts.seconds(60);
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertEquals(2, host.state.netherRevisits);
    }

    @Test
    public void aThrowingHandlerIsAFailedAttemptNeverARethrow() {
        FakeHandler gather = h(GamerPhase.GATHER);
        gather.tickThrows = new IllegalStateException("boom");
        start();
        // first tick: the handler throws, nothing escapes
        machine.tick(null);
        // second tick: the failure is handled as a timeout, attempt 1 -> retry
        gather.tickThrows = null;
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), gather.events);
        // and when it keeps throwing the run ends stuck instead of looping
        gather.tickThrows = new IllegalStateException("boom");
        for (int i = 0; i < 6 && !machine.ended(); i++) {
            machine.tick(null);
        }
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("error in the gather phase"));
    }

    @Test
    public void aThrowingOnTimeoutIsStuck() {
        FakeHandler gather = new FakeHandler(GamerPhase.GATHER) {
            @Override
            public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
                throw new IllegalArgumentException("bad handler");
            }
        };
        List<PhaseHandler> hs = new ArrayList<>(handlers);
        hs.set(0, gather);
        machine = new PhaseMachine(hs, host);
        machine.begin(null);
        machine.fail("whatever");
        machine.tick(null);
        assertTrue(host.state.stuck);
    }

    @Test
    public void startingAlreadyStuckOrFinishedEntersNothing() {
        host.state.finished = true;
        host.state.phase = GamerPhase.DONE;
        machine = new PhaseMachine(new ArrayList<PhaseHandler>(handlers), host);
        machine.begin(null);
        assertTrue(machine.ended());
        assertNull(machine.tick(null));
        for (FakeHandler fh : handlers) {
            assertTrue(fh.events.isEmpty());
        }
    }

    @Test
    public void theContextTheHandlersSeeIsTheRealThing() {
        start();
        host.facts.seconds(42);
        assertEquals(1, machine.attempt());
        assertEquals(42, machine.secondsInPhase(), 1e-6);
        machine.walkOnEndPortal(true);
        assertTrue(host.walkOnPortal);
        machine.log("hello");
        assertEquals(List.of("hello"), host.said);
        int before = host.saves;
        machine.save();
        assertEquals(before + 1, host.saves);
        assertEquals(host.cfg, machine.cfg());
        assertEquals(host.state, machine.state());
    }

    @Test
    public void exitCurrentIsWhatAStopCalls() {
        start();
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void aStuckRunDoesNotExitItsHandlerAgainOnTheRealStop() {
        host.state.phase = GamerPhase.IRON;
        start();
        machine.stuck("out of ideas");
        assertTrue(host.state.stuck);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.IRON).events);
        // the chain then stops the task for real, which asks the machine to exit the current phase again
        machine.exitCurrent(null);
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.IRON).events);
    }

    @Test
    public void aFinishedRunDoesNotExitTwiceEither() {
        host.state.phase = GamerPhase.DRAGON;
        start();
        machine.finish(false);
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.DRAGON).events);
    }

    @Test
    public void aRetryStillPairsExitAndEnter() {
        start();
        machine.fail("first");
        machine.tick(null);
        machine.fail("second");
        machine.tick(null);
        assertTrue(host.state.stuck);
        machine.exitCurrent(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2", "timeout2", "exit"), h(GamerPhase.GATHER).events);
    }

    @Test
    public void theSameRegressThreeTimesIsAPingPongNotARecovery() {
        host.state.phase = GamerPhase.LOCATE;
        FakeHandler locate = h(GamerPhase.LOCATE);
        locate.regress = Optional.of(GamerPhase.NETHER);
        for (GamerPhase p : new GamerPhase[]{GamerPhase.LOCATE, GamerPhase.NETHER, GamerPhase.EYES, GamerPhase.RETURN}) {
            h(p).stall = 0;
        }
        start();
        // NETHER, EYES and RETURN are satisfied at once, so every regress flows straight back to LOCATE
        h(GamerPhase.NETHER).alwaysDone();
        h(GamerPhase.EYES).alwaysDone();
        h(GamerPhase.RETURN).alwaysDone();
        for (int round = 1; round <= PhaseMachine.MAX_REGRESS_PER_PAIR; round++) {
            host.facts.seconds(61);
            machine.tick(null);
            assertEquals("regress " + round, GamerPhase.NETHER, host.state.phase);
            assertEquals(Integer.valueOf(round), host.state.regressCounts.get("LOCATE>NETHER"));
            machine.tick(null);
            assertEquals(GamerPhase.LOCATE, host.state.phase);
        }
        host.facts.seconds(61);
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("phase ping pong"));
        // the phase stays where it was, #gamer resumes there
        assertEquals(GamerPhase.LOCATE, host.state.phase);
    }

    @Test
    public void differentRegressPairsCountSeparately() {
        host.state.regressCounts.put("LOCATE>NETHER", PhaseMachine.MAX_REGRESS_PER_PAIR);
        host.state.phase = GamerPhase.OPEN;
        h(GamerPhase.OPEN).regress = Optional.of(GamerPhase.NETHER);
        h(GamerPhase.OPEN).stall = 0;
        start();
        machine.tick(null);
        assertEquals(GamerPhase.NETHER, host.state.phase);
        assertFalse(host.state.stuck);
    }

    @Test
    public void anExceptionFromTheChildTaskIsAFailedAttemptToo() {
        start();
        machine.failFromChild(new IllegalStateException("child blew up"));
        machine.tick(null);
        assertEquals(List.of("enter1", "timeout1", "exit", "enter2"), h(GamerPhase.GATHER).events);
        machine.failFromChild(new IllegalStateException("again"));
        machine.tick(null);
        assertTrue(host.state.stuck);
        assertTrue(host.state.stuckReason, host.state.stuckReason.contains("error in the gather phase"));
    }

    @Test
    public void theClockNeverGoesBackwards() {
        start();
        host.facts.seconds(100);
        double before = machine.now();
        // a new level can report game time 0 for a few ticks after a dimension change
        host.facts.gameTime = 0;
        assertEquals(before, machine.now(), 1e-9);
        assertEquals(before, machine.secondsInPhase(), 1e-9);
        host.facts.gameTime = 20 * 150;
        assertEquals(150, machine.now(), 1e-9);
    }

    @Test
    public void aStuckDragonRunTellsYouHowToStartTheGearCheckAgain() {
        host.state.phase = GamerPhase.DRAGON;
        start();
        machine.stuck("died 3 times");
        assertTrue(host.said.get(host.said.size() - 1), host.said.get(host.said.size() - 1).contains("#gamer phase end_prep"));
        TestHost other = new TestHost();
        other.state.phase = GamerPhase.IRON;
        PhaseMachine m = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), other);
        m.begin(null);
        m.stuck("x");
        assertFalse(other.said.get(other.said.size() - 1).contains("end_prep"));
    }

    @Test
    public void aFinishedStateOnlyFinishesOnce() {
        host.state.phase = GamerPhase.DRAGON;
        start();
        machine.finish(true);
        machine.finish(true);
        assertEquals(1, host.said.stream().filter(s -> s.startsWith("Beat the game")).count());
        assertEquals(List.of("enter1", "exit"), h(GamerPhase.DRAGON).events);
    }
}
