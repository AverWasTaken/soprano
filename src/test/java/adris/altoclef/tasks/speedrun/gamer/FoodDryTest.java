package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the dry spell latch and the prey override, the pure rules
public class FoodDryTest {
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static KitNeed food(int n) {
        return new KitNeed(KitNeed.FOOD, n);
    }

    private static KitNeed log() {
        return new KitNeed("log", 4);
    }

    @Test
    public void searchingLongEnoughStartsTheLatchOnce() {
        RunState s = new RunState();
        assertNull(FoodDry.tick(s, true, false, 1000));
        assertNull(FoodDry.tick(s, true, false, 1000 + FoodDry.SEARCH_TICKS - 1));
        assertNotNull(FoodDry.tick(s, true, false, 1000 + FoodDry.SEARCH_TICKS));
        assertTrue(s.foodDryOn);
        assertEquals(1000 + FoodDry.SEARCH_TICKS + FoodDry.LATCH_TICKS, s.foodDryUntil);
        // already on: no second line
        assertNull(FoodDry.tick(s, true, false, 1000 + FoodDry.SEARCH_TICKS + 5));
    }

    @Test
    public void aGapInTheSearchOrAnAnimalRestartsTheClock() {
        RunState s = new RunState();
        FoodDry.tick(s, true, false, 0);
        FoodDry.tick(s, false, false, FoodDry.SEARCH_TICKS - 10);
        assertNull(FoodDry.tick(s, true, false, FoodDry.SEARCH_TICKS));
        assertFalse(s.foodDryOn);
        FoodDry.tick(s, false, false, 4000);
        FoodDry.tick(s, true, false, 5000);
        FoodDry.tick(s, true, true, 5000 + FoodDry.SEARCH_TICKS - 10);
        assertNull(FoodDry.tick(s, true, false, 5000 + FoodDry.SEARCH_TICKS));
        assertFalse(s.foodDryOn);
    }

    @Test
    public void theLatchEndsOnItsOwnAndTheSearchStartsFresh() {
        RunState s = new RunState();
        FoodDry.tick(s, true, false, 0);
        FoodDry.tick(s, true, false, FoodDry.SEARCH_TICKS);
        long end = FoodDry.SEARCH_TICKS + FoodDry.LATCH_TICKS;
        assertNull(FoodDry.tick(s, true, false, end - 1));
        assertNotNull(FoodDry.tick(s, true, false, end));
        assertFalse(s.foodDryOn);
        // searching right through the end does not re-latch at once, it counts 90 s again
        assertNull(FoodDry.tick(s, true, false, end + 1));
        assertFalse(s.foodDryOn);
        assertNotNull(FoodDry.tick(s, true, false, end + 1 + FoodDry.SEARCH_TICKS));
    }

    @Test
    public void aClockThatWentBackwardsDoesNotHoldTheLatch() {
        RunState s = new RunState();
        s.foodDryOn = true;
        s.foodDryUntil = 100000;
        assertNotNull(FoodDry.tick(s, false, false, 10));
        assertFalse(s.foodDryOn);
    }

    @Test
    public void deferMovesLeadingFoodBehindTheNextStep() {
        List<KitNeed> out = FoodDry.defer(List.of(food(24), log(), food(60)));
        assertEquals(List.of(log(), food(24), food(60)), out);
        out = FoodDry.defer(List.of(food(24), food(30), log(), new KitNeed("cobblestone", 8)));
        assertEquals(List.of(log(), food(24), food(30), new KitNeed("cobblestone", 8)), out);
    }

    @Test
    public void deferLeavesAnythingElseAlone() {
        List<KitNeed> noFood = List.of(log(), food(60));
        assertSame(noFood, FoodDry.defer(noFood));
        List<KitNeed> onlyFood = List.of(food(24), food(60));
        assertSame(onlyFood, FoodDry.defer(onlyFood));
        assertTrue(FoodDry.defer(List.of()).isEmpty());
    }

    @Test
    public void anAnimalInSightCancelsTheDeferral() {
        assertTrue(FoodDry.defers(true, false));
        assertFalse(FoodDry.defers(true, true));
        assertFalse(FoodDry.defers(false, false));
    }

    private static boolean foodFirst(List<KitNeed> needs) {
        return !needs.isEmpty() && KitNeed.FOOD.equals(needs.get(0).catalogueName());
    }

    @Test
    public void gatherPutsFoodFirstOnlyWithPreyAround() {
        OverworldConfig cfg = new OverworldConfig();
        FakeFacts bag = new FakeFacts();
        assertFalse(foodFirst(KitPlanner.gather(bag, cfg, 8)));
        bag.preyNear = true;
        List<KitNeed> needs = KitPlanner.gather(bag, cfg, 8);
        assertTrue(foodFirst(needs));
        assertEquals(cfg.minHeldFoodUnits, needs.get(0).count());
    }

    @Test
    public void gatherIgnoresPreyOnceTheBagIsFed() {
        OverworldConfig cfg = new OverworldConfig();
        FakeFacts bag = new FakeFacts();
        bag.foodUnits = 200;
        bag.preyNear = true;
        assertFalse(foodFirst(KitPlanner.gather(bag, cfg, 8)));
    }

    @Test
    public void planHungryLeadsWithFoodUnlessTheSpellIsOn() {
        OverworldConfig cfg = new OverworldConfig();
        FakeFacts bag = new FakeFacts();
        assertTrue(foodFirst(KitPlanner.plan(bag, cfg, 8)));
        bag.foodDryLatched = true;
        List<KitNeed> latched = KitPlanner.plan(bag, cfg, 8);
        assertNotEquals(KitNeed.FOOD, latched.get(0).catalogueName());
        assertTrue(latched.stream().anyMatch(n -> KitNeed.FOOD.equals(n.catalogueName())));
        // an animal wanders in and food is the head again, latch or not
        bag.preyNear = true;
        assertTrue(foodFirst(KitPlanner.plan(bag, cfg, 8)));
    }
}
