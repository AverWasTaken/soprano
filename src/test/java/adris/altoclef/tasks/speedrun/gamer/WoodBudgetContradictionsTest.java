package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

// where the wood views still disagree with each other. every test in here pins what the code answers TODAY and every one of those
// answers is KNOWN WRONG: they are waiting on a behaviour round (to be play-tested on its own), and the fix is meant to flip the
// assertions. nothing here is a feature, don't "fix" a test to make the code look right
public class WoodBudgetContradictionsTest {
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

    // everything the kit makes out of wood is made, the beds are the only wood left owed
    private static FakeFacts madeEverything() {
        return new FakeFacts().give(Items.WOODEN_PICKAXE, 1).give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2)
                .give(Items.STONE_AXE, 1).give(Items.CRAFTING_TABLE, 1).give(Items.FURNACE, 1).give(Items.SHIELD, 1)
                .give(Items.LADDER, 3).give(Items.IRON_PICKAXE, 1).give(Items.IRON_AXE, 1).give(Items.BUCKET, 2)
                .give(Items.SHEARS, 1).give(Items.FLINT_AND_STEEL, 1);
    }

    // (a) in the IRON phase the reserve keeps logs for the end fight's beds, and IRON never chops for them (the bed planks are the
    // gather's business). so the logs it kept are sitting there, a smoker can't be fuelled from them, and nothing goes and gets the
    // smoker's logs either (cookFuelLogs is the gather's). the cook gate's station pick counts those same logs as a smoker's price
    @Test
    public void theIronReserveKeepsLogsForBedsNothingInIronChopsFor() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 5).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(0, w.ironLogsOwed());
        assertEquals(0, w.ironChopLogs(f));
        // known wrong: the gather's view of the beds is what the reserve is built from, in every phase
        assertEquals(2, w.gatherLogsOwed());
        assertEquals(new WoodReserve.Keep(5, 0), w.keep());
        assertEquals(0, w.spareLogs());
        assertEquals(0, w.burnableWoodSmelts(0));
        // known wrong: the plan asks for no logs, so the smoker's four never get chopped
        assertFalse(KitPlanner.plan(f, cfg, BEDS).stream().anyMatch(n -> n.catalogueName().equals("log")));
        // known wrong: the station pick says smoker off logs the reserve keeps, and the cook need then dies on fuel
        assertEquals(CookGate.Station.SMOKER, CookGate.station(f, cfg, true, w));
        assertNull(CookGate.need(f, cfg, BEDS, w));
    }

    // (b) the coal detour counts the wood a furnace could burn WITHOUT the logs a smoker still to make would eat, the cook need
    // takes those off. so the detour thinks there is more fuel than the cook does and fetches a coal or two short
    @Test
    public void theCoalDetourDoesNotSubtractTheSmokersLogs() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 12).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30).give(Items.WHITE_BED, 8);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertTrue(w.smokerOwed());
        assertEquals(18, w.burnableWoodSmelts(0));
        assertEquals(12, w.burnableWoodSmelts(w.smokerLogs()));
        StubContext ctx = new StubContext() {
            @Override
            public GamerFacts facts() {
                return f;
            }
        };
        ctx.cfg.overworld = cfg;
        int detour = ResourceDetour.coalNeed(ctx);
        int ingots = KitPlanner.ingotsNeeded(f, cfg);
        int loads = KitPlanner.smeltLoads(f, cfg);
        int withSpoken = DetourSpec.coalNeed(ingots, 0, 0, CookGate.raw(f), w.burnableWoodSmelts(w.smokerLogs()), loads);
        assertEquals(DetourSpec.coalNeed(ingots, 0, 0, CookGate.raw(f), w.burnableWoodSmelts(0), loads), detour);
        // known wrong: the cook's own count is the lower one, so it needs more coal than the detour goes for
        assertNotEquals(withSpoken, detour);
        assertEquals(5, detour);
        assertEquals(6, withSpoken);
    }

    // (c) the log stock-up asks for logs in the wait for the iron, and the wood budget has no use for them (the kit is made), so
    // the reserve calls every one of them spare and the furnace is free to burn what the stock-up chopped
    @Test
    public void theLogStockUpChopsForLogsTheFurnaceMayBurn() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 3).give(Items.WHITE_BED, 8);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(0, w.gatherLogsOwed());
        assertEquals(new WoodReserve.Keep(0, 0), w.keep());
        // known wrong: it wants a pile of 8, none of it spoken for
        assertEquals(8, w.stockUpLogs(8, true));
        assertEquals(3, w.spareLogs());
        assertEquals(4, w.burnableWoodSmelts(0));
    }

    // (d) GatherPhase.isDone and IronPhase's build their own FoodPlan, at the kit's minimum with the latch from the state, and
    // not the tick's latched ctx.food(). mid trip the two disagree about the cook's logs (and about the food need), so isDone says
    // the phase is finished while the plan it just walked still has a chop in it
    @Test
    public void isDoneAndThePlanDisagreeAboutTheGatherAndTheFood() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 3).give(Items.WHITE_BED, 8).give(Items.COBBLESTONE, 30);
        f.foodUnits = 100;
        GamerConfig gc = new GamerConfig();
        // a food trip is on (state.foodRefilling), the bag is past the minimum but not at the trip's stop
        FoodPlan tick = FoodPlan.of(f, gc, GamerPhase.GATHER, true);
        FoodPlan done = FoodPlan.of(f, gc, GamerPhase.GATHER, true).atMinimum();
        assertTrue(tick.wantsRefill());
        assertFalse(done.wantsRefill());
        List<KitNeed> plan = KitPlanner.gather(f, gc.overworld, BEDS, tick);
        List<KitNeed> isDone = KitPlanner.gather(f, gc.overworld, BEDS, done);
        // known wrong: one says 14 logs to chop for the cook and food to hunt, the other says nothing is left
        assertEquals(List.of(new KitNeed("log", 14), new KitNeed(KitNeed.FOOD, 130)), plan);
        assertTrue(isDone.isEmpty());

        // the same in IRON: the plan hunts to the trip's stop, isDone's minimum is already met
        FoodPlan ironTick = FoodPlan.of(f, gc, GamerPhase.IRON, true);
        FoodPlan ironDone = ironTick.atMinimum();
        assertTrue(KitPlanner.plan(f, gc.overworld, BEDS, ironTick).contains(new KitNeed(KitNeed.FOOD, 130)));
        assertFalse(KitPlanner.plan(f, gc.overworld, BEDS, ironDone).contains(new KitNeed(KitNeed.FOOD, 130)));
    }

    // (e, found on the way) the cook gate's count of wood a furnace may burn takes the reserve off the burnable logs only, but
    // FuelPolicy hands the same reserve out by item name over every log, burnable or not. with a species the furnace won't touch
    // in the bag (a crimson stem) the reserve lands on it, the oak stays fully burnable, and the gate undercounts the fuel
    @Test
    public void theReserveLandsOnLogsNothingBurnsSoTheGateUndercountsTheFuel() {
        FakeFacts f = madeEverything().give(Items.CRIMSON_STEM, 10).give(Items.OAK_LOG, 12);
        f.unburnable.add(Items.CRIMSON_STEM);
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(7, 0), w.keep());
        // 12 burnable logs less the 7 kept: 5 logs, 7 smelts
        assertEquals(7, w.burnableWoodSmelts(0));
        WoodReserve.apply(w);
        int[] usable = FuelPolicy.usable(List.of(new ItemStack(Items.CRIMSON_STEM, 10), new ItemStack(Items.OAK_LOG, 12)));
        // known wrong: the furnace may burn all 12 oak (18 smelts), the 7 kept were taken from the stems
        assertEquals(3, usable[0]);
        assertEquals(12, usable[1]);
    }

    // (f, found by the verifier) outside the overworld the furnaces are told to keep every log, but the smelts the coal detour and the
    // cook gate count still take the planner's (smaller) keep off, so they count wood as fuel that FuelPolicy will not let burn
    @Test
    public void downInTheNetherTheCountedFuelIsWoodTheFurnaceMayNotBurn() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 12);
        f.dimension = baritone.api.utils.Dimension.NETHER;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(12, 0), w.fuelKeep());
        // known wrong: 5 logs spare by the planner's keep of 7, 7 smelts counted, none of it burnable
        assertEquals(new WoodReserve.Keep(7, 0), w.keep());
        assertEquals(7, w.burnableWoodSmelts(0));
        WoodReserve.apply(w);
        assertEquals(0, FuelPolicy.usable(List.of(new ItemStack(Items.OAK_LOG, 12)))[0]);
    }
}
