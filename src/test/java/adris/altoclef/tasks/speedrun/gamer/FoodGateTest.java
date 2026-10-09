package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the list and world half of the IRON food gate. the numbers and the verdicts are FoodPlanTest's
public class FoodGateTest {
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 70);
    private static final KitNeed ORE = new KitNeed("iron_ingot", 39);
    private static final KitNeed CRAFT = new KitNeed("iron_pickaxe", 1);

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

    private FoodPlan plan(FakeFacts f) {
        return FoodPlan.of(f, cfg, end);
    }

    @Test
    public void onlyTheKitsOwnMinimumFoodIsTheGatedOne() {
        List<KitNeed> plan = List.of(CRAFT, FOOD, ORE, new KitNeed(KitNeed.FOOD, 100), new KitNeed(KitNeed.FOOD, 130));
        FoodPlan food = plan(new FakeFacts());
        assertEquals(1, FoodGate.index(plan, food));
        // the target and the stock-up are not it, and neither is a plan with no food at all
        assertEquals(-1, FoodGate.index(List.of(ORE, new KitNeed(KitNeed.FOOD, 100)), food));
        assertEquals(-1, FoodGate.index(List.of(), food));
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
    public void aCookStationOrAnotherLoadHoldsTheSoftTopUpBack() {
        assertTrue(FoodGate.cookBusy(true, false, false));
        assertTrue(FoodGate.cookBusy(true, true, true));
        // someone else's screen: the food need did not lead a tick ago, so it is not ours
        assertTrue(FoodGate.cookBusy(false, true, false));
        assertFalse(FoodGate.cookBusy(false, false, false));
        assertFalse(FoodGate.cookBusy(false, false, true));
    }

    @Test
    public void withoutDropsExactlyThatNeed() {
        List<KitNeed> plan = List.of(CRAFT, FOOD, ORE);
        assertEquals(List.of(CRAFT, ORE), FoodGate.without(plan, 1));
        assertEquals(3, plan.size());
    }

    @Test
    public void thePlannerStillListsTheFoodAtTheFullAmountOnlyWhenShort() {
        // the gate works on the planner's list: 70 and over has no minimum entry, so there is nothing to gate
        FakeFacts f = new FakeFacts();
        f.foodUnits = 40;
        assertTrue(FoodGate.index(KitPlanner.plan(f, cfg, 8, plan(f)), plan(f)) >= 0);
        f.foodUnits = 70;
        assertEquals(-1, FoodGate.index(KitPlanner.plan(f, cfg, 8, plan(f)), plan(f)));
    }

    @Test
    public void shallowIsTheSameLineAsGoingUp() {
        assertTrue(SmeltSurface.shallow(SmeltSurface.GO_UP_DEPTH));
        assertFalse(SmeltSurface.shallow(SmeltSurface.GO_UP_DEPTH + 1));
    }
}
