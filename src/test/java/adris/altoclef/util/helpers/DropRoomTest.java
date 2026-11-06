package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import adris.altoclef.util.helpers.DropRoom.Decision;
import adris.altoclef.util.helpers.DropRoom.Kind;
import adris.altoclef.util.helpers.DropRoom.Source;
import adris.altoclef.util.helpers.DropRoom.Stack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

public class DropRoomTest {

    // the End gate's minBuildBlocks out of the box
    private static final int FLOOR = 48;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static Stack kit(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.KIT, false, false);
    }

    private static Stack rest(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.REST, false, false);
    }

    private static Stack build(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.REST, false, true);
    }

    private static Stack core(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.CORE, false, false);
    }

    private static Stack kept(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.KIT, true, false);
    }

    // a bag of kit we keep, one of each, nothing anybody may push out
    private static List<Stack> keepers() {
        List<Stack> bag = new ArrayList<>();
        for (Item item : new Item[]{Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.OBSIDIAN, Items.SHIELD, Items.BUCKET}) {
            bag.add(kit(item, 1));
        }
        return bag;
    }

    private static List<Stack> with(Stack... more) {
        List<Stack> bag = keepers();
        bag.addAll(List.of(more));
        return bag;
    }

    private static Decision pearl(List<Stack> bag) {
        return DropRoom.decide(false, -1, false, false, true, bag);
    }

    // ---- the order

    @Test
    public void fitsBeatsEverything() {
        assertSame(Decision.FIT, DropRoom.decide(true, 3, false, true, true, keepers()));
    }

    @Test
    public void theGarbagePickComesFirst() {
        Decision d = DropRoom.decide(false, 2, false, true, true, with(rest(Items.STRING, 1), rest(Items.STRING, 3)));
        assertEquals(new Decision(Kind.EVICT, Source.GARBAGE, 2), d);
    }

    @Test
    public void aDropThatWouldBeTheNextGarbageIsANo() {
        assertSame(Decision.NO, DropRoom.decide(false, 2, true, false, false, keepers()));
    }

    @Test
    public void wearableArmorStillGetsTheLastResort() {
        assertEquals(new Decision(Kind.EVICT, Source.WEAR, -1), DropRoom.decide(false, -1, false, true, false, keepers()));
    }

    @Test
    public void breadWithAFullBagOfKeepersIsANo() {
        // kit never evicts, even with a perfectly good second string stack around
        assertSame(Decision.NO, DropRoom.decide(false, -1, false, false, false, with(rest(Items.STRING, 1), rest(Items.STRING, 3))));
    }

    // ---- the victim pick

    @Test
    public void pearlPushesOutATierThreeDuplicate() {
        List<Stack> bag = with(rest(Items.STRING, 9), rest(Items.STRING, 4));
        assertEquals(new Decision(Kind.EVICT, Source.VICTIM, 7), pearl(bag));
    }

    @Test
    public void pearlPushesOutALoneTierThreeStack() {
        List<Stack> bag = with(rest(Items.STRING, 9));
        assertEquals(new Decision(Kind.EVICT, Source.VICTIM, bag.size() - 1), pearl(bag));
    }

    @Test
    public void pearlPushesOutADuplicateStonePickaxe() {
        List<Stack> bag = with(kit(Items.STONE_PICKAXE, 1), kit(Items.STONE_PICKAXE, 1));
        assertEquals(6, pearl(bag).slot());
    }

    @Test
    public void pearlPushesOutAWorseToolWeHoldBetter() {
        List<Stack> bag = with(kit(Items.IRON_PICKAXE, 1), kit(Items.WOODEN_PICKAXE, 1));
        assertEquals(7, pearl(bag).slot());
    }

    @Test
    public void surplusBeatsABuildStackBeatsNothing() {
        // two build stacks, the smaller one goes
        assertEquals(7, pearl(with(build(Items.NETHERRACK, 64), build(Items.COBBLESTONE, 20))).slot());
        // one build stack is the bridge, not a victim
        assertSame(Decision.NO, pearl(with(build(Items.NETHERRACK, 64))));
        // a spare furnace is surplus, so it goes before a build stack
        assertEquals(8, pearl(with(build(Items.NETHERRACK, 64), build(Items.COBBLESTONE, 20), kit(Items.FURNACE, 1), kit(Items.FURNACE, 1))).slot());
        // the only furnace isn't spare, the build stack goes instead
        assertEquals(7, pearl(with(build(Items.NETHERRACK, 64), build(Items.COBBLESTONE, 20), kit(Items.FURNACE, 1))).slot());
    }

    @Test
    public void woolPastABedsWorth() {
        // 7 wool: the red two can go and leave five, the white five can't (two left)
        assertEquals(7, pearl(with(kit(Items.WOOL.white(), 5), kit(Items.WOOL.red(), 2))).slot());
        assertSame(Decision.NO, pearl(with(kit(Items.WOOL.white(), 4))));
        // two stacks are not a spare when together they're one bed
        assertSame(Decision.NO, pearl(with(kit(Items.WOOL.white(), 1), kit(Items.WOOL.white(), 2))));
    }

    @Test
    public void aSpareCraftingTableButNeverTheLast() {
        assertEquals(6, pearl(with(kit(Items.CRAFTING_TABLE, 1), kit(Items.CRAFTING_TABLE, 1))).slot());
        assertSame(Decision.NO, pearl(with(kit(Items.CRAFTING_TABLE, 1))));
    }

    @Test
    public void neverTheseEvenAsDuplicates() {
        assertSame(Decision.NO, pearl(with(core(Items.ENDER_PEARL, 3), core(Items.ENDER_PEARL, 16))));
        assertSame(Decision.NO, pearl(with(kit(Items.WATER_BUCKET, 1), kit(Items.WATER_BUCKET, 1))));
        assertSame(Decision.NO, pearl(with(kit(Items.GOLDEN_APPLE, 1), kit(Items.GOLDEN_APPLE, 2))));
        assertSame(Decision.NO, pearl(with(kit(Items.POTION, 1), kit(Items.POTION, 1))));
        // nuggets are for bartering, a second stack is not spare
        assertSame(Decision.NO, pearl(with(kit(Items.GOLD_NUGGET, 30), kit(Items.GOLD_NUGGET, 64))));
        // armor we wear or would, enchanted, named, important, food under the floor: the world says keep
        assertSame(Decision.NO, pearl(with(kept(Items.IRON_HELMET, 1), kept(Items.IRON_HELMET, 1))));
        // the last pickaxe, the last flint and steel
        assertSame(Decision.NO, pearl(with(kit(Items.STONE_PICKAXE, 1))));
        assertSame(Decision.NO, pearl(with(kit(Items.FLINT_AND_STEEL, 1))));
    }

    @Test
    public void tableNetherrackPotionAndKeepersIsANo() {
        List<Stack> bag = with(kit(Items.CRAFTING_TABLE, 1), build(Items.NETHERRACK, 64), kit(Items.POTION, 1));
        assertSame(Decision.NO, pearl(bag));
    }

    @Test
    public void emptySlotsAreNobody() {
        List<Stack> bag = with(rest(Items.STRING, 2), new Stack(ItemStack.EMPTY, ItemValue.REST, false, false), rest(Items.STRING, 5));
        assertEquals(6, pearl(bag).slot());
    }

    private static List<ItemStack> stacks(ItemStack... all) {
        return new ArrayList<>(List.of(all));
    }

    @Test
    public void theEndsBedsAreSpokenForAndTheSpareIsNot() {
        List<ItemStack> bag = stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.BED.white()), new ItemStack(Items.BED.white()), ItemStack.EMPTY);
        boolean[] kept = DropRoom.reserved(bag, 2);
        assertEquals(true, kept[0]);
        assertEquals(true, kept[1]);
        assertEquals(false, kept[2]);
        assertEquals(false, kept[3]);
    }

    @Test
    public void aPearlCannotPushAReserveBedOut() {
        // two white beds look like a duplicate, that's the surplus rule's whole idea of a spare
        List<Stack> bag = keepers();
        bag.add(kit(Items.BED.white(), 1));
        bag.add(kit(Items.BED.white(), 1));
        assertEquals(bag.size() - 2, DropRoom.victim(bag));
        List<ItemStack> raw = new ArrayList<>();
        for (Stack st : bag) {
            raw.add(st.stack());
        }
        boolean[] kept = DropRoom.reserved(raw, 8);
        List<Stack> guarded = new ArrayList<>();
        for (int i = 0; i < bag.size(); i++) {
            Stack st = bag.get(i);
            guarded.add(new Stack(st.stack(), st.tier(), kept[i], st.build()));
        }
        assertEquals(-1, DropRoom.victim(guarded));
    }

    @Test
    public void theWoolThatStillHasToBecomeBedsIsSpokenFor() {
        // 1 bed of 3 reserved, so 6 wool to go. the scraps of another colour can't make a bed
        List<ItemStack> bag = stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.WOOL.red(), 6), new ItemStack(Items.WOOL.blue(), 2));
        boolean[] kept = DropRoom.reserved(bag, 3);
        assertEquals(true, kept[1]);
        assertEquals(false, kept[2]);
        // beds covered: the wool is just wool
        assertEquals(false, DropRoom.reserved(stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.WOOL.red(), 6)), 1)[1]);
    }

    @Test
    public void scrapsDontPayForAnotherColoursWool() {
        // 9 wool owed: 5 red and 5 blue are 3 usable each, so the white three are still needed
        List<ItemStack> bag = stacks(new ItemStack(Items.WOOL.red(), 5), new ItemStack(Items.WOOL.blue(), 5), new ItemStack(Items.WOOL.white(), 3));
        boolean[] kept = DropRoom.reserved(bag, 3);
        assertEquals(true, kept[0]);
        assertEquals(true, kept[1]);
        assertEquals(true, kept[2]);
    }

    @Test
    public void aSetOfThreeSplitOverTwoStacksStaysWhole() {
        List<ItemStack> bag = stacks(new ItemStack(Items.WOOL.red(), 2), new ItemStack(Items.WOOL.red(), 2));
        boolean[] kept = DropRoom.reserved(bag, 1);
        assertEquals(true, kept[0]);
        assertEquals(true, kept[1]);
    }

    @Test
    public void noRunNothingIsSpokenFor() {
        boolean[] kept = DropRoom.reserved(stacks(new ItemStack(Items.BED.white()), new ItemStack(Items.WOOL.red(), 6)), 0);
        assertEquals(false, kept[0]);
        assertEquals(false, kept[1]);
    }
    // ---- the bed errand's shed

    // in window order, the way the view reads it: the main bag and then the hotbar. bag order is the tie break
    private static List<ItemStack> stuffed() {
        Object[][] rows = {
                {Items.COOKED_BEEF, 2}, {Items.IRON_HELMET, 1}, {Items.BLAZE_ROD, 1}, {Items.BED.white(), 1}, {Items.WOOL.lightGray(), 2},
                {Items.COOKED_CHICKEN, 10}, {Items.WOOL.white(), 8}, {Items.BED.white(), 1}, {Items.DIAMOND, 1}, {Items.WOOL.black(), 1},
                {Items.BUCKET, 1}, {Items.SHEARS, 1}, {Items.ENDER_EYE, 13}, {Items.SHIELD, 1}, {Items.BED.white(), 1},
                {Items.FLINT_AND_STEEL, 1}, {Items.WATER_BUCKET, 1}, {Items.COAL, 1}, {Items.FURNACE, 3}, {Items.SHIELD, 1},
                {Items.RAW_GOLD, 4}, {Items.IRON_PICKAXE, 1}, {Items.SMOKER, 1}, {Items.BED.white(), 1}, {Items.STONE_SWORD, 1},
                {Items.BED.white(), 1}, {Items.IRON_SWORD, 1},
                {Items.DIAMOND_PICKAXE, 1}, {Items.CRAFTING_TABLE, 1}, {Items.LADDER, 3}, {Items.BED.white(), 1}, {Items.IRON_SWORD, 1},
                {Items.BED.white(), 1}, {Items.WOODEN_PICKAXE, 1}, {Items.BED.lightGray(), 1}, {Items.NETHERRACK, 41}};
        List<ItemStack> stacks = new ArrayList<>();
        for (Object[] row : rows) {
            stacks.add(new ItemStack((Item) row[0], (Integer) row[1]));
        }
        return stacks;
    }

    // what the view would hand over: the End's reserve read off the bag (ten beds, a spare among them), the helmet is one we'd
    // wear, the important list holds its three, and only the netherrack is plain enough to be tier 3, the gamer's list covers the rest
    private static List<Stack> stuffedStacks(List<ItemStack> stacks) {
        boolean[] end = DropRoom.reserved(stacks, 10);
        List<Stack> bag = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack st = stacks.get(i);
            Item item = st.getItem();
            boolean rock = item == Items.NETHERRACK;
            boolean important = item == Items.DIAMOND || item == Items.DIAMOND_PICKAXE || item == Items.ENDER_EYE;
            bag.add(new Stack(st, ItemValue.tier(false, false, ItemValue.isCore(item), !rock), end[i] || important || item == Items.IRON_HELMET, rock));
        }
        return bag;
    }

    // keeps throwing until nothing may go and returns the order
    private static List<Item> shedAll(List<ItemStack> stacks) {
        List<Item> thrown = new ArrayList<>();
        for (int i = DropRoom.shed(-1, stuffedStacks(stacks), FLOOR); i >= 0; i = DropRoom.shed(-1, stuffedStacks(stacks), FLOOR)) {
            thrown.add(stacks.get(i).getItem());
            stacks.set(i, ItemStack.EMPTY);
        }
        return thrown;
    }

    @Test
    public void aStuffedBagOffersTheCheapStuffFirst() {
        List<ItemStack> stacks = stuffed();
        assertEquals(36, stacks.size());
        // round 173: the wool scraps stay now, the errand's wool never goes
        assertEquals(List.of(Items.SHIELD, Items.IRON_PICKAXE, Items.STONE_SWORD, Items.IRON_SWORD, Items.WOODEN_PICKAXE,
                Items.RAW_GOLD, Items.FURNACE, Items.SHEARS, Items.COAL), shedAll(stacks));
    }

    @Test
    public void aStuffedBagKeepsWhatTheEndNeeds() {
        List<ItemStack> stacks = stuffed();
        shedAll(stacks);
        List<Item> left = new ArrayList<>();
        for (ItemStack st : stacks) {
            if (!st.isEmpty()) left.add(st.getItem());
        }
        assertEquals(List.of(Items.COOKED_BEEF, Items.IRON_HELMET, Items.BLAZE_ROD, Items.BED.white(), Items.WOOL.lightGray(), Items.COOKED_CHICKEN,
                Items.WOOL.white(), Items.BED.white(), Items.DIAMOND, Items.WOOL.black(), Items.BUCKET, Items.ENDER_EYE, Items.BED.white(), Items.FLINT_AND_STEEL, Items.WATER_BUCKET,
                Items.SHIELD, Items.SMOKER, Items.BED.white(), Items.BED.white(), Items.DIAMOND_PICKAXE, Items.CRAFTING_TABLE, Items.LADDER,
                Items.BED.white(), Items.IRON_SWORD, Items.BED.white(), Items.BED.lightGray(), Items.NETHERRACK), left);
    }

    @Test
    public void aDiamondOnTheImportantListStays() {
        List<Stack> bag = new ArrayList<>(List.of(kept(Items.DIAMOND, 1), kit(Items.COAL, 1)));
        assertEquals(1, DropRoom.shed(-1, bag, FLOOR));
        bag.set(1, new Stack(ItemStack.EMPTY, ItemValue.REST, false, false));
        assertEquals(-1, DropRoom.shed(-1, bag, FLOOR));
    }

    @Test
    public void thePearlsListDoesNotGrowTheLateItems() {
        List<Stack> bag = new ArrayList<>(List.of(kit(Items.RAW_GOLD, 4), kit(Items.FURNACE, 3), kit(Items.SMOKER, 1), kit(Items.SHEARS, 1),
                kit(Items.DIAMOND, 1), kit(Items.COAL, 1)));
        assertEquals(-1, DropRoom.victim(bag));
        assertSame(Decision.NO, pearl(bag));
        assertEquals(0, DropRoom.shed(-1, bag, FLOOR));
    }

    @Test
    public void theLateListGoesInItsOwnOrder() {
        List<Stack> bag = new ArrayList<>(List.of(kit(Items.COAL, 1), kit(Items.DIAMOND, 1), kit(Items.SHEARS, 1), kit(Items.FURNACE, 3),
                kit(Items.RAW_GOLD, 4), kit(Items.SMOKER, 1)));
        int[] order = {4, 3, 2, 1, 0};
        for (int want : order) {
            assertEquals(want, DropRoom.shed(-1, bag, FLOOR));
            bag.set(want, new Stack(ItemStack.EMPTY, ItemValue.REST, false, false));
        }
        assertEquals(-1, DropRoom.shed(-1, bag, FLOOR));
    }

    @Test
    public void aFurnaceStaysWithoutASmoker() {
        assertEquals(-1, DropRoom.shed(-1, new ArrayList<>(List.of(kit(Items.FURNACE, 3), kit(Items.BUCKET, 1))), FLOOR));
    }

    @Test
    public void garbageGoesBeforeAnyoneWeLike() {
        List<Stack> bag = with(kit(Items.IRON_SWORD, 1), kit(Items.IRON_SWORD, 1), rest(Items.DIRT, 64));
        assertEquals(bag.size() - 1, DropRoom.shed(bag.size() - 1, bag, FLOOR));
    }

    @Test
    public void aGarbagePickWeKeepIsNotAnAnswer() {
        List<Stack> bag = with(kept(Items.BED.white(), 1), kit(Items.IRON_SWORD, 1), kit(Items.IRON_SWORD, 1));
        int bed = bag.size() - 3;
        assertEquals(bed + 1, DropRoom.shed(bed, bag, FLOOR));
    }

    private static Stack leaves(int count) {
        return new Stack(new ItemStack(Items.OAK_LEAVES, count), ItemValue.REST, false, true, false);
    }

    private static List<Stack> bag(Stack... all) {
        return new ArrayList<>(List.of(all));
    }

    private static List<Item> shedAllOf(List<Stack> bag, int floor) {
        List<Item> thrown = new ArrayList<>();
        for (int i = DropRoom.shed(-1, bag, floor); i >= 0; i = DropRoom.shed(-1, bag, floor)) {
            thrown.add(bag.get(i).stack().getItem());
            bag.set(i, new Stack(ItemStack.EMPTY, ItemValue.REST, false, false));
        }
        return thrown;
    }

    @Test
    public void theBiggestBuildStackGoesFirstAndTheGateKeepsItsBlocks() {
        // keepers() holds a diamond and the late list would take it, a lone shield is all the company this one needs
        List<Stack> bag = bag(kit(Items.SHIELD, 1), build(Items.COBBLESTONE, 51), leaves(1), rest(Items.OAK_LOG, 1), build(Items.COBBLESTONE, 64),
                build(Items.NETHERRACK, 64));
        // 179 for the gate: two whole stacks go, the 51 would leave 0 under 48, then the leaf
        assertEquals(List.of(Items.COBBLESTONE, Items.NETHERRACK, Items.OAK_LEAVES), shedAllOf(bag, FLOOR));
        assertEquals(51, bag.get(1).stack().getCount());
        assertEquals(Items.OAK_LOG, bag.get(3).stack().getItem());
    }

    @Test
    public void aBigStackGoesBeforeTheGarbagePick() {
        List<Stack> bag = bag(kit(Items.SHIELD, 1), leaves(1), build(Items.COBBLESTONE, 64), build(Items.NETHERRACK, 64));
        assertEquals(2, DropRoom.shed(1, bag, FLOOR));
    }

    @Test
    public void theGateFloorHoldsAgainstEveryPick() {
        List<Stack> bag = bag(kit(Items.SHIELD, 1), build(Items.NETHERRACK, 41));
        int rack = 1;
        // not the build pick, not the garbage pick
        assertEquals(-1, DropRoom.shed(rack, bag, FLOOR));
        // two stacks under the floor: victim's one build stack of two doesn't get it either
        bag.add(build(Items.COBBLESTONE, 5));
        assertEquals(-1, DropRoom.shed(-1, bag, FLOOR));
        // a gate stack only goes while what's left still holds the floor
        assertEquals(rack, DropRoom.shed(-1, bag, 5));
        assertEquals(bag.size() - 1, DropRoom.shed(-1, bag, 41));
        // leaves and sand aren't the gate's, they go whatever the count
        bag.add(leaves(3));
        assertEquals(bag.size() - 1, DropRoom.shed(-1, bag, FLOOR));
    }

    @Test
    public void theErrandsStuffNeverGoes() {
        List<Stack> bag = bag(rest(Items.OAK_LOG, 1), rest(Items.OAK_LOG, 3), rest(Items.OAK_PLANKS, 2), rest(Items.BIRCH_PLANKS, 5),
                rest(Items.WOOL.white(), 43), rest(Items.WOOL.black(), 1), rest(Items.BED.white(), 1), rest(Items.BED.white(), 1));
        int log = 0;
        // the garbage pick calls a single log junk, the shed says no
        assertEquals(-1, DropRoom.shed(log, bag, FLOOR));
        assertEquals(-1, DropRoom.shed(-1, bag, FLOOR));
        // and something else does go
        bag.add(rest(Items.STRING, 2));
        assertEquals(bag.size() - 1, DropRoom.shed(log, bag, FLOOR));
    }

    @Test
    public void thePearlsVictimStillTakesASpareLogStack() {
        // victim() is the pearl's, the errand's holds are only the shed's
        List<Stack> bag = bag(rest(Items.OAK_LOG, 3), rest(Items.OAK_LOG, 1));
        assertEquals(1, DropRoom.victim(bag));
        assertEquals(-1, DropRoom.shed(-1, bag, FLOOR));
    }
}
