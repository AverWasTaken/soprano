package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FuelPolicy;
import adris.altoclef.util.helpers.ItemHelper;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

// where the wood views disagreed with each other. a test with a "known wrong" line in it still pins what the code answers today and
// is waiting on its behaviour round (play-tested on its own), the fix is meant to flip those assertions. the ones without are
// fixed, and pin the right answer. nothing here is a feature, don't "fix" a test to make the code look right
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

    private static FoodPlan calm(FakeFacts f, OverworldConfig cfg, WoodBudget w) {
        EndConfig end = new EndConfig();
        end.beds = BEDS;
        return FoodPlan.of(f, cfg, end, true, false, w);
    }

    private static int logNeed(List<KitNeed> plan) {
        return plan.stream().filter(n -> n.catalogueName().equals("log")).mapToInt(KitNeed::count).findFirst().orElse(-1);
    }

    // (a) the cook outranks the beds in the IRON phase. raw meat is waiting, the smoker is still to be made and the logs held are
    // the beds' on the gather's books, which IRON never chops for: the smoker used to starve (the station said smoker, the gate said
    // no fuel, nothing chopped). now the cook's claim is on the logs first, and the plan chops what is short of it
    @Test
    public void theIronCookHasTheLogsItNeedsBeforeTheBeds() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 5).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, w.ironLogsOwed());
        // the beds are still the gather's debt, that view does not move
        assertEquals(2, w.gatherLogsOwed());
        assertEquals(new WoodReserve.Keep(0, 0), w.kitClaim());
        // a smoker's four (the table is held) and four logs to burn five smelts, nothing speaks for the logs but the cook
        assertEquals(4, w.smokerClaim());
        assertEquals(4, w.fuelClaim());
        assertEquals(new WoodReserve.Keep(0, 0), w.keep());
        assertEquals(new WoodReserve.Keep(0, 0), w.bedClaim());
        assertEquals(5, w.spareLogs());
        // five held, eight claimed: the plan chops the three
        assertEquals(3, w.cookChopLogs());
        assertEquals(3, w.ironChopLogs(f));
        assertEquals(8, logNeed(KitPlanner.plan(f, cfg, BEDS, calm(f, cfg, w), w)));
        // the station is the smoker and the gate waits for the fuel
        assertEquals(CookGate.Station.SMOKER, CookGate.station(f, cfg, true, w));
        assertNull(CookGate.need(f, cfg, BEDS, w));
        // with the three chopped it goes
        f.give(Items.OAK_LOG, 3);
        WoodBudget after = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, after.cookChopLogs());
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(f, cfg, BEDS, after).catalogueName());
        // and the logs the furnaces may not burn are still the kit's, here nothing
        assertEquals(new WoodReserve.Keep(0, 0), after.fuelKeep());
    }

    // the gather goes on keeping what it kept, and the cook only claims in the iron phase
    @Test
    public void theGatherStillKeepsEveryLogForTheBeds() {
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 5).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS);
        assertEquals(new WoodReserve.Keep(5, 0), w.keep());
        assertEquals(0, w.smokerClaim() + w.fuelClaim() + w.cookChopLogs());
        assertEquals(0, w.ironChopLogs(f));
        assertEquals(w, WoodBudget.of(f, cfg, BEDS, GamerPhase.GATHER));
    }

    // the cook is the second claim, the kit the first: when the kit still wants wood the cook is not fed from it, and the chop covers both
    @Test
    public void theKitIsFedBeforeTheCookAndTheChopCoversBoth() {
        // no iron pickaxe: the kit owes its two sticks, a plank each, which one log covers (and then the log is the kit's)
        FakeFacts f = madeEverything().give(Items.OAK_LOG, 1).give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
        f.items.remove(Items.IRON_PICKAXE);
        f.nearSurface = true;
        WoodBudget w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, w.ironLogsOwed());
        assertEquals(new WoodReserve.Keep(1, 0), w.kitClaim());
        // the one log we hold is the kit's, so the whole claim is short
        assertEquals(w.smokerClaim() + w.fuelClaim(), w.cookChopLogs());
        assertEquals(w.cookChopLogs(), w.ironChopLogs(f));
        // with none held the kit is short itself, and the chop is its log and the cook's claim, in one trip
        f.items.remove(Items.OAK_LOG);
        w = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(1, w.ironLogsOwed());
        assertEquals(new WoodReserve.Keep(0, 0), w.kitClaim());
        assertEquals(w.ironLogsOwed() + w.smokerClaim() + w.fuelClaim(), w.ironChopLogs(f));
        // ore in the bag ends the kit's chop, not the cook's: the cook near the surface still chops the lot
        f.give(Items.RAW_IRON, 3);
        assertEquals(w.ironLogsOwed() + w.cookChopLogs(), w.ironChopLogs(f));
        // down in the mine nothing is chopped for the cook, and the kit's chop is over once the ore is in
        f.nearSurface = false;
        WoodBudget deep = WoodBudget.of(f, cfg, BEDS, GamerPhase.IRON);
        assertEquals(0, deep.cookChopLogs());
        assertEquals(0, deep.ironChopLogs(f));
    }

    // the failure the first try at this had: a smoker only from logs spare after the reserve, so the smoker never got built. here the
    // bag is the one that failed (iron owed, raw meat waiting, no smoker, N logs) and the answer is never "station smoker, no fuel,
    // nothing to chop": either the cook goes with what is held, or the plan chops, and after that chop the cook goes
    @Test
    public void theIronCookIsNeverLeftWaitingOnLogsNothingChops() {
        for (boolean kitWantsWood : new boolean[]{false, true}) {
            for (int beds : new int[]{0, 3, 8}) {
                for (int logs = 0; logs <= 12; logs++) {
                    FakeFacts f = madeEverything().give(Items.BEEF, 5).give(Items.COBBLESTONE, 30);
                    if (kitWantsWood) {
                        f.items.remove(Items.IRON_PICKAXE);
                    }
                    f.nearSurface = true;
                    if (logs > 0) {
                        f.give(Items.OAK_LOG, logs);
                    }
                    String where = "kitWantsWood=" + kitWantsWood + " beds=" + beds + " logs=" + logs;
                    boolean cooked = false;
                    // two chops at most: the claim is sized to be met by one, a second would be a chase
                    for (int round = 0; round < 3 && !cooked; round++) {
                        WoodBudget w = WoodBudget.of(f, cfg, beds, GamerPhase.IRON);
                        assertTrue(where + ": iron is owed", CookGate.ironOwed(f, cfg));
                        if (CookGate.need(f, cfg, beds, w) != null) {
                            cooked = true;
                            break;
                        }
                        EndConfig end = new EndConfig();
                        end.beds = beds;
                        List<KitNeed> plan = KitPlanner.plan(f, cfg, beds, FoodPlan.of(f, cfg, end, true, false, w), w);
                        int chop = logNeed(plan);
                        assertTrue(where + " round " + round + ": no cook and no chop in " + plan, chop > f.count(ItemHelper.LOG));
                        f.items.put(Items.OAK_LOG, chop);
                    }
                    assertTrue(where + ": still no cook after the chop", cooked);
                }
            }
        }
    }

    // (b) the coal detour used to count the wood a furnace could burn WITHOUT the logs a smoker still to make would eat, where the
    // cook need took those off, so the detour thought there was more fuel than the cook did and fetched a coal short. both read
    // CookGate.woodSmeltsAfterClaims now
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
        assertEquals(w.burnableWoodSmelts(w.smokerLogs()), CookGate.woodSmeltsAfterClaims(f, cfg, w));
        assertEquals(withSpoken, detour);
        // the cook's own count is the lower one and the detour goes by it: one more coal than before
        assertNotEquals(DetourSpec.coalNeed(ingots, 0, 0, CookGate.raw(f), w.burnableWoodSmelts(0), loads), detour);
        assertEquals(6, detour);
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
