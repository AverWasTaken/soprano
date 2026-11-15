package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.DropRoom.Stack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// END_PREP's gear steps with the beds done and a bag with nothing the garbage pick calls junk (DropRoom.shedGear)
public class GearShedTest {

    // the gear step's buildBlocks out of the box
    private static final int FLOOR = 64;
    private static final int BEDS = 9;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    // a full END_PREP bag after the bed errand: nine beds, 34 wool over four colours, kit and 23 blocks, not one free slot
    private static List<ItemStack> full() {
        Object[][] rows = {
                {Items.ENDER_EYE, 3}, {Items.BED.white(), 1}, {Items.WOOL.white(), 18}, {Items.COAL, 11}, {Items.FLINT, 1},
                {Items.BED.white(), 1}, {Items.BED.white(), 1}, {Items.BED.white(), 1}, {Items.WOOL.lightGray(), 4}, {Items.BLAZE_ROD, 1},
                {Items.BUCKET, 2}, {Items.BED.white(), 1}, {Items.SHEARS, 1}, {Items.BED.white(), 1}, {Items.WOOL.gray(), 5},
                {Items.WOOL.black(), 7}, {Items.APPLE, 2}, {Items.COOKED_PORKCHOP, 2}, {Items.FLINT_AND_STEEL, 1}, {Items.FURNACE, 5},
                {Items.WATER_BUCKET, 1}, {Items.SMOKER, 2}, {Items.BED.white(), 1}, {Items.IRON_HELMET, 1}, {Items.STONE_SWORD, 1},
                {Items.IRON_SWORD, 1}, {Items.BIRCH_BOAT, 1}, {Items.IRON_PICKAXE, 1}, {Items.STONE_PICKAXE, 1}, {Items.STONE_PICKAXE, 1},
                {Items.LADDER, 4}, {Items.NETHERRACK, 22}, {Items.COBBLESTONE, 1}, {Items.CRAFTING_TABLE, 2}, {Items.BED.white(), 1},
                {Items.BED.white(), 1}};
        List<ItemStack> stacks = new ArrayList<>();
        for (Object[] row : rows) {
            stacks.add(new ItemStack((Item) row[0], (Integer) row[1]));
        }
        return stacks;
    }

