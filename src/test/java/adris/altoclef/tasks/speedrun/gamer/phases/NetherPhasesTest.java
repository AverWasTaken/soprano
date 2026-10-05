package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.FakeFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import baritone.api.utils.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// isDone / regress / timeout rules of the nether, eyes and return phases. the ticks are game code and not tested here
public class NetherPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    private final RunState state = new RunState();
    private final NetherPhase nether = new NetherPhase();
    private final EyesPhase eyes = new EyesPhase();
    private final ReturnPhase back = new ReturnPhase();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FakeFacts bag(int rods, int powder, int pearls, int eyes) {
        FakeFacts f = new FakeFacts();
        f.give(Items.BLAZE_ROD, rods).give(Items.BLAZE_POWDER, powder).give(Items.ENDER_PEARL, pearls).give(Items.ENDER_EYE, eyes);
        f.dimension = Dimension.NETHER;
        return f;
    }

    // a context that only knows the config, the state and the facts, which is all onTimeout reads
    private GamerContext ctx(GamerFacts facts) {
        return new GamerContext() {
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
            public int attempt() {
                return 1;
            }

            @Override
            public double secondsInPhase() {
                return 0;
            }

            @Override
            public void save() {
            }

            @Override
            public void progress(String what) {
            }

            @Override
            public void fail(String reason) {
            }

            @Override
            public void log(String line) {
            }

            @Override
            public void walkOnEndPortal(boolean on) {
            }
        };
    }

    @Test
    public void handlersKnowTheirPhase() {
        assertEquals(GamerPhase.NETHER, nether.phase());
        assertEquals(GamerPhase.EYES, eyes.phase());
        assertEquals(GamerPhase.RETURN, back.phase());
        assertEquals("Looking for a fortress", nether.hud());
    }

    @Test
    public void netherIsDoneWithTheFullKit() {
        assertTrue(nether.isDone(bag(7, 0, 14, 0), state, cfg));
        assertFalse(nether.isDone(bag(7, 0, 13, 0), state, cfg));
        assertFalse(nether.isDone(bag(6, 0, 14, 0), state, cfg));
        assertFalse(nether.isDone(bag(0, 0, 0, 0), state, cfg));
    }

    @Test
    public void netherAcceptsTheFloorOnlyOnceTheBudgetIsNearlyOver() {
        FakeFacts floor = bag(6, 0, 12, 0);
        state.phaseEnteredGameTime = 1000;
        floor.gameTime = 1000 + 20 * 60 * 39;
        assertFalse(nether.isDone(floor, state, cfg));
        // 40 min budget minus the 20 s grace
        floor.gameTime = 1000 + 20 * (40 * 60 - 20);
        assertTrue(nether.isDone(floor, state, cfg));
        // still under the floor: no
        FakeFacts under = bag(6, 0, 11, 0);
        under.gameTime = floor.gameTime;
        assertFalse(nether.isDone(under, state, cfg));
    }

    @Test
    public void unsetPhaseClockNeverCountsAsBudgetOver() {
        FakeFacts floor = bag(6, 0, 12, 0);
        floor.gameTime = 10_000_000;
        state.phaseEnteredGameTime = 0;
        assertFalse(nether.isDone(floor, state, cfg));
    }

    @Test
    public void givingUpOnRodsAcceptsTheFloor() {
        FakeFacts floor = bag(6, 0, 12, 0);
        assertFalse(nether.isDone(floor, state, cfg));
        state.netherRodsGaveUp = true;
        assertTrue(nether.isDone(floor, state, cfg));
        // but never below it
        assertFalse(nether.isDone(bag(5, 0, 12, 0), state, cfg));
    }

    @Test
    public void timeoutLeavesWithTheFloorElseRetriesThenGivesUp() {
        assertEquals(Timeout.SKIP, nether.onTimeout(ctx(bag(6, 0, 12, 0)), 1, "slow"));
        assertEquals(Timeout.RETRY, nether.onTimeout(ctx(bag(2, 0, 3, 0)), 1, "slow"));
        assertEquals(Timeout.STUCK, nether.onTimeout(ctx(bag(2, 0, 3, 0)), cfg.maxAttempts, "slow"));
    }

    @Test
    public void eyesDoneAtTargetOrFloorWithNothingLeft() {
        assertTrue(eyes.isDone(bag(0, 0, 0, 14), state, cfg));
        assertFalse(eyes.isDone(bag(7, 0, 14, 0), state, cfg));
        assertTrue(eyes.isDone(bag(0, 0, 2, 12), state, cfg));
        assertFalse(eyes.isDone(bag(1, 0, 2, 12), state, cfg));
    }

    @Test
    public void eyesRegressToTheNetherOnceWhenTheBagCannotMakeTheFloor() {
        assertEquals(java.util.Optional.of(GamerPhase.NETHER), eyes.regressTo(bag(1, 0, 5, 2), state, cfg));
        // enough potential: stay
        assertTrue(eyes.regressTo(bag(6, 0, 12, 0), state, cfg).isEmpty());
        assertTrue(eyes.regressTo(bag(0, 0, 0, 12), state, cfg).isEmpty());
        // already used the one allowed trip
        state.netherRevisits = 1;
        assertTrue(eyes.regressTo(bag(1, 0, 5, 2), state, cfg).isEmpty());
    }

    @Test
    public void returnIsDoneInTheOverworldOnly() {
        FakeFacts f = bag(0, 0, 0, 0);
        assertFalse(back.isDone(f, state, cfg));
        f.dimension = Dimension.OVERWORLD;
        assertTrue(back.isDone(f, state, cfg));
        f.dimension = Dimension.END;
        assertFalse(back.isDone(f, state, cfg));
    }
}
