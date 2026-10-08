package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import baritone.api.utils.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CookGateTest {
    private static final KitNeed ORE = new KitNeed("iron_ingot", 39);
    private static final KitNeed CRAFT = new KitNeed("iron_pickaxe", 1);
    private static final KitNeed COOK = new KitNeed(KitNeed.COOK_FURNACE, CookGate.MIN_RAW);

    private OverworldConfig cfg;
    private FakeFacts f;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
        // no iron owed unless a test says so: while it is, the furnace is the iron's and meat only goes in a smoker
        cfg.ironKit.clear();
        cfg.armorPlan = OverworldConfig.ArmorPlan.NONE;
        f = new FakeFacts();
    }

    private KitNeed need() {
        return CookGate.need(f, cfg, 8);
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    @Test
    public void rawMeatAndAFreeFurnaceIsACook() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        assertEquals(COOK, need());
        // fish and the rest count, a raw potato does not
        FakeFacts fish = new FakeFacts().give(Items.SALMON, 3).give(Items.COAL, 2);
        fish.furnacePlaced = true;
        assertEquals(COOK, CookGate.need(fish, cfg, 8));
        FakeFacts spuds = new FakeFacts().give(Items.POTATO, 9).give(Items.COAL, 2);
        spuds.furnacePlaced = true;
        assertNull(CookGate.need(spuds, cfg, 8));
    }

    @Test
    public void aSmokerWinsOverAFurnace() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // one in the bag counts too
        FakeFacts bag = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.SMOKER, 1);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(bag, cfg, 8).catalogueName());
    }

    @Test
    public void aFurnaceInTheBagIsAFurnace() {
        f.give(Items.BEEF, 4).give(Items.COAL, 1).give(Items.FURNACE, 1);
        assertEquals(KitNeed.COOK_FURNACE, need().catalogueName());
    }

    @Test
    public void nothingToCookIsNoNeed() {
        f.give(Items.COAL, 2).give(Items.COOKED_MUTTON, 9);
        f.furnacePlaced = true;
        assertNull(need());
    }

    @Test
    public void aHandfulOnlyCooksWhereAStationAlreadyStands() {
        // two rabbit and nothing built: not worth a smoker
        f.give(Items.RABBIT, 2).give(Items.COAL, 2).give(Items.COBBLESTONE, 40).give(Items.OAK_LOG, 8);
        assertNull(need());
        // with one standing it is the last thing before the nether
        f.furnacePlaced = true;
        assertEquals(COOK, need());
    }

    @Test
    public void aHandfulDoesNotPutTheBagFurnaceDown() {
        // place, cook, walk back, pick up, all for two rabbit
        f.give(Items.RABBIT, 2).give(Items.COAL, 2).give(Items.FURNACE, 1);
        assertNull(need());
        f.give(Items.SMOKER, 1);
        assertNull(need());
        // a smoker standing is free though
        f.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    @Test
    public void aRunningCookKeepsItsStation() {
        // the smoker craft ate the logs and the cobble that picked "smoker": still a smoker, not a furnace or nothing
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.cookStation = "smoker";
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        f.cookStation = "furnace";
        assertEquals(KitNeed.COOK_FURNACE, need().catalogueName());
        // but never onto iron, that one input slot is still the iron's
        f.cooking("iron_ingot", 12, 60);
        assertNull(need());
    }

    @Test
    public void theSmokersLogsAreNotFuel() {
        // the whole kit done, so the reserve keeps no wood and every log may burn (1.5 smelts each)
        FakeFacts wood = new FakeFacts();
        for (net.minecraft.world.item.Item i : new net.minecraft.world.item.Item[]{Items.WOODEN_AXE, Items.STONE_PICKAXE, Items.STONE_AXE,
                Items.IRON_PICKAXE, Items.IRON_AXE, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.FURNACE}) {
            wood.give(i, 1);
        }
        wood.give(Items.BUCKET, 2).give(Items.LADDER, 3).give(Items.OAK_LOG, 8);
        assertEquals(12, CookGate.fuelSmelts(wood, cfg, 0));
        // nine mutton: twelve smelts of logs looks like enough, but five logs (four and the table's) are about to be a smoker,
        // and the three left burn for four
        wood.give(Items.MUTTON, 9).give(Items.COBBLESTONE, 60);
        wood.cooking("iron_ingot", 12, 60);
        assertEquals(CookGate.Station.SMOKER, CookGate.station(wood, cfg, true));
        assertNull(CookGate.need(wood, cfg, 0));
        // with a coal it is fine
        wood.give(Items.COAL, 1);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(wood, cfg, 0).catalogueName());
    }

    @Test
    public void aSmokerWithoutATableCostsALogMore() {
        // four logs is the smoker, the table to craft it on is the fifth
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 4).give(Items.COBBLESTONE, 60);
        f.cooking("iron_ingot", 12, 60);
        assertNull(need());
        f.tablePlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
    }

    @Test
    public void enoughMeatBuildsAStationButNotOutOfTheStoneFloor() {
        // the stone pick still wants its cobble: the furnace and the tools come first
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 8).give(Items.COBBLESTONE, 10);
        assertNull(need());
        // plenty over the floor: a smoker (logs for it, cobble for its furnace)
        f.give(Items.COBBLESTONE, 40);
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // no logs for a smoker, so a furnace
        FakeFacts nologs = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.COBBLESTONE, 50);
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(nologs, cfg, 8).catalogueName());
    }

    @Test
    public void meatQueuesBehindIronInTheOnlyFurnace() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.cooking("iron_ingot", 12, 60);
        // one input slot, loading meat would swap the ore back out
        assertNull(need());
        // a smoker (even one we still have to make) is the way round
        f.give(Items.OAK_LOG, 8).give(Items.COBBLESTONE, 60);
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // and a smoker that is already there is free
        FakeFacts standing = new FakeFacts().give(Items.MUTTON, 6).give(Items.COAL, 2);
        standing.furnacePlaced = true;
        standing.smokerPlaced = true;
        standing.cooking("iron_ingot", 12, 60);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(standing, cfg, 8).catalogueName());
    }

    @Test
    public void foodAlreadyCookingBlocksTheNextBatch() {
        f.give(Items.MUTTON, 3).give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        f.smokerPlaced = true;
        f.cookingFood("cooked_mutton", 6, 6, 30);
        assertNull(need());
    }

    @Test
    public void noFuelNoCook() {
        f.give(Items.MUTTON, 6);
        f.furnacePlaced = true;
        assertNull(need());
        // coal is 8 smelts, six meat is one
        f.give(Items.COAL, 1);
        assertEquals(COOK, need());
        // wood only burns above what the kit keeps for crafting: a handful of logs do not count while the wooden tools are owed
        FakeFacts wood = new FakeFacts().give(Items.MUTTON, 6).give(Items.OAK_LOG, 1);
        wood.furnacePlaced = true;
        assertNull(CookGate.need(wood, cfg, 8));
    }

    @Test
    public void notInTheNetherAndNotAfterGivingUp() {
        f.give(Items.MUTTON, 6).give(Items.COAL, 2);
        f.furnacePlaced = true;
        f.cookSuspended = true;
        assertNull(need());
        f.cookSuspended = false;
        f.dimension = Dimension.NETHER;
        assertNull(need());
    }

    @Test
    public void theCookIsTheLastNeedOfThePlan() {
        // this is the "never leaves the overworld raw" half: IRON ends when the plan is empty, and it is not
        f.give(Items.MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 30);
        f.furnacePlaced = true;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(KitNeed.COOK_FURNACE, plan.get(plan.size() - 1).catalogueName());
        assertEquals(1, names(plan).stream().filter(n -> n.startsWith("cook")).count());
        // GATHER does not wait out the cook, IRON starts it on the surface and collects it on the way
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).stream().noneMatch(n -> n.startsWith("cook")));
        // and cooked it is gone from both
        FakeFacts cooked = new FakeFacts().give(Items.COOKED_MUTTON, 6).give(Items.COAL, 2).give(Items.OAK_LOG, 30);
        cooked.furnacePlaced = true;
        assertTrue(names(KitPlanner.plan(cooked, cfg, 8)).stream().noneMatch(n -> n.startsWith("cook")));
    }

    @Test
    public void aFurnaceCookingTheMeatIsNotAReasonToCraftAnother() {
        f.give(Items.OAK_LOG, 30).give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2).give(Items.STONE_AXE, 1);
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
        // it went down to cook and it is coming back to the bag
        f.furnacePlaced = true;
        assertFalse(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
    }

    @Test
    public void aCookIsNotACraftSoItNeverHoldsTheTable() {
        assertTrue(COOK.isGathering());
        assertFalse(COOK.isCraft());
        assertTrue(new KitNeed(KitNeed.COOK_SMOKER, 3).isSpecial());
        // and it counts as the furnace being used again, so the furnace we are cooking in is not picked up under us
        assertTrue(OwnTables.smeltsSoon(KitNeed.COOK_FURNACE, 0));
        assertTrue(OwnTables.smeltsSoon(KitNeed.COOK_SMOKER, 0));
        assertFalse(OwnTables.wantsFurnaceBack("food", KitNeed.COOK_FURNACE, OwnTables.smeltsSoon(KitNeed.COOK_FURNACE, 0)));
    }

    @Test
    public void progressIsTheMeatLeavingTheBag() {
        f.give(Items.MUTTON, 6);
        int before = KitPlanner.progressOf(f, COOK);
        FakeFacts less = new FakeFacts().give(Items.MUTTON, 2);
        assertTrue(KitPlanner.progressOf(less, COOK) > before);
    }

    @Test
    public void earlyStartOnTheSurfaceOrBetweenJobsButNeverInFrontOfOre() {
        List<KitNeed> underMine = List.of(ORE, CRAFT, COOK);
        // standing in a vein: the mine goes first, the cook keeps the end of the plan
        assertFalse(CookGate.leads(underMine, 2, 6, false, false));
        // on the surface it goes first
        assertTrue(CookGate.leads(underMine, 2, 6, true, false));
        // the next job is not ore (a craft at the table): between needs, go
        assertTrue(CookGate.leads(List.of(CRAFT, ORE, COOK), 2, 6, false, false));
        // a start that happened carries on, walking over the heightmap line does not undo it
        assertTrue(CookGate.leads(underMine, 2, 6, false, true));
        // a handful is only the closing need
        assertFalse(CookGate.leads(underMine, 2, 2, true, false));
        assertFalse(CookGate.leads(underMine, 2, 2, true, true));
    }

    @Test
    public void leadingMovesItToTheFrontBehindALeadingFood() {
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        assertEquals(List.of(COOK, ORE, CRAFT), CookGate.lead(List.of(ORE, CRAFT, COOK), 2));
        assertEquals(List.of(food, COOK, ORE), CookGate.lead(List.of(food, ORE, COOK), 2));
        assertEquals(2, CookGate.index(List.of(ORE, CRAFT, COOK)));
        assertEquals(-1, CookGate.index(List.of(ORE, CRAFT)));
    }

    @Test
    public void reusingTheFurnaceWeJustEmptiedNeedsEnoughMeatAndFuel() {
        f.give(Items.MUTTON, 4).give(Items.COAL, 1);
        assertTrue(CookGate.reusable(f, cfg, 8));
        // a pair of rabbit is not worth holding the furnace for
        assertFalse(CookGate.reusable(new FakeFacts().give(Items.RABBIT, 2).give(Items.COAL, 1), cfg, 8));
        assertFalse(CookGate.reusable(new FakeFacts().give(Items.MUTTON, 4), cfg, 8));
        f.cookSuspended = true;
        assertFalse(CookGate.reusable(f, cfg, 8));
    }

    // ---- the GATHER -> IRON boundary: 22:05:29, a bag of raw pork, chicken and mutton, a furnace and no fuel, and the cook
    // never came up. the fuel gate wanted a coal per piece of meat in the whole bag and the gather chops exactly its budget

    private FakeFacts boundary() {
        FakeFacts b = new FakeFacts();
        b.give(Items.PORKCHOP, 4).give(Items.CHICKEN, 3).give(Items.MUTTON, 3).give(Items.FURNACE, 1).give(Items.CRAFTING_TABLE, 1);
        b.give(Items.WOODEN_AXE, 1).give(Items.STONE_PICKAXE, 2).give(Items.STONE_AXE, 1);
        b.foodUnits = 90;
        return b;
    }

    @Test
    public void theFirstLoadIsTheBiggestPileNotTheWholeBag() {
        // ten raw in three kinds and one coal (8 smelts): the first load is the 4 pork, the rest waits for it to be collected
        FakeFacts b = boundary().give(Items.COAL, 1);
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(b, cfg, 8).catalogueName());
        assertEquals(4, CookGate.pile(b));
        assertEquals(10, CookGate.raw(b));
        // and with fuel for three smelts the pile does not fit
        FakeFacts less = boundary().give(Items.OAK_LOG, 2);
        assertNull(CookGate.need(less, cfg, 8));
    }

    @Test
    public void whileIronIsOwedTheMeatGoesInASmokerNotTheKitFurnace() {
        OverworldConfig full = new OverworldConfig();
        FakeFacts b = boundary().give(Items.COAL, 2);
        assertTrue(CookGate.ironOwed(b, full));
        // the furnace in the bag is the iron's, and nothing to build a smoker from: no cook yet
        assertNull(CookGate.need(b, full, 8));
        // with the logs for a smoker (and the furnace it is made from, already in the bag) the meat gets its own
        b.give(Items.OAK_LOG, 8);
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(b, full, 8).catalogueName());
        // a smoker standing is free, same as always
        FakeFacts standing = boundary().give(Items.COAL, 2);
        standing.smokerPlaced = true;
        assertEquals(KitNeed.COOK_SMOKER, CookGate.need(standing, full, 8).catalogueName());
        // and a furnace standing next to us is not a place for meat while the ore is still to come
        FakeFacts placed = boundary().give(Items.COAL, 2);
        placed.furnacePlaced = true;
        assertNull(CookGate.need(placed, full, 8));
    }

    @Test
    public void onceTheIronIsSmeltedTheFurnaceIsFreeForTheMeat() {
        OverworldConfig full = new OverworldConfig();
        FakeFacts b = boundary().give(Items.COAL, 2);
        // all the ingots the kit wants, in the bag
        b.give(Items.IRON_INGOT, KitPlanner.ingotsNeeded(b, full));
        assertFalse(CookGate.ironOwed(b, full));
        assertEquals(KitNeed.COOK_FURNACE, CookGate.need(b, full, 8).catalogueName());
    }

    @Test
    public void aCookMidLoadKeepsItsNeedWhenTheLastPileLeavesTheBag() {
        // everything is in the smoker's slot, the bag has no raw meat and the job is not recorded yet
        f.give(Items.COAL, 2).give(Items.COOKED_MUTTON, 2);
        f.cookStation = "smoker";
        assertEquals(KitNeed.COOK_SMOKER, need().catalogueName());
        // no cook running and nothing raw is still no need
        f.cookStation = null;
        assertNull(need());
    }

    @Test
    public void gatherChopsFuelForTheCookOnTopOfItsBudget() {
        OverworldConfig full = new OverworldConfig();
        FakeFacts none = new FakeFacts();
        int base = KitPlanner.woodNeed(none, full, 8);
        assertTrue(base > 0);
        // a fresh gather: its budget and the fuel, in the one log need
        assertEquals(full.cookFuelLogs, KitPlanner.cookFuelLogs(none, full, 8, base));
        KitNeed logs = KitPlanner.gather(none, full, 8).stream().filter(n -> n.catalogueName().equals("log")).reduce((a, b) -> b).orElseThrow();
        assertEquals(base + full.cookFuelLogs, logs.count());
        // the setting off is the old gather
        OverworldConfig off = new OverworldConfig();
        off.cookFuelLogs = 0;
        assertEquals(0, KitPlanner.cookFuelLogs(none, off, 8, base));
    }

    @Test
    public void theFuelLogsStopOnceTheyAreChoppedAndOnceTheFoodIsIn() {
        OverworldConfig full = new OverworldConfig();
        // the budget met and plenty spare on top: nothing more to chop
        FakeFacts rich = new FakeFacts().give(Items.OAK_LOG, 60);
        assertEquals(0, KitPlanner.woodNeed(rich, full, 8));
        assertEquals(0, KitPlanner.cookFuelLogs(rich, full, 8, 0));
        // food gathered: no logs asked for the cook, whatever the bag has (cooking them must not send the gather back out)
        FakeFacts fed = new FakeFacts();
        fed.foodUnits = 70;
        assertEquals(0, KitPlanner.cookFuelLogs(fed, full, 8, 5));
        // and meat already in the bag means the fuel decision has been made
        FakeFacts meat = new FakeFacts().give(Items.PORKCHOP, 3);
        assertEquals(0, KitPlanner.cookFuelLogs(meat, full, 8, 5));
    }
}
