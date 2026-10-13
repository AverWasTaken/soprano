package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.GarbageRules.Cand;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

public class GarbageRulesTest {

    // the default setting, what the gamer overrides with minFoodUnits
    private static final int FLOOR = 40;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ItemFixture.load();
    }

    private static int bag(ItemStack... stacks) {
        int sum = 0;
        for (ItemStack s : stacks) {
            sum += GarbageRules.keepUnits(s);
        }
        return sum;
    }

    // the index of the stack that goes, -1 for none
    private static int pick(int floor, ItemStack... stacks) {
        List<Cand> cands = new ArrayList<>();
        for (ItemStack s : stacks) {
            cands.add(GarbageRules.cand(s));
        }
        return GarbageRules.pick(cands, bag(stacks), floor);
    }

    @Test
    public void foodSumIsNutritionTimesCountNotPerStack() {
        // cooked beef is 8 a piece. the old sum added 8 per stack, so a stack of 16 looked like a single steak
        assertEquals(8, GarbageRules.keepUnits(new ItemStack(Items.COOKED_BEEF, 1)));
        assertEquals(128, GarbageRules.keepUnits(new ItemStack(Items.COOKED_BEEF, 16)));
        assertEquals(0, GarbageRules.keepUnits(new ItemStack(Items.COBBLESTONE, 64)));
        assertEquals(0, GarbageRules.keepUnits(ItemStack.EMPTY));
    }

    @Test
    public void rawMeatCountsAtItsCookedValueLikeTheGamerDoes() {
        // GamerFacts.foodUnits books raw mutton at 6, not the 2 it is raw
        assertEquals(6, GarbageRules.keepUnits(new ItemStack(Items.MUTTON, 1)));
    }

    @Test
    public void rottenFleshAndGapplesDoNotCountTowardTheFloor() {
        assertEquals(0, GarbageRules.keepUnits(new ItemStack(Items.ROTTEN_FLESH, 20)));
        assertEquals(0, GarbageRules.keepUnits(new ItemStack(Items.GOLDEN_APPLE, 3)));
    }

    @Test
    public void gappleIsNeverTheStackThatGoes() {
        ItemStack gapple = new ItemStack(Items.GOLDEN_APPLE, 1);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 3);
        ItemStack pick = new ItemStack(Items.WOODEN_PICKAXE, 1);
        // under the floor it used to rank after junk and tools and still go once those were gone
        assertEquals(1, pick(FLOOR, gapple, cobble));
        assertEquals(1, pick(FLOOR, gapple, pick));
        assertEquals(-1, pick(FLOOR, gapple));
        assertEquals(-1, pick(0, gapple, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1)));
    }

    @Test
    public void fullBagUnderTheFloorThrowsCobbleNeverTheSteak() {
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 16);
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 2);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        // 16 units against a floor of 40: the steak stays whatever order the bag is in
        assertEquals(0, pick(FLOOR, cobble, steak, pick));
        assertEquals(2, pick(FLOOR, steak, pick, cobble));
        // and without the cobble the steak still stays, the pick is what goes
        assertEquals(1, pick(FLOOR, steak, pick));
    }

    @Test
    public void underTheFloorNothingFoodIsEverGarbage() {
        assertEquals(-1, pick(FLOOR, new ItemStack(Items.COOKED_BEEF, 2), new ItemStack(Items.BREAD, 3)));
        assertTrue(GarbageRules.keepsFood(16, 16, FLOOR));
    }

    @Test
    public void surplusFoodGoesOnlyAfterEveryNonFoodCandidate() {
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 16);
        ItemStack bread = new ItemStack(Items.BREAD, 2);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        // 128 + 10 units, bread is pure surplus. but the cobble goes first, then even the pick, then the bread
        assertEquals(3, pick(FLOOR, steak, bread, pick, cobble));
        assertEquals(2, pick(FLOOR, steak, bread, pick));
        assertEquals(1, pick(FLOOR, steak, bread));
    }

    @Test
    public void surplusFoodGoesLowestUnitsFirst() {
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 16);
        ItemStack bread = new ItemStack(Items.BREAD, 4);
        ItemStack carrot = new ItemStack(Items.CARROT, 2);
        assertEquals(2, pick(FLOOR, steak, bread, carrot));
    }

    @Test
    public void fewerPlannedUnitsGoFirstEvenWhenTheyAreCooked() {
        // 8 raw beef is 64 at the gamer's measure, 4 cooked is 32. the cooked stack is the smaller loss
        ItemStack raw = new ItemStack(Items.BEEF, 8);
        ItemStack cooked = new ItemStack(Items.COOKED_BEEF, 4);
        ItemStack steak = new ItemStack(Items.COOKED_PORKCHOP, 16);
        assertEquals(1, pick(FLOOR, raw, cooked, steak));
    }

    @Test
    public void gapplesAndChorusFruitGoAfterJunkAndToolsToo() {
        ItemStack apple = new ItemStack(Items.GOLDEN_APPLE, 1);
        ItemStack chorus = new ItemStack(Items.CHORUS_FRUIT, 1);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 30);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack steak = new ItemStack(Items.COOKED_BEEF, 16);
        assertEquals(2, pick(FLOOR, apple, chorus, cobble, pick));
        assertEquals(3, pick(FLOOR, apple, chorus, steak, pick));
        // and rotten flesh is junk like any other, it goes ahead of the pick
        ItemStack rotten = new ItemStack(Items.ROTTEN_FLESH, 20);
        assertEquals(2, pick(FLOOR, apple, pick, rotten));
    }

    @Test
    public void rawBeforeCookedWhenTheCountsMatch() {
        // both are 64 units at the gamer's measure, the raw one gives 3 a piece as it is and goes first
        ItemStack cooked = new ItemStack(Items.COOKED_BEEF, 8);
        ItemStack raw = new ItemStack(Items.BEEF, 8);
        ItemStack steak = new ItemStack(Items.COOKED_PORKCHOP, 16);
        assertEquals(1, pick(FLOOR, cooked, raw, steak));
    }

    @Test
    public void aStackIsOnlyGarbageIfTheRestStillClearsTheFloor() {
        // 41 units and a floor of 40: throwing the 40 would leave 1, so the one big stack stays
        assertTrue(GarbageRules.keepsFood(41, 40, FLOOR));
        assertFalse(GarbageRules.keepsFood(41, 1, FLOOR));
        assertFalse(GarbageRules.keepsFood(100, 60, FLOOR));
        // two stacks that are each spare alone are not both spare together
        ItemStack a = new ItemStack(Items.COOKED_BEEF, 7);
        ItemStack b = new ItemStack(Items.COOKED_PORKCHOP, 8);
        assertEquals(0, pick(FLOOR, a, b));
        assertEquals(-1, pick(FLOOR, b));
    }

    @Test
    public void aFloorOfZeroTurnsTheRuleOff() {
        assertEquals(0, pick(0, new ItemStack(Items.COOKED_BEEF, 2)));
    }

    @Test
    public void toolsStillGoWorstFirstAndJunkBeforeTools() {
        ItemStack wood = new ItemStack(Items.WOODEN_PICKAXE, 1);
        ItemStack iron = new ItemStack(Items.IRON_PICKAXE, 1);
        ItemStack junk = new ItemStack(Items.FLINT, 3);
        assertEquals(0, pick(FLOOR, wood, iron));
        assertEquals(2, pick(FLOOR, wood, iron, junk));
    }

    @Test
    public void heldFoodUnderTheFloorIsNotThrowableButSpareFoodIs() {
        // the cursor is in the bag total, so a lone 2 steak stack on it is the whole 16
        ItemStack held = new ItemStack(Items.COOKED_BEEF, 2);
        assertTrue(GarbageRules.keepsFood(bag(held), GarbageRules.keepUnits(held), FLOOR));
        ItemStack spare = new ItemStack(Items.BREAD, 2);
        ItemStack stock = new ItemStack(Items.COOKED_BEEF, 16);
        assertFalse(GarbageRules.keepsFood(bag(spare, stock), GarbageRules.keepUnits(spare), FLOOR));
    }
}
