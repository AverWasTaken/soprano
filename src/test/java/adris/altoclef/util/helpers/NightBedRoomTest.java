package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

// the night bed's pickup with a full bag: the stock garbage pick first, then a build stack the night's floor can spare
// (DropRoom.shedBlocks, OverworldConfig.nightBedShedFloor) and nothing past that
public class NightBedRoomTest {

    private static final int NIGHT_FLOOR = 32;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static Stack kept(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.KIT, true, false);
    }

    // a block we bridge with that END_PREP's gate counts
    private static Stack blocks(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.REST, false, true, true);
    }

    // a full bag of stuff nobody may throw, plus whatever the test adds
    private static List<Stack> keepers(Stack... more) {
        List<Stack> bag = new ArrayList<>();
        for (Item item : new Item[]{Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.OBSIDIAN, Items.SHIELD, Items.BUCKET,
                Items.IRON_PICKAXE, Items.COOKED_BEEF, Items.FLINT_AND_STEEL, Items.CRAFTING_TABLE, Items.BED.white()}) {
            bag.add(kept(item, 1));
        }
        bag.addAll(List.of(more));
        return bag;
    }

    // what GarbageView.room does for a bed: no fit, the garbage pick as given, beds are not core
    private static Decision stock(int garbagePick, List<Stack> bag) {
        Item bed = Items.BED.white();
        return DropRoom.decide(false, garbagePick, false, false, ItemValue.isCore(bed), bag);
    }

    @Test
    public void aBedIsNotCoreSoOnlyGarbageOrTheShedMakeRoom() {
        assertFalse(ItemValue.isCore(Items.BED.white()));
    }

    // GarbageRules' view of the same bag: keepers never go, blocks go once the bag is past the reserve (protectsCore)
    private static List<GarbageRules.Entry> entries(List<Stack> bag, int reserve) {
        java.util.Map<Item, Integer> ranks = GarbageRules.throwRanks(new Item[]{Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK});
        int blocks = 0;
        for (Stack s : bag) {
            if (ranks.containsKey(s.stack().getItem())) blocks += s.stack().getCount();
        }
        List<GarbageRules.Entry> out = new ArrayList<>();
        for (Stack s : bag) {
            Item item = s.stack().getItem();
            boolean mayGo = !s.keep() && !GarbageRules.protectsCore(ItemHelper.isCoreBuildBlock(item), blocks, reserve);
            out.add(new GarbageRules.Entry(s.stack(), mayGo, ranks.getOrDefault(item, -1)));
        }
        return out;
    }

    private static int garbagePick(List<Stack> bag) {
        return GarbageRules.nextPick(entries(bag, 64), 64, true, 0, 0);
    }

    @Test
    public void netherrackPastTheReserveIsTheGarbagePickAndGoesForTheBed() {
        List<Stack> bag = keepers(blocks(Items.NETHERRACK, 64), blocks(Items.NETHERRACK, 64));
        int first = bag.size() - 2;
        assertEquals(first, garbagePick(bag));
        assertEquals(new Decision(Kind.EVICT, Source.GARBAGE, first), stock(garbagePick(bag), bag));
    }

    @Test
    public void blocksUnderTheReserveAreNobodysGarbageSoTheShedHasToAnswer() {
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 36), blocks(Items.DIRT, 20));
        assertEquals(-1, garbagePick(bag));
        assertEquals(Decision.NO, stock(-1, bag));
        // the cobble would leave 20, the dirt leaves 36
        assertEquals(bag.size() - 1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void noGarbageIsAStockNo() {
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 64), blocks(Items.COBBLESTONE, 40));
        assertEquals(Decision.NO, stock(-1, bag));
    }

    @Test
    public void theShedThrowsTheBigCobbleStackWhileTheFloorHolds() {
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 40), blocks(Items.COBBLESTONE, 64));
        int big = bag.size() - 1;
        assertEquals(big, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void theShedStopsAtTheFloor() {
        // the 64 already went, a lone 40 is all the blocks we have and throwing it leaves 0
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 40), blocks(Items.COBBLESTONE, 64));
        bag.set(bag.size() - 1, new Stack(ItemStack.EMPTY, ItemValue.REST, false, false));
        assertEquals(-1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void exactlyTheFloorLeftIsStillAThrow() {
        // 32 cobble + 30 dirt: the dirt goes and leaves 32 on the dot, the cobble would leave 30
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 32), blocks(Items.DIRT, 30));
        assertEquals(bag.size() - 1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void belowTheFloorNothingGoes() {
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 20), blocks(Items.DIRT, 10));
        assertEquals(-1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void theNightsFloorLetsTheBigStackGoWhereTheEndsWouldNot() {
        // 40 + 64. the End's 48 can only spare the 40 (the 64 would leave 40), the night's 32 throws the 64
        List<Stack> bag = keepers(blocks(Items.COBBLESTONE, 40), blocks(Items.COBBLESTONE, 64));
        assertEquals(bag.size() - 2, DropRoom.shed(-1, bag, 48));
        assertEquals(bag.size() - 1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void bedsAndTheirMakingsNeverGo() {
        List<Stack> bag = keepers(new Stack(new ItemStack(Items.OAK_LOG, 3), ItemValue.REST, false, true, false),
                new Stack(new ItemStack(Items.WOOL.white(), 3), ItemValue.REST, false, false));
        assertEquals(-1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    // kit nobody marked keep: what the full shed would happily throw after the blocks
    private static Stack spare(Item item, int count) {
        return new Stack(new ItemStack(item, count), ItemValue.KIT, false, false);
    }

    @Test
    public void onlyKitAndBlocksAtTheFloorLeavesTheBed() {
        // 20 cobble + 12 dirt is the floor on the dot, either throw goes under it. the End's shed would move on to the spare
        // pickaxe or the late list, the night's never does
        List<Stack> bag = keepers(spare(Items.STONE_PICKAXE, 1), spare(Items.STONE_PICKAXE, 1), spare(Items.RAW_GOLD, 9),
                spare(Items.FURNACE, 1), spare(Items.SMOKER, 1), spare(Items.SHEARS, 1), spare(Items.COAL, 5),
                blocks(Items.COBBLESTONE, 20), blocks(Items.DIRT, 12));
        assertTrue(DropRoom.shed(-1, bag, NIGHT_FLOOR) >= 0);
        assertEquals(-1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void onlyKitIsANo() {
        List<Stack> bag = keepers(spare(Items.RAW_GOLD, 9), spare(Items.FURNACE, 1), spare(Items.SMOKER, 1), spare(Items.SHEARS, 1));
        assertEquals(-1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void eightyCobbleGoesDownToTheFloorAndNoFurther() {
        // 64 + 16: the 16 goes (64 left), then the 64 would leave nothing. the raw gold sits there the whole time
        List<Stack> bag = keepers(spare(Items.RAW_GOLD, 9), blocks(Items.COBBLESTONE, 64), blocks(Items.COBBLESTONE, 16));
        int small = bag.size() - 1;
        assertEquals(small, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
        bag.set(small, new Stack(ItemStack.EMPTY, ItemValue.REST, false, false));
        assertEquals(-1, DropRoom.shedBlocks(bag, NIGHT_FLOOR));
    }

    @Test
    public void aKeptBuildStackNeverGoes() {
        // GarbageView.shedBlocks marks the protected list keep, the throw itself has no veto. floor 0 so only the keep says no
        List<Stack> bag = keepers(new Stack(new ItemStack(Items.COBBLESTONE, 64), ItemValue.REST, true, true, true));
        assertEquals(-1, DropRoom.shedBlocks(bag, 0));
        bag.add(blocks(Items.DIRT, 10));
        assertEquals(bag.size() - 1, DropRoom.shedBlocks(bag, 0));
    }
}
