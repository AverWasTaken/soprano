package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

// the per-tick wood budget: what its fields say about a bag, and that the engine hands every reader the same one
public class WoodBudgetTest {
    private static final int BEDS = 8;
    private final OverworldConfig cfg = new OverworldConfig();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @After
    public void clearFuel() {
        FuelPolicy.clear();
    }

    private static class TestHost implements PhaseMachine.Host {
        final RunState state = new RunState();
        final GamerConfig cfg = new GamerConfig();
        final FakeFacts facts = new FakeFacts();

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
        }

        @Override
        public void save() {
        }

        @Override
        public void walkOnEndPortal(boolean on) {
        }
    }

    @Test
    public void aBareBagOwesTheWholeKit() {
        WoodBudget w = WoodBudget.of(new FakeFacts(), cfg, BEDS);
        assertEquals(15, w.gatherLogsOwed());
        assertEquals(KitPlanner.woodNeed(new FakeFacts(), cfg, BEDS), w.gatherLogsOwed());
        assertEquals(KitPlanner.woodNeed(new FakeFacts(), cfg, BEDS, false), w.ironLogsOwed());
        assertEquals(3 * BEDS, w.bedPlanksOwed());
        // short of the budget every piece is spoken for, there is nothing spare
        assertEquals(0, w.spareLogs());
        assertEquals(0, w.burnableWoodSmelts(0));
    }

    @Test
    public void spareWoodIsWhatIsHeldBeyondTheKeep() {
        // 15 logs is exactly the budget (WoodReserveTest), 5 more are fuel: a log and a plank are 1.5 smelts apiece
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.OAK_PLANKS, 2);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(15, 0), w.keep());
        assertEquals(5, w.spareLogs());
        assertEquals(2, w.sparePlanks());
        assertEquals(10, w.burnableWoodSmelts(0));
        // logs about to go into a smoker come off the spare ones first
        assertEquals(3, w.burnableWoodSmelts(5));
        assertEquals(3, w.burnableWoodSmelts(9));
    }

    @Test
    public void aSmokerOnlyEatsLogsWhenOneStillHasToBeMade() {
        FakeFacts f = new FakeFacts();
        assertEquals(5, WoodBudget.of(f, cfg, BEDS).smokerLogs());
        assertTrue(WoodBudget.of(f, cfg, BEDS).smokerOwed());
        assertEquals(5, WoodBudget.of(f, cfg, BEDS).logsSpokenFor(true));
        assertEquals(0, WoodBudget.of(f, cfg, BEDS).logsSpokenFor(false));
        f.give(Items.CRAFTING_TABLE, 1);
        assertEquals(4, WoodBudget.of(f, cfg, BEDS).smokerLogs());
        f.smokerPlaced = true;
        assertFalse(WoodBudget.of(f, cfg, BEDS).smokerOwed());
        assertEquals(0, WoodBudget.of(f, cfg, BEDS).logsSpokenFor(true));
    }

    @Test
    public void theFuelReserveIsEverythingOutsideTheOverworld() {
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.OAK_PLANKS, 6);
        // the planks count towards the budget too: 14 logs and 4 planks are what the kit still wants
        assertEquals(new WoodReserve.Keep(14, 4), WoodBudget.of(f, cfg, BEDS).fuelKeep());
        f.dimension = Dimension.NETHER;
        WoodBudget nether = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(20, 6), nether.fuelKeep());
        // the planner's own keep does not care where we are
        assertEquals(new WoodReserve.Keep(14, 4), nether.keep());
    }

    @Test
    public void applyingTheBudgetTellsTheFurnacesItsFuelKeep() {
        FakeFacts f = new FakeFacts().give(Items.OAK_LOG, 20).give(Items.OAK_PLANKS, 6);
        WoodReserve.apply(WoodBudget.of(f, cfg, BEDS));
        int[] viaApply = burnable();
        // 14 logs and 4 planks kept
        assertEquals(6, viaApply[0]);
        assertEquals(2, viaApply[1]);
    }

    private static int[] burnable() {
        return FuelPolicy.usable(List.of(new ItemStack(Items.OAK_LOG, 20), new ItemStack(Items.OAK_PLANKS, 6)));
    }

    @Test
    public void theContextBuildsOneFromItsFacts() {
        StubContext ctx = new StubContext();
        ctx.facts.give(Items.OAK_LOG, 20);
        assertEquals(WoodBudget.of(ctx.facts, ctx.cfg.overworld, ctx.cfg.end.beds), ctx.wood());
    }

    @Test
    public void theMachineHandsOutOnePerTickAndRebuildsOnTheNext() {
        TestHost host = new TestHost();
        PhaseMachine machine = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), host);
        machine.begin(null);
        host.facts.give(Items.OAK_LOG, 3);
        WoodBudget first = machine.wood();
        assertSame(first, machine.wood());
        assertEquals(3, first.logs());
        host.facts.give(Items.OAK_LOG, 4);
        machine.tick(null);
        WoodBudget second = machine.wood();
        assertNotSame(first, second);
        assertEquals(7, second.logs());
    }

    @Test
    public void aBudgetTheEngineGaveSurvivesExactlyTheTickItWasGivenFor() {
        TestHost host = new TestHost();
        PhaseMachine machine = new PhaseMachine(new ArrayList<PhaseHandler>(FakeHandler.full()), host);
        machine.begin(null);
        host.facts.give(Items.OAK_LOG, 3);
        WoodBudget given = WoodBudget.of(host.facts, host.cfg.overworld, host.cfg.end.beds);
        machine.giveWood(given);
        assertSame(given, machine.wood());
        machine.tick(null);
        assertSame(given, machine.wood());
        // and the tick after that, with nothing given, is a fresh one
        host.facts.give(Items.OAK_LOG, 1);
        machine.tick(null);
        assertNotSame(given, machine.wood());
        assertEquals(4, machine.wood().logs());
    }

    @Test
    public void theIronChopFollowsTheEarlyLoadLatchEvenWhenItStartsAfterTheBudget() {
        // a bare bag owes iron-phase wood, and the latch can start mid tick (EarlyIronPick.track runs after the plan)
        FakeFacts f = new FakeFacts();
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertTrue(w.ironLogsOwed() > 0);
        assertEquals(w.ironLogsOwed(), w.ironChopLogs(f));
        f.earlyLoad = true;
        assertEquals(0, w.ironChopLogs(f));
    }
}
