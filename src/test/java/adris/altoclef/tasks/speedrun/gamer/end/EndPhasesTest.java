package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.DragonPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.EndPrepPhase;
import baritone.api.utils.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the pure half of the two handlers: isDone and regressTo over fake facts. the tick logic needs a game and is not covered here
public class EndPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    private final EndPrepPhase prep = new EndPrepPhase();
    private final DragonPhase dragon = new DragonPhase();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void endDeath(RunState state) {
        RunState.Death d = new RunState.Death();
        d.dimension = "END";
        state.deaths.add(d);
    }

    @Test
    public void endPrepIsOnlyDoneOnceWeAreInTheEnd() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        // a full kit standing next to the portal is not done, walking in is the last step of the phase
        assertFalse(prep.isDone(f, s, cfg));
        f.dimension = Dimension.END;
        assertTrue(prep.isDone(f, s, cfg));
    }

    @Test
    public void endPrepIsDoneWhenTheRunAlreadyKnowsTheDragonIsDeadOrCreditsRolled() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        s.dragonDead = true;
        assertTrue(prep.isDone(f, s, cfg));
        s.dragonDead = false;
        f.credits = true;
        assertTrue(prep.isDone(f, s, cfg));
    }

    @Test
    public void dragonIsDoneAfterTheDragonDiedAndWeAreHome() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        f.dimension = Dimension.END;
        s.dragonDead = true;
        // still standing in the End: the exit portal walk is not done yet
        assertFalse(dragon.isDone(f, s, cfg));
        f.dimension = Dimension.OVERWORLD;
        assertTrue(dragon.isDone(f, s, cfg));
    }

    @Test
    public void dragonIsDoneOnCreditsAndNotOnAnAliveDragon() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        assertFalse(dragon.isDone(f, s, cfg));
        f.credits = true;
        assertTrue(dragon.isDone(f, s, cfg));
    }

    @Test
    public void aDeathSendsUsBackToEndPrepWhileAttemptsLast() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        endDeath(s);
        assertEquals(Optional.of(GamerPhase.END_PREP), dragon.regressTo(f, s, cfg));
        endDeath(s);
        assertEquals(Optional.of(GamerPhase.END_PREP), dragon.regressTo(f, s, cfg));
        endDeath(s);
        assertEquals(cfg.end.attempts, EndRules.endDeaths(s));
        assertEquals(Optional.empty(), dragon.regressTo(f, s, cfg));
    }

    @Test
    public void noRegressInTheEndOrAfterTheDragonDied() {
        FakeFacts f = new FakeFacts();
        RunState s = new RunState();
        f.dimension = Dimension.END;
        assertEquals(Optional.empty(), dragon.regressTo(f, s, cfg));
        f.dimension = Dimension.OVERWORLD;
        s.dragonDead = true;
        assertEquals(Optional.empty(), dragon.regressTo(f, s, cfg));
    }

    @Test
    public void deathsElsewhereDoNotUseUpDragonAttempts() {
        RunState s = new RunState();
        for (String dim : new String[]{"OVERWORLD", "NETHER", "minecraft:the_nether"}) {
            RunState.Death d = new RunState.Death();
            d.dimension = dim;
            s.deaths.add(d);
        }
        assertEquals(0, EndRules.endDeaths(s));
        for (String dim : new String[]{"END", "the_end", "minecraft:the_end"}) {
            assertTrue(EndRules.isEnd(dim));
        }
    }

    @Test
    public void handlersReportTheirPhaseAndWaitWithoutAStallTimer() {
        assertEquals(GamerPhase.END_PREP, prep.phase());
        assertEquals(GamerPhase.DRAGON, dragon.phase());
        assertEquals(0, dragon.stallSeconds(), 0);
        assertTrue(prep.stallSeconds() > 0);
    }
}
