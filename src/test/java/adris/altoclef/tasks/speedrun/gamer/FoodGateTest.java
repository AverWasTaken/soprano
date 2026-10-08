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
        assertFalse(FoodGate.leads(40, cfg, false, false, false));
        assertFalse(FoodGate.leads(24, cfg, false, false, false));
    }

    @Test
    public void underTheFloorFoodAlwaysGoesFirst() {
        assertTrue(FoodGate.leads(23, cfg, false, false, false));
        assertTrue(FoodGate.leads(0, cfg, false, false, false));
    }

    @Test
    public void onlyTheSurfaceMakesTheSoftTopUpCheap() {
        assertTrue(FoodGate.leads(40, cfg, true, false, false));
        // a cook or a craft next in a mine used to count as "between jobs" (22:08, a smoker at y=32 and then a cow hunt through
        // the rock). down there the hunt is a climb whatever the next job is
        assertFalse(FoodGate.leads(40, cfg, false, false, false));
    }

    @Test
    public void aCookThatIsLoadingIsNotCutShortBySoftTopUp() {
        assertFalse(FoodGate.leads(40, cfg, true, false, true));
        // not even one that already started, it picks up again when the cook lets go
        assertFalse(FoodGate.leads(40, cfg, true, true, true));
        assertTrue(FoodGate.leads(40, cfg, true, true, false));
        // but a bag that is really empty is still an emergency
        assertTrue(FoodGate.leads(10, cfg, false, false, true));
        // and the top-up that was latched does not start from nothing because the cook blinked
        assertFalse(FoodGate.nextTopUp(false, 40, cfg, FoodGate.leads(40, cfg, true, false, true)));
        assertTrue(FoodGate.nextTopUp(true, 40, cfg, FoodGate.leads(40, cfg, true, true, true)));
    }

    @Test
    public void meatInTheOpenStationCountsUntilTheJobIsRecorded() {
        // 42 held, 4 pork in the screen (32 planned): the dip that sent the bot after a cow is not a dip
        assertEquals(32, FoodGate.inStation(32, 0));
        // the job is in the state: the pending sum has it, counting it again would hide a real shortage
        assertEquals(0, FoodGate.inStation(32, 32));
        assertEquals(0, FoodGate.inStation(0, 0));
    }

    @Test
    public void atTheFullAmountItNeverLeads() {
        assertFalse(FoodGate.leads(70, cfg, true, true, false));
        assertFalse(FoodGate.leads(100, cfg, true, false, false));
    }

    @Test
    public void aTopUpThatStartedRunsToTheTopWithoutFlipping() {
        int held = 40;
        boolean topUp = false;
        // down the mine: nothing
        boolean lead = FoodGate.leads(held, cfg, false, topUp, false);
        topUp = FoodGate.nextTopUp(topUp, held, cfg, lead);
        assertFalse(lead);
        assertFalse(topUp);
        // up on the surface it starts
        lead = FoodGate.leads(held, cfg, true, topUp, false);
        topUp = FoodGate.nextTopUp(topUp, held, cfg, lead);
        assertTrue(lead);
        assertTrue(topUp);
        // the hunt takes it under ground or into a ravine, and the count climbs: still leading, every tick
        for (held = 41; held < 70; held += 7) {
            lead = FoodGate.leads(held, cfg, false, topUp, false);
            topUp = FoodGate.nextTopUp(topUp, held, cfg, lead);
            assertTrue("at " + held, lead);
            assertTrue(topUp);
        }
        // 70 and it is over, and the next dip starts from scratch
        lead = FoodGate.leads(70, cfg, false, topUp, false);
        topUp = FoodGate.nextTopUp(topUp, 70, cfg, lead);
        assertFalse(lead);
        assertFalse(topUp);
        assertFalse(FoodGate.leads(60, cfg, false, topUp, false));
    }

    @Test
    public void aForcedTripUnderTheFloorDoesNotLatchTheSoftOne() {
        boolean lead = FoodGate.leads(10, cfg, false, false, false);
        assertTrue(lead);
        assertFalse(FoodGate.nextTopUp(false, 10, cfg, lead));
        // and once it is back over the floor in the mine, the mine wins again
        assertFalse(FoodGate.leads(25, cfg, false, false, false));
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