    // what the view would hand over: the End's beds read off the bag, the helmet is one we'd wear, eyes are important, the little
    // food sits under the floor, the rock is the gate's. woolTier lets a test call the wool plain enough for the victim pick
    private static List<Stack> view(List<ItemStack> stacks, int reserveBeds, int woolTier) {
        boolean[] end = DropRoom.reserved(stacks, reserveBeds);
        List<Stack> bag = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack st = stacks.get(i);
            Item item = st.getItem();
            boolean rock = item == Items.NETHERRACK || item == Items.COBBLESTONE;
            boolean keep = end[i] || item == Items.ENDER_EYE || item == Items.IRON_HELMET || item == Items.APPLE || item == Items.COOKED_PORKCHOP;
            int tier = ItemValue.tier(false, false, ItemValue.isCore(item), !rock);
            if (in(ItemHelper.WOOL, item)) tier = woolTier;
            // string is the one plain thing in here, nothing on the gamer's list
            if (item == Items.STRING) tier = ItemValue.REST;
            bag.add(new Stack(st, st.isEmpty() ? ItemValue.REST : tier, keep, rock, rock));
        }
        return bag;
    }

    private static boolean in(Item[] items, Item item) {
        for (Item i : items) {
            if (i == item) return true;
        }
        return false;
    }

    // throws until nothing may go, the order back
    private static List<Item> shedAll(List<ItemStack> stacks, int reserveBeds, int woolTier, boolean gear) {
        List<Item> thrown = new ArrayList<>();
        while (true) {
            List<Stack> bag = view(stacks, reserveBeds, woolTier);
            int i = gear ? DropRoom.shedGear(-1, bag, FLOOR) : DropRoom.shed(-1, bag, 48);
            if (i < 0) return thrown;
            thrown.add(stacks.get(i).getItem());
            stacks.set(i, ItemStack.EMPTY);
        }
    }

    private static int wool(List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack st : stacks) {
            if (in(ItemHelper.WOOL, st.getItem())) n += st.getCount();
        }
        return n;
    }

    private static void assertKeptTheEnd(List<Item> thrown) {
        for (Item item : thrown) {
            assertFalse("threw a bed", DropRoom.isBed(item));
            assertFalse(item.toString(), item == Items.ENDER_EYE || item == Items.BLAZE_ROD || item == Items.IRON_SWORD
                    || item == Items.IRON_PICKAXE || item == Items.IRON_HELMET || item == Items.WATER_BUCKET || item == Items.FLINT_AND_STEEL
                    || item == Items.NETHERRACK || item == Items.COBBLESTONE || item == Items.CRAFTING_TABLE || item == Items.BUCKET);
        }
    }

    @Test
    public void theFullBagLetsTheSpareWoolGoFirstThenTheWorseTools() {
        List<ItemStack> stacks = full();
        assertEquals(36, stacks.size());
        List<Item> thrown = shedAll(stacks, BEDS, ItemValue.KIT, true);
        assertEquals(List.of(Items.WOOL.lightGray(), Items.WOOL.gray(), Items.WOOL.black(), Items.STONE_SWORD, Items.STONE_PICKAXE,
                Items.STONE_PICKAXE, Items.FURNACE, Items.SHEARS, Items.COAL), thrown);
        assertKeptTheEnd(thrown);
        assertTrue(wool(stacks) >= 3);
    }

    @Test
    public void noBedGoesEvenWithNothingReserved() {
        List<ItemStack> stacks = full();
        List<Item> thrown = shedAll(stacks, 0, ItemValue.KIT, true);
        assertKeptTheEnd(thrown);
        int beds = 0;
        for (ItemStack st : stacks) {
            if (DropRoom.isBed(st.getItem())) beds++;
        }
        assertEquals(BEDS, beds);
    }

    @Test
    public void plainWoolStillKeepsABedsThree() {
        // REST tier wool is fair game for the plain pick, the hold is what keeps the last bed's worth
        List<ItemStack> stacks = full();
        List<Item> thrown = shedAll(stacks, BEDS, ItemValue.REST, true);
        assertKeptTheEnd(thrown);
        assertTrue(wool(stacks) >= 3);
        // and with only scraps left, two stacks that make one bed between them stay
        List<ItemStack> scraps = new ArrayList<>(List.of(new ItemStack(Items.WOOL.red(), 2), new ItemStack(Items.WOOL.blue(), 2),
                new ItemStack(Items.STRING, 1)));
        assertEquals(List.of(Items.STRING), shedAll(scraps, 0, ItemValue.REST, true));
    }

    @Test
    public void theGateBlocksStayUnderTheStepsCount() {
        // 30 + 22 + 1 = 53: under the step's 64 every block stays and the wool goes. the bed errand's floor of 48 hands the
        // cobble over, that's the 48 the gear step must not use while it digs on to 64
        List<ItemStack> stacks = full();
        stacks.set(3, new ItemStack(Items.NETHERRACK, 30));
        List<Stack> bag = view(stacks, BEDS, ItemValue.KIT);
        assertEquals(Items.WOOL.lightGray(), stacks.get(DropRoom.shedGear(-1, bag, FLOOR)).getItem());
        assertEquals(32, DropRoom.shed(-1, bag, 48));
        // past 64 the spare goes first, the biggest one the count can spare: 64 + 30 + 22 + 1, the 64 would leave 53
        stacks.set(5, new ItemStack(Items.NETHERRACK, 64));
        assertEquals(3, DropRoom.shedGear(-1, view(stacks, 0, ItemValue.KIT), FLOOR));
    }

    @Test
    public void theBedErrandStillHoldsAllItsWool() {
        List<ItemStack> stacks = full();
        List<Item> thrown = shedAll(stacks, BEDS, ItemValue.KIT, false);
        for (Item item : thrown) {
            assertFalse(item.toString(), in(ItemHelper.WOOL, item) || DropRoom.isBed(item));
        }
        assertEquals(34, wool(stacks));
    }

    @Test
    public void theGarbagePickStillGoesBeforeTheWool() {
        List<ItemStack> stacks = full();
        int coal = 3;
        assertEquals(coal, DropRoom.shedGear(coal, view(stacks, BEDS, ItemValue.KIT), FLOOR));
        // a garbage pick on a held stack doesn't count
        assertEquals(8, DropRoom.shedGear(31, view(stacks, BEDS, ItemValue.KIT), FLOOR));
    }
}
