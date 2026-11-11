package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.FakeFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.util.helpers.ItemFixture;
import adris.altoclef.util.helpers.RunZones;
import baritone.api.utils.Dimension;
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
        ItemFixture.boot();
    }

    @Test
    public void theFortressWalkWaitsOutAPackOnOurSpawner() {
        RunState.Pos spawner = new RunState.Pos(100, 70, 100);
        RunZones zones = new RunZones();
        assertFalse(NetherPhase.waitOutPack(zones, spawner, 1000));
        // one skeleton's fat zone is no reason to wait
        zones.add(100, 100, 1000, RunZones.RADIUS);
        assertFalse(NetherPhase.waitOutPack(zones, spawner, 1000));
        zones.add(105, 100, 1000, RunZones.PACK_RADIUS);
        assertTrue(NetherPhase.waitOutPack(zones, spawner, 1000));
        assertFalse(NetherPhase.waitOutPack(zones, spawner, 1000 + RunZones.EXPIRY_TICKS));
        // no spawner saved (a fortress given up clears it): the walk goes
        assertFalse(NetherPhase.waitOutPack(zones, null, 1000));
    }

    @Test
    public void aPickedSpawnerBeatsTheSweep() {
        assertTrue(NetherPhase.sweepInstead(true, false));
        assertFalse(NetherPhase.sweepInstead(true, true));
        assertFalse(NetherPhase.sweepInstead(false, false));
        assertFalse(NetherPhase.sweepInstead(false, true));
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
        assertTrue(nether.isDone(bag(9, 0, 16, 0), state, cfg));
        // 8 rods covers the target, the exit waits for the spare one
        assertFalse(nether.isDone(bag(8, 0, 16, 0), state, cfg));
        assertFalse(nether.isDone(bag(9, 0, 15, 0), state, cfg));
        assertFalse(nether.isDone(bag(7, 0, 16, 0), state, cfg));
        assertFalse(nether.isDone(bag(0, 0, 0, 0), state, cfg));
    }

    @Test
    public void netherAcceptsTheFloorOnlyOnceTheBudgetIsNearlyOver() {
        FakeFacts floor = bag(7, 0, 13, 0);
        state.phaseEnteredGameTime = 1000;
        floor.gameTime = 1000 + 20 * 60 * 39;
        assertFalse(nether.isDone(floor, state, cfg));
        // 40 min budget minus the 20 s grace
        floor.gameTime = 1000 + 20 * (40 * 60 - 20);
        assertTrue(nether.isDone(floor, state, cfg));
        // still under the floor: no
        FakeFacts under = bag(7, 0, 12, 0);
        under.gameTime = floor.gameTime;
        assertFalse(nether.isDone(under, state, cfg));
    }

    @Test
    public void unsetPhaseClockNeverCountsAsBudgetOver() {
        FakeFacts floor = bag(7, 0, 13, 0);
        floor.gameTime = 10_000_000;
        state.phaseEnteredGameTime = 0;
        assertFalse(nether.isDone(floor, state, cfg));
    }

    @Test
    public void givingUpOnRodsAcceptsTheFloor() {
        FakeFacts floor = bag(7, 0, 13, 0);
        assertFalse(nether.isDone(floor, state, cfg));
        state.netherRodsGaveUp = true;
        assertTrue(nether.isDone(floor, state, cfg));
        // but never below it
        assertFalse(nether.isDone(bag(6, 0, 13, 0), state, cfg));
    }

    @Test
    public void timeoutLeavesWithTheFloorElseRetriesThenGivesUp() {
        assertEquals(Timeout.SKIP, nether.onTimeout(ctx(bag(7, 0, 13, 0)), 1, "slow"));
        assertEquals(Timeout.RETRY, nether.onTimeout(ctx(bag(2, 0, 3, 0)), 1, "slow"));
        assertEquals(Timeout.STUCK, nether.onTimeout(ctx(bag(2, 0, 3, 0)), cfg.maxAttempts, "slow"));
    }

    @Test
    public void aNetherTripBackFromOpenOnlyNeedsTheEmptyFrames() {
        // OPEN filled 5 of 12 and ran dry: 7 empty frames + the 4 spare, not another 16
        state.framesFilled = 5;
        state.netherRevisits = 1;
        // the ring was never read here, so the spare rod rides on top (7 rods, not 6)
        assertTrue(nether.isDone(bag(7, 0, 11, 0), state, cfg));
        assertFalse(nether.isDone(bag(6, 0, 11, 0), state, cfg));
        // the floor (7 empty + the 1 spare) when the budget or the rods run out
        FakeFacts eight = bag(4, 0, 8, 0);
        assertFalse(nether.isDone(eight, state, cfg));
        state.netherRodsGaveUp = true;
        assertTrue(nether.isDone(eight, state, cfg));
        assertEquals(Timeout.SKIP, nether.onTimeout(ctx(bag(4, 0, 8, 0)), 1, "slow"));
        assertEquals(Timeout.RETRY, nether.onTimeout(ctx(bag(3, 0, 7, 0)), 1, "slow"));
    }

    @Test
    public void aRevisitAfterARealReadOfTheRingWantsNoSpare() {
        // OPEN read 5 of 12 and the bag was short: the way back needs exactly the 7 empty frames
        state.framesFilled = 5;
        state.netherRevisits = 1;
        state.openRead = true;
        assertTrue(nether.isDone(bag(4, 0, 7, 0), state, cfg));
        assertFalse(nether.isDone(bag(3, 0, 7, 0), state, cfg));
        assertFalse(nether.isDone(bag(4, 0, 6, 0), state, cfg));
        assertTrue(eyes.isDone(bag(0, 0, 0, 7), state, cfg));
    }

    @Test
    public void eyesPhaseAgreesAboutTheEmptyFrames() {
        state.framesFilled = 5;
        assertTrue(eyes.isDone(bag(0, 0, 0, 11), state, cfg));
        assertTrue(eyes.isDone(bag(0, 0, 2, 8), state, cfg));
        assertFalse(eyes.isDone(bag(0, 0, 0, 7), state, cfg));
        // 8 eyes' worth in the bag is enough to stay, 7 sends us back (once)
        assertTrue(eyes.regressTo(bag(4, 0, 8, 0), state, cfg).isEmpty());
        assertEquals(java.util.Optional.of(GamerPhase.NETHER), eyes.regressTo(bag(3, 0, 7, 0), state, cfg));
    }

    @Test
    public void eyesDoneAtTargetOrFloorWithNothingLeft() {
        assertTrue(eyes.isDone(bag(0, 0, 0, 16), state, cfg));
        assertFalse(eyes.isDone(bag(8, 0, 16, 0), state, cfg));
        assertTrue(eyes.isDone(bag(0, 0, 2, 13), state, cfg));
        assertFalse(eyes.isDone(bag(1, 0, 2, 13), state, cfg));
    }

    @Test
    public void eyesRegressToTheNetherOnceWhenTheBagCannotMakeTheFloor() {
        assertEquals(java.util.Optional.of(GamerPhase.NETHER), eyes.regressTo(bag(1, 0, 5, 2), state, cfg));
        // enough potential: stay
        assertTrue(eyes.regressTo(bag(7, 0, 13, 0), state, cfg).isEmpty());
        assertTrue(eyes.regressTo(bag(0, 0, 0, 13), state, cfg).isEmpty());
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

    @Test
    public void huntClockPausesOverAGapAndKeepsRunningWithoutOne() {
        // ticking along: untouched
        assertEquals(100, NetherPhase.pausedClock(100, 500, 501), 1e-9);
        assertEquals(100, NetherPhase.pausedClock(100, 500, 502), 1e-9);
        // 60 s away from the hunt: the start slides forward by the gap
        assertEquals(160, NetherPhase.pausedClock(100, 500, 560), 1e-9);
        // nothing started yet, or never seen: nothing to pause
        assertEquals(-1, NetherPhase.pausedClock(-1, 500, 560), 1e-9);
        assertEquals(100, NetherPhase.pausedClock(100, -1, 560), 1e-9);
    }

    @Test
    public void aDryHuntWithTheFloorInTheBagStillLeaves() {
        assertEquals(NetherPhase.DryHunt.LEAVE, NetherPhase.dryHunt(true, new RunState.Pos(10, 70, 10)));
        assertEquals(NetherPhase.DryHunt.LEAVE, NetherPhase.dryHunt(true, null));
    }

    @Test
    public void aDryHuntWithoutTheFloorMovesOnOrKeepsGoing() {
        // a known forest gets written off, no forest never asks the planner to give up a null
        assertEquals(NetherPhase.DryHunt.NEXT_FOREST, NetherPhase.dryHunt(false, new RunState.Pos(10, 70, 10)));
        assertEquals(NetherPhase.DryHunt.KEEP_HUNTING, NetherPhase.dryHunt(false, null));
    }
}
