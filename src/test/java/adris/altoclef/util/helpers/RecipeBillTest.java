package adris.altoclef.util.helpers;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the bill over the real catalogue recipes with a pretend bag. every number here was worked out by hand from the recipes
// in TaskCatalogue, so if one of these moves, check the recipe before you "fix" the test
public class RecipeBillTest {

    private static final RecipeBill.World NOTHING_NEAR = new RecipeBill.World(false, false, 0, n -> false);
    private static final RecipeBill.World ALL_NEAR = new RecipeBill.World(true, true, 0, n -> false);

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static CatalogueBook bag(Object... pairs) {
        Map<Item, Integer> held = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) held.put((Item) pairs[i], (Integer) pairs[i + 1]);
        return new CatalogueBook(matches -> {
            int sum = 0;
            for (Item item : matches) sum += held.getOrDefault(item, 0);
            return sum;
        });
    }

    private static Map<String, Integer> want(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], (Integer) pairs[i + 1]);
        return out;
    }

    private static List<String> names(List<RecipeBill.Node> nodes) {
        List<String> out = new ArrayList<>();
        for (RecipeBill.Node n : nodes) out.add(n.name());
        return out;
    }

    @Test
    public void lanternChainAndBucketPoolTheirIron() {
        RecipeBill.Bill bill = RecipeBill.of(want("lantern", 1, "chain", 1, "bucket", 1), bag(), NOTHING_NEAR);
        // nuggets: 8 for the lantern + 2 for the chain = 10, two crafts of 9. ingots: 2 for those + 1 chain + 3 bucket = 6
        assertEquals(10, bill.need("iron_nugget"));
        assertEquals(6, bill.need("iron_ingot"));
        assertEquals(6, bill.need("raw_iron"));
        assertEquals(6, bill.smelts);
        // the torch: a coal and a stick. a stick is half of 2 planks, a crafting table is 4 more, 6 planks is 2 logs
        assertEquals(1, bill.need("crafting_table"));
        assertEquals(6, bill.need("planks"));
        assertEquals(2, bill.need("log"));
        // one furnace (8 cobble), and 6 smelts is one coal on top of the torch's
        assertEquals(1, bill.need("furnace"));
        assertEquals(8, bill.need("cobblestone"));
        assertEquals(2, bill.need("coal"));
        assertEquals(1, bill.fuelCoal);
        assertTrue(bill.table);
        assertTrue(bill.furnace);
        assertFalse(bill.blast);
        // fetched lowest pickaxe first
        assertEquals(List.of("log", "coal", "cobblestone", "raw_iron"), names(bill.leaves));
        assertTrue(bill.deferred.isEmpty());
    }

    @Test
    public void heldIngotsAndNuggetsComeOffOnce() {
        RecipeBill.Bill bill = RecipeBill.of(want("lantern", 1, "chain", 1, "bucket", 1),
                bag(Items.IRON_INGOT, 3, Items.IRON_NUGGET, 4), NOTHING_NEAR);
        // 10 nuggets wanted, 4 held: one craft, one ingot. ingots 1 + 1 + 3 = 5, 3 held: 2 raw iron
        assertEquals(1, bill.node("iron_nugget").runs());
        assertEquals(5, bill.need("iron_ingot"));
        assertEquals(2, bill.need("raw_iron"));
        assertEquals(2, bill.smelts);
    }

    @Test
    public void sixtyFourTorches() {
        RecipeBill.Bill bill = RecipeBill.of(want("torch", 64), bag(), ALL_NEAR);
        assertEquals(16, bill.need("coal"));
        assertEquals(16, bill.need("stick"));
        assertEquals(8, bill.need("planks"));
        assertEquals(2, bill.need("log"));
        // torches, sticks and planks all fit the 2x2, so no table for this one
        assertFalse(bill.table);
        assertEquals(0, bill.need("crafting_table"));
        // and no table is no table even when there's none around
        assertEquals(0, RecipeBill.of(want("torch", 64), bag(), NOTHING_NEAR).need("crafting_table"));
    }

    @Test
    public void aTableCraftWithNoTableAroundBringsOne() {
        RecipeBill.Bill bill = RecipeBill.of(want("chest", 1), bag(), NOTHING_NEAR);
        // 8 planks for the chest, 4 for the table, 12 planks is 3 logs
        assertEquals(1, bill.need("crafting_table"));
        assertEquals(12, bill.need("planks"));
        assertEquals(3, bill.need("log"));
        // with one standing nearby it's 2 logs
        assertEquals(2, RecipeBill.of(want("chest", 1), bag(), ALL_NEAR).need("log"));
        // one in the bag counts as the station
        assertEquals(2, RecipeBill.of(want("chest", 1), bag(Items.CRAFTING_TABLE, 1), NOTHING_NEAR).need("log"));
    }

    @Test
    public void stationsComeBeforeWhatNeedsThem() {
        RecipeBill.Bill bill = RecipeBill.of(want("bucket", 1), bag(), NOTHING_NEAR);
        assertTrue(bill.node("crafting_table").level() < bill.node("furnace").level());
        assertTrue(bill.node("furnace").level() < bill.node("iron_ingot").level());
        assertTrue(bill.node("iron_ingot").level() < bill.node("bucket").level());
    }

    @Test
    public void blastFurnacePartsOnlyWhenTheIronRunWouldBuildOne() {
        RecipeBill.World blasts = new RecipeBill.World(false, false, 0, n -> n >= 5);
        RecipeBill.Bill bill = RecipeBill.of(want("lantern", 1, "chain", 1, "bucket", 1), bag(), blasts);
        assertTrue(bill.blast);
        // the ingot target stays 6, the blast eats 5 more on the side
        assertEquals(6, bill.need("iron_ingot"));
        assertEquals(11, bill.need("raw_iron"));
        assertEquals(3, bill.need("smooth_stone"));
        assertEquals(3, bill.need("stone"));
        // a furnace to smelt in and one that goes into the blast furnace: 16 cobble, plus 3 for the stone
        assertEquals(2, bill.need("furnace"));
        assertEquals(19, bill.need("cobblestone"));
        // smelts: 6 iron + 5 for the blast + 3 stone + 3 smooth stone = 17, three coal, plus the torch's
        assertEquals(17, bill.smelts);
        assertEquals(4, bill.need("coal"));
        // the smooth stone and the furnace are there before the iron goes in
        assertTrue(bill.node("smooth_stone").level() < bill.node("iron_ingot").level());
        assertTrue(bill.node("furnace").level() < bill.node("iron_ingot").level());

        // 4 ingots isn't a blast furnace's worth
        RecipeBill.Bill few = RecipeBill.of(want("iron_ingot", 4), bag(), blasts);
        assertFalse(few.blast);
        assertEquals(4, few.need("raw_iron"));
        assertEquals(0, few.need("smooth_stone"));
        // and nothing to smelt is nothing to build, even for a big target
        RecipeBill.Bill held = RecipeBill.of(want("iron_ingot", 9), bag(Items.IRON_INGOT, 9), blasts);
        assertFalse(held.blast);
    }

    @Test
    public void fuelMath() {
        assertEquals(0, RecipeBill.fuelCoal(0, 0));
        assertEquals(1, RecipeBill.fuelCoal(1, 0));
        assertEquals(1, RecipeBill.fuelCoal(8, 0));
        assertEquals(2, RecipeBill.fuelCoal(9, 0));
        assertEquals(3, RecipeBill.fuelCoal(17, 0));
        // charcoal or whatever else is in the bag covers its share first
        assertEquals(0, RecipeBill.fuelCoal(8, 8));
        assertEquals(1, RecipeBill.fuelCoal(9, 8));
        // coal already in the bag nets off the coal node like any other leaf
        RecipeBill.Bill bill = RecipeBill.of(want("iron_ingot", 16), bag(Items.COAL, 1), ALL_NEAR);
        assertEquals(2, bill.need("coal"));
        assertEquals(1, bill.net("coal"));
    }

    @Test
    public void mobDropsAndNetherStuffAreNotFetchedUpFront() {
        RecipeBill.Bill bill = RecipeBill.of(want("blaze_powder", 2, "book", 1), bag(), ALL_NEAR);
        assertTrue(names(bill.deferred).contains("blaze_rod"));
        assertTrue(names(bill.deferred).contains("leather"));
        assertFalse(names(bill.leaves).contains("blaze_rod"));
        assertFalse(names(bill.leaves).contains("leather"));
        // the paper's sugar cane is a plain mine, that one is fine
        assertEquals(List.of("sugar_cane"), names(bill.leaves));
        assertEquals(3, bill.need("sugar_cane"));
        // quartz is a plain mine too, but it's in the nether
        assertEquals(-1, new CatalogueBook(m -> 0).gatherTier("quartz"));
        assertEquals(-1, new CatalogueBook(m -> 0).gatherTier("blaze_rod"));
        assertEquals(2, new CatalogueBook(m -> 0).gatherTier("raw_iron"));
    }

    @Test
    public void aliasesFindTheRealRecipe() {
        // chain has no iron_ prefix on this line, so it's just its own name
        assertEquals("chain", CatalogueBook.canonical(new ItemTarget("chain", 1)));
        assertEquals("wooden_pickaxe", CatalogueBook.canonical(new ItemTarget("wooden_pick", 1)));
        assertEquals("planks", CatalogueBook.canonical(new ItemTarget("planks", 1)));
        assertEquals("iron_ingot", CatalogueBook.canonical(new ItemTarget(Items.IRON_INGOT, 1)));
        assertNull(CatalogueBook.canonical(new ItemTarget("not_a_thing_at_all", 1)));
    }

    @Test
    public void aLoopEnds() {
        RecipeBill.Book loop = new RecipeBill.Book() {
            @Override
            public RecipeBill.Way wayOf(String name) {
                return switch (name) {
                    case "nugget" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT, Map.of("ingot", 1), 9);
                    case "ingot" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT, Map.of("block", 1), 9);
                    case "block" -> new RecipeBill.Way(RecipeBill.Kind.CRAFT, Map.of("nugget", 81), 1);
                    default -> null;
                };
            }

            @Override
            public int gatherTier(String name) {
                return 0;
            }

            @Override
            public int held(String name) {
                return 0;
            }
        };
        RecipeBill.Bill bill = RecipeBill.of(want("nugget", 10), loop, ALL_NEAR);
        assertEquals(10, bill.need("nugget"));
        assertEquals(2, bill.need("ingot"));
        // the one that would close the loop is a leaf now
        assertNotNull(bill.node("block"));
        assertNull(bill.node("block").kind());
    }

    private static RecipeBill.Node leaf(String name, int gross, int held, int tier) {
        return new RecipeBill.Node(name, null, gross, held, 0, 0, tier);
    }

    private static RecipeBill.ToolBag tools(int pick, int axe, int shovel) {
        return new RecipeBill.ToolBag() {
            public int pickTier() {
                return pick;
            }

            public int axeTier() {
                return axe;
            }

            public int shovelTier() {
                return shovel;
            }
        };
    }

    @Test
    public void toolsTheJobCallsFor() {
        RecipeBill.ToolBag none = tools(-1, -1, -1);
        assertEquals(List.of("wooden_axe"), RecipeBill.tools(List.of(leaf("log", 20, 0, 0)), none));
        assertEquals(List.of("stone_axe"), RecipeBill.tools(List.of(leaf("log", 40, 0, 0)), none));
        // 20 in the bill but 15 already held is 5 to chop, a fist does that
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("log", 20, 15, 0)), none));
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("sand", 10, 0, 0)), none));
        assertEquals(List.of("stone_shovel"), RecipeBill.tools(List.of(leaf("dirt", 40, 0, 0)), none));
        // iron ore wants a stone pick, and with that coming the axe is stone too
        assertEquals(List.of("stone_pickaxe", "stone_axe"),
                RecipeBill.tools(List.of(leaf("log", 20, 0, 0), leaf("raw_iron", 6, 0, 2)), none));
        // a stone pick in the bag already
        assertEquals(List.of("stone_axe"), RecipeBill.tools(List.of(leaf("log", 10, 0, 0)), tools(2, -1, -1)));
        // and one that's good enough is left alone
        assertEquals(List.of(), RecipeBill.tools(List.of(leaf("log", 10, 0, 0), leaf("raw_iron", 6, 0, 2)), tools(3, 2, -1)));
        // cobble is a wooden pick and nothing else
        assertEquals(List.of("wooden_pickaxe"), RecipeBill.tools(List.of(leaf("cobblestone", 8, 0, 1)), none));
    }

    @Test
    public void theBagCapCutsInLeafOrder() {
        List<RecipeBill.Node> leaves = List.of(leaf("log", 2, 0, 0), leaf("cobblestone", 200, 0, 1), leaf("raw_iron", 6, 0, 2));
        List<RecipeBill.Fetch> all = RecipeBill.fitBag(leaves, 10, n -> 64);
        assertEquals(3, all.size());
        assertEquals(200, all.get(1).target());
        // 3 slots: a log stack, then two stacks of cobble out of the four it wanted, and the iron waits
        List<RecipeBill.Fetch> cut = RecipeBill.fitBag(leaves, 3, n -> 64);
        assertEquals(2, cut.size());
        assertEquals("log", cut.get(0).name());
        assertEquals("cobblestone", cut.get(1).name());
        assertEquals(128, cut.get(1).target());
        // a half stack already held tops up into its own stack for free
        List<RecipeBill.Fetch> topUp = RecipeBill.fitBag(List.of(leaf("cobblestone", 60, 10, 1)), 0, n -> 64);
        assertEquals(60, topUp.get(0).target());
        assertTrue(RecipeBill.fitBag(leaves, 0, n -> 64).isEmpty());
    }

    @Test
    public void everyEdgeInputIsACatalogueName() {
        // an edge pointing at a name nobody registered would quietly turn into a deferred leaf
        for (String name : TaskCatalogue.resourceNames()) {
            TaskCatalogue.BillEdge edge = TaskCatalogue.billEdge(name);
            if (edge != null) {
                assertTrue(name + " <- " + edge.input(), TaskCatalogue.taskExists(edge.input()));
            }
        }
    }
}
