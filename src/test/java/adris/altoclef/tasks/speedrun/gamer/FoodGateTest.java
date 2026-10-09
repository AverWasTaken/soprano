package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
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

public class FoodGateTest {
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 70);
    private static final KitNeed ORE = new KitNeed("iron_ingot", 39);
    private static final KitNeed CRAFT = new KitNeed("iron_pickaxe", 1);

    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
    }

    @Test
    public void rawMeatCountsAtItsCookedValue() {
        // the 14:42 bag: an apple and six raw mutton. raw that is 4 + 12 = 16, cooked it is 4 + 36 = 40
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

    @Test
    public void onlyTheKitsOwnMinimumFoodIsTheGatedOne() {
        List<KitNeed> plan = List.of(CRAFT, FOOD, ORE, new KitNeed(KitNeed.FOOD, 100), new KitNeed(KitNeed.FOOD, 130));
        assertEquals(1, FoodGate.index(plan, cfg));
        // the target and the stock-up are not it, and neither is a plan with no food at all
        assertEquals(-1, FoodGate.index(List.of(ORE, new KitNeed(KitNeed.FOOD, 100)), cfg));
        assertEquals(-1, FoodGate.index(List.of(), cfg));
    }

    @Test
    public void theHeadIsOreMeansTheNextJobIsTheMine() {
        assertTrue(FoodGate.headIsOre(List.of(FOOD, ORE), 0));
        assertFalse(FoodGate.headIsOre(List.of(FOOD, CRAFT, ORE), 0));
        assertFalse(FoodGate.headIsOre(List.of(FOOD), 0));
        // food further back: what runs first without it is whatever is in front
        assertFalse(FoodGate.headIsOre(List.of(CRAFT, FOOD, ORE), 1));
    }

    @Test
    public void miningWithEnoughInTheBagKeepsMining() {
        // 40 units and a vein in front of us: no trip
        assertFalse(FoodGate.leads(40, 0, cfg, false, false, false));
        assertFalse(FoodGate.leads(24, 0, cfg, false, false, false));
    }

    @Test
    public void underTheFloorFoodAlwaysGoesFirst() {
        assertTrue(FoodGate.leads(23, 0, cfg, false, false, false));
        assertTrue(FoodGate.leads(0, 0, cfg, false, false, false));
    }

    @Test
    public void onlyTheSurfaceMakesTheSoftTopUpCheap() {
        assertTrue(FoodGate.leads(40, 0, cfg, true, false, false));
        // a cook or a craft next in a mine used to count as "between jobs" (22:08, a smoker at y=32 and then a cow hunt through
        // the rock). down there the hunt is a climb whatever the next job is
        assertFalse(FoodGate.leads(40, 0, cfg, false, false, false));
    }

    @Test
    public void aCookThatIsLoadingIsNotCutShortBySoftTopUp() {
        assertFalse(FoodGate.leads(40, 0, cfg, true, false, true));
        // not even one that already started, it picks up again when the cook lets go
        assertFalse(FoodGate.leads(40, 0, cfg, true, true, true));
        assertTrue(FoodGate.leads(40, 0, cfg, true, true, false));
        // but a bag that is really empty is still an emergency
        assertTrue(FoodGate.leads(10, 0, cfg, false, false, true));
        // and the top-up that was latched does not start from nothing because the cook blinked
        assertFalse(FoodGate.nextTopUp(false, 40, 0, cfg, FoodGate.leads(40, 0, cfg, true, false, true)));
        assertTrue(FoodGate.nextTopUp(true, 40, 0, cfg, FoodGate.leads(40, 0, cfg, true, true, true)));
    }

    @Test
    public void meatInTheOpenStationCountsUntilTheJobIsRecorded() {
        RunState.Pos screen = new RunState.Pos(5, 64, 0);
        // 42 held, 4 pork in the screen (32 planned): the dip that sent the bot after a cow is not a dip
        assertEquals(32, FoodGate.inStation(32, List.of(), screen, "OVERWORLD"));
        // the job is in the state: the pending sum has it, counting it again would hide a real shortage
        assertEquals(0, FoodGate.inStation(32, List.of(foodJob(5, 4)), screen, "OVERWORLD"));
        assertEquals(0, FoodGate.inStation(0, List.of(), screen, "OVERWORLD"));
    }

    private static RunState.FurnaceJob foodJob(int x, int count) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(x, 64, 0), "OVERWORLD", "smoker", "porkchop", count,
                "cooked_porkchop", 0, 1000);
        job.unitsEach = 8;
        return job;
    }

    @Test
    public void aDifferentStationsJobDoesNotHideTheMeatBeingLoaded() {
        RunState.Pos screen = new RunState.Pos(9, 64, 0);
        // smoker A (x 5) is recorded and furnace B (x 9) is on screen with meat going in: B's meat still counts
        assertEquals(32, FoodGate.inStation(32, List.of(foodJob(5, 4)), screen, "OVERWORLD"));
        // once B has its own job, it is the pending sum's and not counted twice
        assertEquals(0, FoodGate.inStation(32, List.of(foodJob(5, 4), foodJob(9, 4)), screen, "OVERWORLD"));
        // same spot in another dimension is another station
        assertEquals(32, FoodGate.inStation(32, List.of(foodJob(9, 4)), screen, "NETHER"));
        // an iron job on the very block is not food and hides nothing
        RunState.FurnaceJob iron = new RunState.FurnaceJob(new RunState.Pos(9, 64, 0), "OVERWORLD", "furnace", "raw_iron", 3,
                "iron_ingot", 0, 1000);
        assertEquals(32, FoodGate.inStation(32, List.of(iron), screen, "OVERWORLD"));
        // not knowing which block the screen is falls back to the old rule: any food job hides it
        assertEquals(0, FoodGate.inStation(32, List.of(foodJob(5, 4)), null, "OVERWORLD"));
        assertEquals(32, FoodGate.inStation(32, List.of(), null, "OVERWORLD"));
    }

    // ---- raw meat is only worth its cooked value while a cook can happen

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
        assertEquals(30, KitPlanner.foodHeld(f, cfg, 10));
        assertEquals(new KitNeed(KitNeed.FOOD, cfg.minFoodUnits), KitPlanner.gather(f, cfg, 10).stream()
                .filter(n -> n.catalogueName().equals(KitNeed.FOOD)).findFirst().orElse(null));
    }

    @Test
    public void rawMeatWithAStationAndFuelCountsAtItsCookedValue() {
        FakeFacts f = tenRawPork();
        f.smokerPlaced = true;
        f.give(Items.COAL, 4);
        assertTrue(CookGate.cookFeasible(f, cfg, 10));
        assertEquals(80, KitPlanner.foodHeld(f, cfg, 10));
        // a station with no fuel to burn is no way to cook it
        FakeFacts dry = tenRawPork();
        dry.smokerPlaced = true;
        assertEquals(30, KitPlanner.foodHeld(dry, cfg, 10));
        // and a cook that backed off is not one either
        f.cookSuspended = true;
        assertEquals(30, KitPlanner.foodHeld(f, cfg, 10));
    }

    @Test
    public void aCookInProgressKeepsTheCookedValue() {
        // loading right now (the meat is half in the slot) or a smoker already on a batch: the cook is happening
        FakeFacts loading = tenRawPork();
        loading.cookStation = "smoker";
        assertEquals(80, KitPlanner.foodHeld(loading, cfg, 10));
        FakeFacts cooking = tenRawPork();
        cooking.cookingFood("cooked_porkchop", 4, 8, 20);
        assertEquals(80 + 32, KitPlanner.foodHeld(cooking, cfg, 10));
    }

    @Test
    public void foodWithNoRawMeatIsTheSumItAlwaysWas() {
        FakeFacts f = new FakeFacts();
        f.foodUnits = 50;
        assertEquals(50, KitPlanner.foodHeld(f, cfg, 10));
        f.cookingFood("cooked_mutton", 3, 6, 20);
        assertEquals(68, KitPlanner.foodHeld(f, cfg, 10));
    }

    @Test
    public void theFoodFloorStartsLowAndRunsToTheFullAmount() {
        assertFalse(FoodFloor.next(false, 40, cfg));
        assertTrue(FoodFloor.next(false, cfg.minHeldFoodUnits - 1, cfg));
        // once started it carries on past the floor, up to the minimum
        assertTrue(FoodFloor.next(true, 40, cfg));
        assertFalse(FoodFloor.next(true, cfg.minFoodUnits, cfg));
    }

    @Test
    public void atTheFullAmountItNeverLeads() {
        assertFalse(FoodGate.leads(70, 0, cfg, true, true, false));
        assertFalse(FoodGate.leads(100, 0, cfg, true, false, false));
    }

    @Test
    public void aTopUpThatStartedRunsToTheTopWithoutFlipping() {
        int held = 40;
        boolean topUp = false;
        // down the mine: nothing
        boolean lead = FoodGate.leads(held, 0, cfg, false, topUp, false);
        topUp = FoodGate.nextTopUp(topUp, held, 0, cfg, lead);
        assertFalse(lead);
        assertFalse(topUp);
        // up on the surface it starts
        lead = FoodGate.leads(held, 0, cfg, true, topUp, false);
        topUp = FoodGate.nextTopUp(topUp, held, 0, cfg, lead);
        assertTrue(lead);
        assertTrue(topUp);
        // the hunt takes it under ground or into a ravine, and the count climbs: still leading, every tick
        for (held = 41; held < 70; held += 7) {
            lead = FoodGate.leads(held, 0, cfg, false, topUp, false);
            topUp = FoodGate.nextTopUp(topUp, held, 0, cfg, lead);
            assertTrue("at " + held, lead);
            assertTrue(topUp);
        }
        // 70 and it is over, and the next dip starts from scratch
        lead = FoodGate.leads(70, 0, cfg, false, topUp, false);
        topUp = FoodGate.nextTopUp(topUp, 70, 0, cfg, lead);
        assertFalse(lead);
        assertFalse(topUp);
        assertFalse(FoodGate.leads(60, 0, cfg, false, topUp, false));
    }

    @Test
    public void aForcedTripUnderTheFloorDoesNotLatchTheSoftOne() {
        boolean lead = FoodGate.leads(10, 0, cfg, false, false, false);
        assertTrue(lead);
        assertFalse(FoodGate.nextTopUp(false, 10, 0, cfg, lead));
        // and once it is back over the floor in the mine, the mine wins again
        assertFalse(FoodGate.leads(25, 0, cfg, false, false, false));
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
        int held = KitPlanner.foodHeld(f, cfg, 10);
        return FoodGate.leads(held, KitPlanner.rawGapLeftOut(f, cfg, 10), cfg, true, topUp, false);
    }

    @Test
    public void aSmallGapTheRawMeatCoversDoesNotLead() {
        // 66 held, the chicken's 4 not in it: cooked it is 70, so no trip, latched top-up or not
        assertFalse(FoodGate.leads(66, 4, cfg, true, false, false));
        assertFalse(FoodGate.leads(66, 4, cfg, true, true, false));
        // and it does not start the latch either
        assertFalse(FoodGate.nextTopUp(false, 66, 4, cfg, FoodGate.leads(66, 4, cfg, true, false, false)));
        // without the raw meat to cover it the very same 66 is a real top-up
        assertTrue(FoodGate.leads(66, 0, cfg, true, false, false));
        assertTrue(FoodGate.nextTopUp(false, 66, 0, cfg, FoodGate.leads(66, 0, cfg, true, false, false)));
    }

    @Test
    public void theSmokerComingAndGoingDoesNotStartOrStopATopUp() {
        // the smoker is picked up, placed again and forgotten while the bag stays the same: the verdict must not follow it
        FakeFacts nearby = chickenBag(true);
        FakeFacts gone = chickenBag(false);
        assertTrue(CookGate.cookFeasible(nearby, cfg, 10));
        assertFalse(CookGate.cookFeasible(gone, cfg, 10));
        assertEquals(70, KitPlanner.foodHeld(nearby, cfg, 10));
        assertEquals(66, KitPlanner.foodHeld(gone, cfg, 10));
        boolean topUp = false;
        for (FakeFacts f : new FakeFacts[]{nearby, gone, nearby, gone, gone, nearby}) {
            boolean lead = softLeads(f, topUp);
            topUp = FoodGate.nextTopUp(topUp, KitPlanner.foodHeld(f, cfg, 10), KitPlanner.rawGapLeftOut(f, cfg, 10), cfg, lead);
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
            assertEquals(70, KitPlanner.foodHeld(nearby, cfg, 10));
            assertEquals(60, KitPlanner.foodHeld(gone, cfg, 10));
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
        assertEquals(68, KitPlanner.foodHeld(f, cfg, 10));
        assertEquals(0, KitPlanner.rawGapLeftOut(f, cfg, 10));
        assertFalse(FoodGate.covered(68, KitPlanner.rawGapLeftOut(f, cfg, 10), cfg));
        assertTrue(softLeads(f, false));
        // no cook possible: the same chicken is not counted, and left out it is the gap
        f.smokerPlaced = false;
        assertEquals(64, KitPlanner.foodHeld(f, cfg, 10));
        assertEquals(4, KitPlanner.rawGapLeftOut(f, cfg, 10));
        // 64 + 4 is 68, still 2 short of 70: the chicken does not close that hole
        assertFalse(FoodGate.covered(64, 4, cfg));
        assertTrue(softLeads(f, false));
    }

    @Test
    public void aBigGapOfRawMeatStillGetsAHunt() {
        // ten raw porkchop and no way to cook them: 30 real units, 50 on paper. nowhere near a steak's worth of hole
        FakeFacts f = tenRawPork();
        assertEquals(30, KitPlanner.foodHeld(f, cfg, 10));
        assertEquals(50, KitPlanner.rawGapLeftOut(f, cfg, 10));
        assertTrue(softLeads(f, false));
        // the cap is SMALL_GAP: that far under is covered by any raw meat that fills it, one more unit is not
        int edge = cfg.minFoodUnits - FoodGate.SMALL_GAP;
        assertTrue(FoodGate.covered(edge, 50, cfg));
        assertFalse(FoodGate.covered(edge - 1, 50, cfg));
        assertTrue(FoodGate.leads(edge - 1, 50, cfg, true, false, false));
        assertFalse(FoodGate.leads(edge, 50, cfg, true, false, false));
        // and a small hole that the meat only half fills is still a top-up
        assertTrue(FoodGate.leads(66, 3, cfg, true, false, false));
        assertFalse(FoodGate.leads(66, 4, cfg, true, false, false));
    }

    @Test
    public void underTheFloorLeadsWhateverTheBagHolds() {
        assertTrue(FoodGate.leads(10, 100, cfg, false, false, false));
        assertTrue(FoodGate.leads(23, 50, cfg, false, false, true));
        assertFalse(FoodGate.covered(23, 100, cfg));
        // the floor is not a small hole even when the config puts the two lines next to each other
        OverworldConfig close = new OverworldConfig();
        close.minHeldFoodUnits = 24;
        close.minFoodUnits = 28;
        assertTrue(FoodGate.leads(23, 100, close, false, false, false));
        assertFalse(FoodGate.leads(24, 100, close, true, false, false));
    }

    @Test
    public void aTopUpUnderWayEndsWhenTheRawMeatCoversTheRest() {
        // the hunt got us to 66 and the bag holds the chicken that fills the last 4: done, the latch is cleared too
        assertFalse(FoodGate.leads(66, 4, cfg, false, true, false));
        assertFalse(FoodGate.nextTopUp(true, 66, 4, cfg, false));
        // and it stays cleared: with the meat eaten a top-up has to start over, on the surface, not wake up in the mine
        assertFalse(FoodGate.leads(66, 0, cfg, false, false, false));
        assertTrue(FoodGate.leads(66, 0, cfg, true, false, false));
        // far from covered, the latch carries on the way it always did
        assertTrue(FoodGate.nextTopUp(true, 50, 4, cfg, true));
        assertTrue(FoodGate.leads(50, 4, cfg, false, true, false));
    }

    // ---- the top-up's own smoker screen is not somebody else's load

    @Test
    public void aCookStationOrAnotherLoadHoldsTheSoftTopUpBack() {
        assertTrue(FoodGate.cookBusy(true, false, false));
        assertTrue(FoodGate.cookBusy(true, true, true));
        // someone else's screen: the food need did not lead a tick ago, so it is not ours
        assertTrue(FoodGate.cookBusy(false, true, false));
        assertFalse(FoodGate.cookBusy(false, false, false));
        assertFalse(FoodGate.cookBusy(false, false, true));
    }

    @Test
    public void theTopUpDoesNotCutItselfOffWhenItsOwnScreenOpens() {
        // a top-up on the surface walks to a smoker for the cooked chicken, and the screen opens under it: that screen is its own
        boolean led = false;
        boolean lead = FoodGate.leads(40, 0, cfg, true, false, FoodGate.cookBusy(false, false, led));
        assertTrue(lead);
        led = lead;
        for (int tick = 0; tick < 5; tick++) {
            lead = FoodGate.leads(40, 0, cfg, true, true, FoodGate.cookBusy(false, true, led));
            assertTrue("tick " + tick, lead);
            led = lead;
        }
        // an iron load that was already in flight when the food need came up still holds it back
        led = false;
        assertFalse(FoodGate.leads(40, 0, cfg, true, false, FoodGate.cookBusy(false, true, led)));
    }

    @Test
    public void withoutDropsExactlyThatNeed() {
        List<KitNeed> plan = List.of(CRAFT, FOOD, ORE);
        assertEquals(List.of(CRAFT, ORE), FoodGate.without(plan, 1));
        assertEquals(3, plan.size());
    }

    @Test
    public void bandsAreTheTwoLines() {
        assertEquals(0, FoodGate.band(23, cfg));
        assertEquals(1, FoodGate.band(24, cfg));
        assertEquals(1, FoodGate.band(69, cfg));
        assertEquals(2, FoodGate.band(70, cfg));
    }

    @Test
    public void thePlannerStillListsTheFoodAtTheFullAmountOnlyWhenShort() {
        // the gate works on the planner's list: 70 and over has no minimum entry, so there is nothing to gate
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        assertTrue(FoodGate.index(KitPlanner.plan(f, cfg, 8), cfg) >= 0);
        f.foodUnits = 70;
        assertEquals(-1, FoodGate.index(KitPlanner.plan(f, cfg, 8), cfg));
    }

    @Test
    public void shallowIsTheSameLineAsGoingUp() {
        assertTrue(SmeltSurface.shallow(SmeltSurface.GO_UP_DEPTH));
        assertFalse(SmeltSurface.shallow(SmeltSurface.GO_UP_DEPTH + 1));
    }
}
