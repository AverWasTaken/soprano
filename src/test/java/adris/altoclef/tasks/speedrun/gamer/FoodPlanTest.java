package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.util.helpers.FoodHelper;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoodPlanTest {
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 70);

    private OverworldConfig cfg;
    private EndConfig end;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        end = new EndConfig();
    }

    // a plan with exactly this held and this much raw meat left out of it, no facts needed
    private FoodPlan held(int held, int rawLeftOut) {
        return new FoodPlan(held + rawLeftOut, 0, rawLeftOut, 0, 0, 0, cfg, end);
    }

    private FoodPlan plan(FakeFacts f, int beds) {
        return FoodPlan.ofBeds(f, cfg, beds);
    }

    private FoodPlan plan(FakeFacts f) {
        return FoodPlan.of(f, cfg, end);
    }

    // ---- the valuation

    @Test
    public void rawMeatCountsAtItsCookedValue() {
        // an apple and six raw mutton: raw that is 4 + 12 = 16, cooked it is 4 + 36 = 40
        assertEquals(6, FoodHelper.plannedNutrition(Items.MUTTON));
        assertEquals(6, FoodHelper.plannedNutrition(Items.COOKED_MUTTON));
        assertEquals(4, FoodHelper.plannedNutrition(Items.APPLE));
        assertEquals(4 + 6 * 6, FoodHelper.plannedNutrition(Items.APPLE) + 6 * FoodHelper.plannedNutrition(Items.MUTTON));
        for (Item[] pair : new Item[][]{{Items.PORKCHOP, Items.COOKED_PORKCHOP}, {Items.BEEF, Items.COOKED_BEEF},
                {Items.CHICKEN, Items.COOKED_CHICKEN}, {Items.RABBIT, Items.COOKED_RABBIT}, {Items.COD, Items.COOKED_COD},
                {Items.SALMON, Items.COOKED_SALMON}}) {
            assertEquals(pair[0].toString(), pair[1].components().get(DataComponents.FOOD).nutrition(), FoodHelper.plannedNutrition(pair[0]));
            assertTrue(pair[0].toString(), FoodHelper.plannedNutrition(pair[0]) > pair[0].components().get(DataComponents.FOOD).nutrition());
        }
        // everything else is what it says on the tin, and a stick is not dinner
        assertEquals(Items.BREAD.components().get(DataComponents.FOOD).nutrition(), FoodHelper.plannedNutrition(Items.BREAD));
        assertEquals(0, FoodHelper.plannedNutrition(Items.STICK));
    }

    private static FakeFacts tenRawPork() {
        FakeFacts f = new FakeFacts();
        // what MinecraftFacts books: ten porkchop at the cooked 8 each
        f.give(Items.PORKCHOP, 10);
        f.foodUnits = 80;
        return f;
    }

    @Test
    public void rawGapIsTheCookedValueMinusTheRawOne() {
        assertEquals(10 * (8 - 3), CookGate.rawGap(tenRawPork()));
        assertEquals(0, CookGate.rawGap(new FakeFacts().give(Items.COOKED_PORKCHOP, 10)));
        assertEquals(3, FoodHelper.ownNutrition(Items.PORKCHOP));
        assertEquals(0, FoodHelper.ownNutrition(Items.STICK));
    }

    @Test
    public void rawMeatWithNoWayToCookItCountsAtItsRawValue() {
        // no furnace, no smoker, no fuel, no cobble: ten porkchop get eaten raw, 30 units, and the kit hunts
        FakeFacts f = tenRawPork();
        assertFalse(CookGate.cookFeasible(f, cfg, 10));
        assertEquals(30, plan(f, 10).held());
        assertEquals(50, plan(f, 10).rawLeftOut());
        assertEquals(new KitNeed(KitNeed.FOOD, cfg.minFoodUnits), KitPlanner.gather(f, cfg, 10, plan(f, 10)).stream()
                .filter(n -> n.catalogueName().equals(KitNeed.FOOD)).findFirst().orElse(null));
    }

    @Test
    public void rawMeatWithAStationAndFuelCountsAtItsCookedValue() {
        FakeFacts f = tenRawPork();
        f.smokerPlaced = true;
        f.give(Items.COAL, 4);
        assertTrue(CookGate.cookFeasible(f, cfg, 10));
        assertEquals(80, plan(f, 10).held());
        // a station with no fuel to burn is no way to cook it
        FakeFacts dry = tenRawPork();
        dry.smokerPlaced = true;
        assertEquals(30, plan(dry, 10).held());
        // and a cook that backed off is not one either
        f.cookSuspended = true;
        assertEquals(30, plan(f, 10).held());
    }

    @Test
    public void aCookInProgressKeepsTheCookedValue() {
        // loading right now (the meat is half in the slot) or a smoker already on a batch: the cook is happening
        FakeFacts loading = tenRawPork();
        loading.cookStation = "smoker";
        assertEquals(80, plan(loading, 10).held());
        FakeFacts cooking = tenRawPork();
        cooking.cookingFood("cooked_porkchop", 4, 8, 20);
        assertEquals(80 + 32, plan(cooking, 10).held());
        assertEquals(32, plan(cooking, 10).pending());
    }

    @Test
    public void foodWithNoRawMeatIsTheSumItAlwaysWas() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 50;
        assertEquals(50, plan(f, 10).held());
        f.cookingFood("cooked_mutton", 3, 6, 20);
        assertEquals(68, plan(f, 10).held());
        assertEquals(50, plan(f, 10).bag());
    }

    // ---- the lines, and the two things called minFoodUnits

    @Test
    public void theLinesAreTheConfigsAndTheEndFloorIsNotTheOverworldMinimum() {
        FoodPlan p = plan(new FakeFacts());
        assertEquals(cfg.minHeldFoodUnits, p.floor());
        assertEquals(cfg.minFoodUnits, p.overworldMinimum());
        assertEquals(cfg.targetFoodUnits, p.target());
        assertEquals(end.minFoodUnits, p.endFloor());
        // the two configs share a field name and not a number, and neither leaks into the other
        assertEquals(70, p.overworldMinimum());
        assertEquals(24, p.endFloor());
        cfg.minFoodUnits = 90;
        end.minFoodUnits = 50;
        FoodPlan moved = plan(new FakeFacts());
        assertEquals(90, moved.overworldMinimum());
        assertEquals(50, moved.endFloor());
        assertEquals(cfg.minHeldFoodUnits, moved.floor());
    }

    @Test
    public void aBagBetweenTheTwoMinimumsIsShortForOneAndFineForTheOther() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        FoodPlan p = plan(f);
        assertTrue(p.shortOfMinimum());
        assertFalse(p.shortOfEndFloor());
        end.minFoodUnits = 50;
        assertTrue(plan(f).shortOfEndFloor());
    }

    @Test
    public void bandsAreTheTwoOverworldLines() {
        assertEquals(0, held(23, 0).band());
        assertEquals(1, held(24, 0).band());
        assertEquals(1, held(69, 0).band());
        assertEquals(2, held(70, 0).band());
    }

    @Test
    public void collectAsksForTheJunkOnTop() {
        FakeFacts f = new FakeFacts();
        f.junkFoodUnits = 12;
        FoodPlan p = plan(f);
        assertEquals(70 + 12, p.collect(70));
        assertEquals(24 + FoodPlan.END_MARGIN + 12, p.endCollect());
        // the End hunt is the same junk rule the kit runner uses, only with its own line and margin
        assertEquals(p.collect(end.minFoodUnits + 8), p.endCollect());
        assertEquals(70 + 12, KitRunner.foodTarget(FOOD, p));
        assertEquals(32, KitRunner.foodTarget(new KitNeed(KitNeed.BUILD_BLOCKS, 32), p));
    }

    // ---- the verdicts

    @Test
    public void miningWithEnoughInTheBagKeepsMining() {
        // 40 units and a vein in front of us: no trip
        assertFalse(held(40, 0).leads(false, false, false));
        assertFalse(held(24, 0).leads(false, false, false));
    }

    @Test
    public void underTheFloorFoodAlwaysGoesFirst() {
        assertTrue(held(23, 0).leads(false, false, false));
        assertTrue(held(0, 0).leads(false, false, false));
    }

    @Test
    public void onlyTheSurfaceMakesTheSoftTopUpCheap() {
        assertTrue(held(40, 0).leads(true, false, false));
        // a cook or a craft next in a mine used to count as "between jobs" (a smoker deep down and then a cow hunt through the
        // rock). down there the hunt is a climb whatever the next job is
        assertFalse(held(40, 0).leads(false, false, false));
    }

    @Test
    public void aCookThatIsLoadingIsNotCutShortBySoftTopUp() {
        FoodPlan p = held(40, 0);
        assertFalse(p.leads(true, false, true));
        // not even one that already started, it picks up again when the cook lets go
        assertFalse(p.leads(true, true, true));
        assertTrue(p.leads(true, true, false));
        // but a bag that is really empty is still an emergency
        assertTrue(held(10, 0).leads(false, false, true));
        // and the top-up that was latched does not start from nothing because the cook blinked
        assertFalse(p.nextTopUp(false, p.leads(true, false, true)));
        assertTrue(p.nextTopUp(true, p.leads(true, true, true)));
    }

    @Test
    public void atTheFullAmountItNeverLeads() {
        assertFalse(held(70, 0).leads(true, true, false));
        assertFalse(held(100, 0).leads(true, false, false));
    }

    @Test
    public void aTopUpThatStartedRunsToTheTopWithoutFlipping() {
        int count = 40;
        boolean topUp = false;
        // down the mine: nothing
        boolean lead = held(count, 0).leads(false, topUp, false);
        topUp = held(count, 0).nextTopUp(topUp, lead);
        assertFalse(lead);
        assertFalse(topUp);
        // up on the surface it starts
        lead = held(count, 0).leads(true, topUp, false);
        topUp = held(count, 0).nextTopUp(topUp, lead);
        assertTrue(lead);
        assertTrue(topUp);
        // the hunt takes it under ground or into a ravine, and the count climbs: still leading, every tick
        for (count = 41; count < 70; count += 7) {
            lead = held(count, 0).leads(false, topUp, false);
            topUp = held(count, 0).nextTopUp(topUp, lead);
            assertTrue("at " + count, lead);
            assertTrue(topUp);
        }
        // 70 and it is over, and the next dip starts from scratch
        lead = held(70, 0).leads(false, topUp, false);
        topUp = held(70, 0).nextTopUp(topUp, lead);
        assertFalse(lead);
        assertFalse(topUp);
        assertFalse(held(60, 0).leads(false, topUp, false));
    }

    @Test
    public void aForcedTripUnderTheFloorDoesNotLatchTheSoftOne() {
        boolean lead = held(10, 0).leads(false, false, false);
        assertTrue(lead);
        assertFalse(held(10, 0).nextTopUp(false, lead));
        // and once it is back over the floor in the mine, the mine wins again
        assertFalse(held(25, 0).leads(false, false, false));
    }

    @Test
    public void theFoodFloorStartsLowAndRunsToTheFullAmount() {
        assertFalse(held(40, 0).floorNext(false));
        assertTrue(held(cfg.minHeldFoodUnits - 1, 0).floorNext(false));
        // once started it carries on past the floor, up to the minimum
        assertTrue(held(40, 0).floorNext(true));
        assertFalse(held(cfg.minFoodUnits, 0).floorNext(true));
    }

    @Test
    public void theFoodFloorIgnoresWhatTheRawMeatWouldBeWorthCooked() {
        // LOCATE and the like never cook, so the part of the raw meat that held leaves out is not coming: it keeps hunting at 66
        // where the IRON phase, which does cook, calls the same bag covered
        assertTrue(held(66, 4).floorNext(true));
        assertTrue(held(66, 4).covered());
        assertFalse(held(66, 4).leads(true, true, false));
    }

    // ---- a small shortfall the raw meat in the bag covers is not worth a trip (one raw chicken, 4 units)

    // 64 units of other food and one raw chicken. foodUnits books the chicken at the cooked 6, so 70 in the sum
    private static FakeFacts chickenBag(boolean smokerNearby) {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 64 + FoodHelper.plannedNutrition(Items.CHICKEN);
        f.give(Items.CHICKEN, 1);
        if (smokerNearby) {
            f.smokerPlaced = true;
            f.give(Items.COAL, 1);
        }
        return f;
    }

    // the soft rule as IronPhase asks it, standing on the surface with nobody else's load going
    private boolean softLeads(FakeFacts f, boolean topUp) {
        return plan(f, 10).leads(true, topUp, false);
    }

    @Test
    public void aSmallGapTheRawMeatCoversDoesNotLead() {
        // 66 held, the chicken's 4 not in it: cooked it is 70, so no trip, latched top-up or not
        assertFalse(held(66, 4).leads(true, false, false));
        assertFalse(held(66, 4).leads(true, true, false));
        // and it does not start the latch either
        assertFalse(held(66, 4).nextTopUp(false, held(66, 4).leads(true, false, false)));
        // without the raw meat to cover it the very same 66 is a real top-up
        assertTrue(held(66, 0).leads(true, false, false));
        assertTrue(held(66, 0).nextTopUp(false, held(66, 0).leads(true, false, false)));
    }

    @Test
    public void theSmokerComingAndGoingDoesNotStartOrStopATopUp() {
        // the smoker is picked up, placed again and forgotten while the bag stays the same: the verdict must not follow it
        FakeFacts nearby = chickenBag(true);
        FakeFacts gone = chickenBag(false);
        assertTrue(CookGate.cookFeasible(nearby, cfg, 10));
        assertFalse(CookGate.cookFeasible(gone, cfg, 10));
        assertEquals(70, plan(nearby, 10).held());
        assertEquals(66, plan(gone, 10).held());
        boolean topUp = false;
        for (FakeFacts f : new FakeFacts[]{nearby, gone, nearby, gone, gone, nearby}) {
            FoodPlan p = plan(f, 10);
            boolean lead = p.leads(true, topUp, false);
            topUp = p.nextTopUp(topUp, lead);
            assertFalse(lead);
            assertFalse(topUp);
        }
    }

    @Test
    public void twoRawBeefAndTheSmokerComingAndGoingDoNotChurnEither() {
        // under MIN_RAW the cook is feasible only with a smoker standing: that is the biggest swing the smoker can make, 2 x 5
        for (Item meat : new Item[]{Items.BEEF, Items.PORKCHOP}) {
            FakeFacts nearby = new FakeFacts();
            nearby.foodUnits = 54 + 2 * FoodHelper.plannedNutrition(meat);
            nearby.give(meat, 2).give(Items.COAL, 1);
            nearby.smokerPlaced = true;
            FakeFacts gone = new FakeFacts();
            gone.foodUnits = nearby.foodUnits;
            gone.give(meat, 2).give(Items.COAL, 1);
            assertEquals(70, plan(nearby, 10).held());
            assertEquals(60, plan(gone, 10).held());
            assertFalse(softLeads(nearby, false));
            assertFalse(softLeads(gone, false));
        }
        // the pile above is the biggest one under MIN_RAW, a bigger MIN_RAW wants a bigger pile here and a bigger SMALL_GAP
        assertEquals(2, CookGate.MIN_RAW - 1);
    }

    @Test
    public void rawMeatThatIsAlreadyCountedIsNotPaidTwice() {
        // a cook is feasible, so the sum has the chicken at 6 already. 62 other units and the chicken is a real 68: a top-up.
        // adding the chicken's gap on top would read 72 and call it covered
        FakeFacts f = chickenBag(true);
        f.foodUnits = 62 + FoodHelper.plannedNutrition(Items.CHICKEN);
        assertEquals(68, plan(f, 10).held());
        assertEquals(0, plan(f, 10).rawLeftOut());
        assertFalse(plan(f, 10).covered());
        assertTrue(softLeads(f, false));
        // no cook possible: the same chicken is not counted, and left out it is the gap
        f.smokerPlaced = false;
        assertEquals(64, plan(f, 10).held());
        assertEquals(4, plan(f, 10).rawLeftOut());
        // 64 + 4 is 68, still 2 short of 70: the chicken does not close that hole
        assertFalse(held(64, 4).covered());
        assertTrue(softLeads(f, false));
    }

    @Test
    public void aBigGapOfRawMeatStillGetsAHunt() {
        // ten raw porkchop and no way to cook them: 30 real units, 50 on paper. nowhere near a steak's worth of hole
        FakeFacts f = tenRawPork();
        assertEquals(30, plan(f, 10).held());
        assertEquals(50, plan(f, 10).rawLeftOut());
        assertTrue(softLeads(f, false));
        // the cap is SMALL_GAP: that far under is covered by any raw meat that fills it, one more unit is not
        int edge = cfg.minFoodUnits - FoodPlan.SMALL_GAP;
        assertTrue(held(edge, 50).covered());
        assertFalse(held(edge - 1, 50).covered());
        assertTrue(held(edge - 1, 50).leads(true, false, false));
        assertFalse(held(edge, 50).leads(true, false, false));
        // and a small hole that the meat only half fills is still a top-up
        assertTrue(held(66, 3).leads(true, false, false));
        assertFalse(held(66, 4).leads(true, false, false));
    }

    @Test
    public void underTheFloorLeadsWhateverTheBagHolds() {
        assertTrue(held(10, 100).leads(false, false, false));
        assertTrue(held(23, 50).leads(false, false, true));
        assertFalse(held(23, 100).covered());
        // the floor is not a small hole even when the config puts the two lines next to each other
        cfg.minHeldFoodUnits = 24;
        cfg.minFoodUnits = 28;
        assertTrue(held(23, 100).leads(false, false, false));
        assertFalse(held(24, 100).leads(true, false, false));
    }

    @Test
    public void aTopUpUnderWayEndsWhenTheRawMeatCoversTheRest() {
        // the hunt got us to 66 and the bag holds the chicken that fills the last 4: done, the latch is cleared too
        assertFalse(held(66, 4).leads(false, true, false));
        assertFalse(held(66, 4).nextTopUp(true, false));
        // and it stays cleared: with the meat eaten a top-up has to start over, on the surface, not wake up in the mine
        assertFalse(held(66, 0).leads(false, false, false));
        assertTrue(held(66, 0).leads(true, false, false));
        // far from covered, the latch carries on the way it always did
        assertTrue(held(50, 4).nextTopUp(true, true));
        assertTrue(held(50, 4).leads(false, true, false));
    }

    // ---- the top-up's own smoker screen is not somebody else's load

    @Test
    public void theTopUpDoesNotCutItselfOffWhenItsOwnScreenOpens() {
        // a top-up on the surface walks to a smoker for the cooked chicken, and the screen opens under it: that screen is its own
        FoodPlan p = held(40, 0);
        boolean led = false;
        boolean lead = p.leads(true, false, FoodGate.cookBusy(false, false, led));
        assertTrue(lead);
        led = lead;
        for (int tick = 0; tick < 5; tick++) {
            lead = p.leads(true, true, FoodGate.cookBusy(false, true, led));
            assertTrue("tick " + tick, lead);
            led = lead;
        }
        // an iron load that was already in flight when the food need came up still holds it back
        led = false;
        assertFalse(p.leads(true, false, FoodGate.cookBusy(false, true, led)));
    }

    // ---- the furnace screen we have open

    private static RunState.FurnaceJob foodJob(int x, int count) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", "smoker", "porkchop", count,
                "cooked_porkchop", 0, 1000);
        job.unitsEach = 8;
        return job;
    }

    @Test
    public void meatInTheOpenStationCountsUntilTheJobIsRecorded() {
        RunState.Pos screen = new RunState.Pos(5, 64, 0);
        // 42 held, 4 pork in the screen (32 planned): the dip that sent the bot after a cow is not a dip
        assertEquals(32, FoodPlan.inStation(32, List.of(), screen, "OVERWORLD"));
        // the job is in the state: the pending sum has it, counting it again would hide a real shortage
        assertEquals(0, FoodPlan.inStation(32, List.of(foodJob(5, 4)), screen, "OVERWORLD"));
        assertEquals(0, FoodPlan.inStation(0, List.of(), screen, "OVERWORLD"));
    }

    @Test
    public void aDifferentStationsJobDoesNotHideTheMeatBeingLoaded() {
        RunState.Pos screen = new RunState.Pos(9, 64, 0);
        // smoker A (x 5) is recorded and furnace B (x 9) is on screen with meat going in: B's meat still counts
        assertEquals(32, FoodPlan.inStation(32, List.of(foodJob(5, 4)), screen, "OVERWORLD"));
        // once B has its own job, it is the pending sum's and not counted twice
        assertEquals(0, FoodPlan.inStation(32, List.of(foodJob(5, 4), foodJob(9, 4)), screen, "OVERWORLD"));
        // same spot in another dimension is another station
        assertEquals(32, FoodPlan.inStation(32, List.of(foodJob(9, 4)), screen, "NETHER"));
        // an iron job on the very block is not food and hides nothing
        RunState.FurnaceJob iron = new RunState.FurnaceJob(new RunState.Pos(9, 64, 0), "OVERWORLD", "furnace", "raw_iron", 3,
                "iron_ingot", 0, 1000);
        assertEquals(32, FoodPlan.inStation(32, List.of(iron), screen, "OVERWORLD"));
        // not knowing which block the screen is falls back to the old rule: any food job hides it
        assertEquals(0, FoodPlan.inStation(32, List.of(foodJob(5, 4)), null, "OVERWORLD"));
        assertEquals(32, FoodPlan.inStation(32, List.of(), null, "OVERWORLD"));
    }

    private static final RunState.Pos SMOKER = new RunState.Pos(5, 64, 0);

    // one visit to a smoker replayed the way MinecraftFacts does it: the leftover is carried from tick to tick and the numbers go
    // through the real planner. iron is cooking in a furnace the whole time, so the stock-up fillers are in the schedule
    private final class Visit {
        final FakeFacts f = new FakeFacts();
        int leftover = -1;

        Visit() {
            f.cooking("iron_ingot", 3, 60);
        }

        void tick(int bag, int station, boolean synced, boolean foodTask) {
            leftover = FoodPlan.leftover(leftover, station, synced, foodTask);
            f.foodUnits = bag + FoodPlan.inStation(station, f.jobs, SMOKER, "OVERWORLD", leftover);
        }

        int held() {
            return plan(f, 8).held();
        }

        // the stock-up filler IronPhase's cooking tick would run (100 and the 30 of smeltExtras)
        boolean fillerOn() {
            return SmeltFiller.schedule(f, cfg, 8, true, plan(f, 8)).isStockUp(new KitNeed(KitNeed.FOOD, cfg.targetFoodUnits + 30));
        }

        // the minimum need, the one that would replace the filler mid load
        boolean minimumOn() {
            return FoodGate.index(KitPlanner.plan(f, cfg, 8, plan(f, 8)), plan(f, 8)) >= 0;
        }
    }

    @Test
    public void whatWasInTheScreenWhenTheFoodTaskGotThereStaysOut() {
        // the first look is the baseline, after that it only goes down
        assertEquals(24, FoodPlan.leftover(-1, 24, true, true));
        assertEquals(24, FoodPlan.leftover(24, 40, true, true));
        assertEquals(0, FoodPlan.leftover(24, 0, true, true));
        assertEquals(0, FoodPlan.leftover(0, 40, true, true));
        // no screen, slots not here yet, or somebody else's screen: nothing to leave out
        assertEquals(-1, FoodPlan.leftover(24, 24, false, true));
        assertEquals(-1, FoodPlan.leftover(-1, 0, false, true));
        assertEquals(-1, FoodPlan.leftover(24, 24, true, false));
        assertEquals(0, FoodPlan.inStation(24, List.of(), SMOKER, "OVERWORLD", 24));
        assertEquals(16, FoodPlan.inStation(40, List.of(), SMOKER, "OVERWORLD", 24));
        // -1 is no baseline, not a negative one
        assertEquals(24, FoodPlan.inStation(24, List.of(), SMOKER, "OVERWORLD", -1));
        assertEquals(24, FoodPlan.inStation(24, List.of(), SMOKER, "OVERWORLD"));
        // a recorded job on this block still hides all of it, and not knowing which block it is hides it for any food job
        assertEquals(0, FoodPlan.inStation(40, List.of(foodJob(5, 4)), SMOKER, "OVERWORLD", 24));
        assertEquals(0, FoodPlan.inStation(40, List.of(foodJob(5, 4)), null, "OVERWORLD", 0));
    }

    @Test
    public void somebodyElsesLoadStillCounts() {
        // a cook loading 72 units into the smoker: 72 held the whole way, neither food need wakes up
        Visit cook = new Visit();
        for (int moved = 0; moved <= 72; moved += 8) {
            cook.tick(72 - moved, moved, true, false);
            assertEquals("moved " + moved, 72, cook.held());
            assertFalse("moved " + moved, cook.minimumOn());
        }
        // and once its job is recorded the pending sum has it, not the screen
        cook.f.cookingFood("cooked_beef", 9, 8, 40);
        cook.tick(0, 72, true, false);
        assertEquals(72, cook.held());
    }

    @Test
    public void openingTheSmokerDoesNotEndTheNeedThatWalkedThere() {
        // 110 held, the filler asks for 130, and 24 units of cooked meat sit in a smoker no job knows about
        Visit v = new Visit();
        v.tick(110, 0, false, true);
        assertTrue(v.fillerOn());
        // the slots arrive a tick after the screen: an empty look is not the baseline
        v.tick(110, 0, false, true);
        assertTrue(v.fillerOn());
        v.tick(110, 24, true, true);
        assertEquals(110, v.held());
        assertTrue("the click must not end the need that opened the screen", v.fillerOn());
        v.tick(110, 24, true, true);
        assertTrue(v.fillerOn());
        // the output comes out and into the bag: honestly 134 now, ended, and the screen closing does not bring it back
        v.tick(134, 0, true, true);
        assertFalse(v.fillerOn());
        v.tick(134, 0, false, true);
        assertFalse(v.fillerOn());
        // what the same click did without the leftover rule: 134 on the click, the filler gone, and back on once the screen closed
        Visit old = new Visit();
        old.tick(110, 0, false, false);
        assertTrue(old.fillerOn());
        old.tick(110, 24, true, false);
        assertFalse(old.fillerOn());
        old.tick(110, 0, false, false);
        assertTrue(old.fillerOn());
    }

    @Test
    public void theFoodTaskLoadingItsOwnMeatDoesNotDip() {
        // 100 in the bag, 40 of it raw going into an empty smoker a stack at a time: the same 100 the whole way, so the minimum need
        // never shows up in front of the filler and the task is not swapped out with meat in the slots and no job
        Visit v = new Visit();
        v.tick(100, 0, true, true);
        for (int moved = 0; moved <= 40; moved += 8) {
            v.tick(100 - moved, moved, true, true);
            assertEquals("moved " + moved, 100, v.held());
            assertFalse("moved " + moved, v.minimumOn());
            assertTrue("moved " + moved, v.fillerOn());
        }
    }

    @Test
    public void aLoadByTheFoodTaskEndsCleanlyOnceTheJobIsRecorded() {
        // 100 in the bag, 40 of raw meat already in the smoker's input from an earlier visit nobody wrote down, 40 more going in
        Visit v = new Visit();
        v.tick(100, 40, true, true);
        assertEquals(100, v.held());
        v.tick(60, 80, true, true);
        assertEquals(100, v.held());
        assertTrue(v.fillerOn());
        // Loaded: the job covers the whole input, the leftover included (that meat is cooking for us now)
        v.f.cookingFood("cooked_beef", 10, 8, 50);
        assertEquals(80, v.f.pendingFoodUnits());
        for (boolean open : new boolean[]{true, true, false, false}) {
            v.tick(60, open ? 80 : 0, open, true);
            assertEquals(140, v.held());
            assertFalse(v.fillerOn());
        }
    }

    @Test
    public void loadingBeforeTakingTheOutputOnlyEverUndercounts() {
        // 24 cooked in the output, 16 more raw goes in first and then the output comes out. the baseline follows the station down, so
        // the 16 loaded is briefly not counted: the number never falls, it catches up when the job is recorded
        Visit v = new Visit();
        v.tick(100, 24, true, true);
        assertEquals(100, v.held());
        v.tick(84, 40, true, true);
        assertEquals(100, v.held());
        v.tick(108, 16, true, true);
        assertEquals(108, v.held());
        v.f.cookingFood("cooked_beef", 2, 8, 10);
        v.tick(108, 0, false, true);
        assertEquals(124, v.held());
    }

    // ---- one plan, every gate

    @Test
    public void everyFoodGateReadsTheSameNumberOnTheClick() {
        // all of them go through the one plan, so the click moves none of them. spelled out for the ones that decide something
        Visit v = new Visit();
        v.tick(60, 0, false, true);
        int before = v.held();
        v.tick(60, 24, true, true);
        assertEquals(before, v.held());
        assertEquals(60, KitPlanner.progressOf(v.f, FOOD, plan(v.f, 8)));
        // the min need is in the plan, the floor and the soft top-up read the same held, and the portal's need is the same sum
        assertTrue(v.minimumOn());
        assertTrue(plan(v.f, 8).leads(true, true, FoodGate.cookBusy(false, true, true)));
        assertTrue(PortalPlanner.gate(v.f, cfg, 8, plan(v.f, 8)).stream().anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
    }

    // a bag, and whether each gate says the food is short. every row is one bag read by all of them
    private void assertGatesAgree(String bag, FakeFacts f, boolean shortOfMinimum, boolean shortOfEnd) {
        FoodPlan p = plan(f);
        assertEquals(bag + " (plan)", shortOfMinimum, p.shortOfMinimum());
        assertEquals(bag + " (kit)", shortOfMinimum, KitPlanner.plan(f, cfg, end.beds, p).stream()
                .anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName()) && n.count() == cfg.minFoodUnits));
        assertEquals(bag + " (gather)", shortOfMinimum, KitPlanner.gather(f, cfg, end.beds, p).stream()
                .anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
        assertEquals(bag + " (portal)", shortOfMinimum, PortalPlanner.gate(f, cfg, end.beds, p).stream()
                .anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
        assertEquals(bag + " (end)", shortOfEnd, EndGear.missing(f, new RunState(), end, 0, p).food());
    }

    @Test
    public void theKitThePortalAndTheEndAllSeeTheSameBag() {
        FakeFacts empty = new FakeFacts();
        assertGatesAgree("empty", empty, true, true);

        FakeFacts mid = new FakeFacts();
        mid.foodUnits = 40;
        assertGatesAgree("40 cooked units", mid, true, false);

        FakeFacts full = new FakeFacts();
        full.foodUnits = 90;
        assertGatesAgree("90 cooked units", full, false, false);

        // five raw porkchop with nothing to cook them on: 40 on paper, 15 to eat. short everywhere, the End included
        FakeFacts rawNoCook = new FakeFacts();
        rawNoCook.give(Items.PORKCHOP, 5);
        rawNoCook.foodUnits = 5 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(15, plan(rawNoCook).held());
        assertGatesAgree("5 raw porkchop, no cook", rawNoCook, true, true);

        // the same five with a smoker standing and coal to burn: they are 40 and the cook turns them into 40
        FakeFacts rawCook = new FakeFacts();
        rawCook.give(Items.PORKCHOP, 5).give(Items.COAL, 2);
        rawCook.smokerPlaced = true;
        rawCook.foodUnits = 5 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(40, plan(rawCook).held());
        assertGatesAgree("5 raw porkchop, smoker standing", rawCook, true, false);
    }

    // ---- where the gates used to disagree, and what they say now

    @Test
    public void theEndGateNoLongerCountsRawMeatNothingCanCook() {
        // it read the bare bag: three raw porkchop is 24 on paper and the End gate was happy, the food chain eats them at 3 apiece
        FakeFacts f = new FakeFacts();
        f.give(Items.PORKCHOP, 3);
        f.foodUnits = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(24, f.foodUnits);
        assertFalse("the old read", f.foodUnits() < end.minFoodUnits);
        assertEquals(9, plan(f).held());
        assertTrue(EndGear.missing(f, new RunState(), end, 0, plan(f)).food());
        // with a smoker standing and fuel the planner can cook them, so the same bag is worth its cooked 24 here as in every other
        // gate (END_PREP does not run the cook itself, held is not phase aware)
        f.smokerPlaced = true;
        f.give(Items.COAL, 1);
        assertEquals(24, plan(f).held());
        assertFalse(EndGear.missing(f, new RunState(), end, 0, plan(f)).food());
    }

    @Test
    public void theEndGateCountsABatchTheSmokerIsCookingForUs() {
        // empty bag, 4 pork in the smoker (32 units): every other gate calls that held, the End one only saw the bag
        FakeFacts f = new FakeFacts();
        f.cookingFood("cooked_porkchop", 4, 8, 20);
        assertEquals(0, f.foodUnits());
        assertEquals(32, plan(f).held());
        assertTrue("the old read", f.foodUnits() < end.minFoodUnits);
        assertFalse(EndGear.missing(f, new RunState(), end, 0, plan(f)).food());
        // the kit and the portal gate are short of 70 with it, as they always were
        assertTrue(plan(f).shortOfMinimum());
    }

    @Test
    public void theEndHuntAndTheKitHuntAskForTheJunkTheSameWay() {
        // the End phase copied the junk fix instead of sharing it: both go through FoodPlan.collect now
        FakeFacts f = new FakeFacts();
        f.junkFoodUnits = 17;
        FoodPlan p = plan(f);
        assertEquals(KitRunner.foodTarget(new KitNeed(KitNeed.FOOD, end.minFoodUnits + 8), p), p.endCollect());
        f.junkFoodUnits = 0;
        assertEquals(end.minFoodUnits + 8, plan(f).endCollect());
    }

    @Test
    public void progressIsThePlainCountAndDoesNotFollowTheSmoker() {
        // held moves by the chicken's 4 when the smoker comes and goes, progress must not: it would be a stall timer reset for nothing
        FakeFacts nearby = chickenBag(true);
        FakeFacts gone = chickenBag(false);
        assertEquals(70, plan(nearby, 10).held());
        assertEquals(66, plan(gone, 10).held());
        assertEquals(KitPlanner.progressOf(nearby, FOOD, plan(nearby, 10)), KitPlanner.progressOf(gone, FOOD, plan(gone, 10)));
        assertEquals(70, plan(gone, 10).counted());
        // a batch going into the smoker is progress: the pending food is in it
        gone.cookingFood("cooked_mutton", 3, 6, 20);
        assertEquals(70 + 18, KitPlanner.progressOf(gone, FOOD, plan(gone, 10)));
    }

    @Test
    public void theCookFuelGateAndTheMinimumNeedAgreeOnABagWithNoRawMeat() {
        // cookFuelLogs used to add the bag and the pending food itself: with no raw meat that is exactly held
        for (int units : new int[]{0, 40, 69, 70, 120}) {
            FakeFacts f = new FakeFacts();
            f.foodUnits = units;
            FoodPlan p = plan(f, 8);
            assertEquals(units, p.held());
            assertEquals(units + " units", p.shortOfMinimum() ? cfg.cookFuelLogs : 0, KitPlanner.cookFuelLogs(f, cfg, 8, 0, p));
        }
    }

    // ---- the log line

    @Test
    public void theFoodLineSaysWhatTheNumbersWere() {
        // station 24 of the bag's 134 (110 + 24), nothing pending, nothing left out
        FoodPlan on = new FoodPlan(134, 0, 0, 0, 24, 0, cfg, end);
        assertEquals("food: need off, held 134 of 70 (station 24, pending 0, raw left out 0), running wool", on.line(false, 70, "wool"));
        FoodPlan left = new FoodPlan(116, 0, 6, 0, 0, 24, cfg, end);
        assertEquals("food: need on, held 110 of 130 (station 0, pending 0, raw left out 6), running food, "
                + "24 was already in the open screen, not counted", left.line(true, 130, "food"));
        assertTrue(held(0, 0).line(true, 70, null).endsWith("running nothing"));
    }
}
