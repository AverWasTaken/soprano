package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig.ArmorPlan;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KitPlannerTest {
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
        // these are the wood budget's tests; the fuel logs on top of it for the cook are in CookGateTest
        cfg.cookFuelLogs = 0;
        f = new FakeFacts();
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    private static KitNeed find(List<KitNeed> needs, String name) {
        return needs.stream().filter(n -> n.catalogueName().equals(name)).findFirst().orElse(null);
    }

    // a bag one table's worth of planks off the wood budget: the log need is there exactly when the table is not held. walking
    // from the trees (near our table) to the stone 22 blocks off used to cross the latch every trip, and the log need and the
    // cobble traded the head every few seconds without either getting a block. the table counts out to 48 now (the craft walks
    // back to it that far), so the walk changes nothing
    @Test
    public void walkingBetweenTheTreesAndTheStoneDoesNotFlipTheLogNeed() {
        cfg.cookFuelLogs = 14;
        FakeFacts bag = new FakeFacts().give(Items.WOODEN_AXE, 1).give(Items.WOODEN_PICKAXE, 1).give(Items.OAK_LOG, 23)
                .give(Items.OAK_PLANKS, 4).give(Items.STICK, 6);
        bag.tablePlaced = false;
        assertTrue(names(KitPlanner.gather(bag, cfg, 8)).contains("log"));
        bag.tablePlaced = true;
        assertFalse(names(KitPlanner.gather(bag, cfg, 8)).contains("log"));
        // there and back, a quarter block a step, the latch carried along like MinecraftFacts carries it
        boolean far = false;
        for (int step = 0; step <= 200; step++) {
            double d = 5 + (step <= 100 ? step : 200 - step) * 0.25;
            boolean near = d <= WorkbenchRules.returnRadius(far);
            far = !near;
            // what MinecraftFacts says: the latch, or ours within the walk back (the bag can make a table, it has logs)
            bag.tablePlaced = near || d <= adris.altoclef.util.helpers.StationChoice.oursReach(adris.altoclef.util.helpers.StationHook.Kind.TABLE, true);
            assertFalse("d " + d, names(KitPlanner.gather(bag, cfg, 8)).contains("log"));
        }
    }

    // everything the default kit asks for, so a test can take one thing away
    private FakeFacts complete() {
        FakeFacts full = new FakeFacts();
        for (Item i : new Item[]{Items.STONE_PICKAXE, Items.STONE_AXE, Items.FURNACE, Items.IRON_PICKAXE, Items.IRON_AXE,
                Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.IRON_CHESTPLATE, Items.IRON_HELMET,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            full.give(i, 1);
        }
        // the axe, and enough logs that the gather has nothing left to chop (beds are 24 planks of it)
        full.give(Items.WOODEN_AXE, 1).give(Items.OAK_LOG, 20);
        full.give(Items.BUCKET, 2).give(Items.LADDER, 3).give(Items.WHITE_WOOL, 24);
        full.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        full.foodUnits = 100;
        return full;
    }

    @Test
    public void freshStartGather() {
        List<KitNeed> gather = KitPlanner.gather(f, cfg, 8);
        // 3 logs is the table and the axe (9 planks), 15 is the whole run, and the axe is made in between
        // 19 cobble is 2 picks 6 + axe 3 + furnace 8 + 2 slack, all of it mined before the first stone craft
        assertEquals(List.of(new KitNeed("log", 3), new KitNeed("wooden_axe", 1), new KitNeed("log", 15),
                new KitNeed("cobblestone", 19), new KitNeed("stone_pickaxe", 2), new KitNeed("stone_axe", 1), new KitNeed("furnace", 1),
                new KitNeed(KitNeed.FOOD, 70)), gather);
    }

    @Test
    public void freshStartFullPlanOrder() {
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        // no axe and no bed planks past the gather, but a plan that starts bare still does its wood before any ore
        assertEquals(List.of("log", "stone_pickaxe", "stone_axe", "food", "iron_ingot", "iron_pickaxe", "iron_axe", "bucket",
                "flint_and_steel", "shield", "shears", "ladder", "iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots",
                "wool", "food"), names(plan));
    }

    @Test
    public void ironPhaseWithExactlyEnoughPlanksAsksForNoLog() {
        // 27 planks is the whole bare run (picks, shield, a table, the sticks). the old spare log wanted 4 more of them
        f.give(Items.OAK_PLANKS, 27);
        assertNull(find(KitPlanner.plan(f, cfg, 8), "log"));
        // one short is a log, and only one
        FakeFacts shy = new FakeFacts().give(Items.OAK_PLANKS, 26);
        assertEquals(new KitNeed("log", 1), find(KitPlanner.plan(shy, cfg, 8), "log"));
    }

    @Test
    public void ladderIsInTheKitButCostsNoIron() {
        // sticks only: three ladders must not grow the ingot need (that stays pickaxe..armor, see the next test)
        assertEquals(new KitNeed("ladder", 3), find(KitPlanner.plan(f, cfg, 8), "ladder"));
        assertEquals(40, KitPlanner.ingotsNeeded(f, cfg));
        // holding some of them: the need is the total, and full hands ask for nothing
        f.give(Items.LADDER, 1);
        assertEquals(new KitNeed("ladder", 3), find(KitPlanner.plan(f, cfg, 8), "ladder"));
        assertEquals(null, find(KitPlanner.plan(complete(), cfg, 8), "ladder"));
    }

    @Test
    public void oneCombinedIronNeedSizedByEverythingMissing() {
        // pickaxe 3 + axe 3 + 2 buckets 6 + flint and steel 1 + shield 1 + shears 2 + armor 24
        assertEquals(40, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
        assertEquals(40, KitPlanner.ingotsNeeded(f, cfg));
        assertEquals(1, KitPlanner.plan(f, cfg, 8).stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
    }

    @Test
    public void startedGatherPlanDoesNotAskForStationsAgain() {
        // the furnace gets placed for smelting and then we hold none: that must not send us off to craft another
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("furnace"));
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("furnace"));
    }

    @Test
    public void partialKitOnlyAsksForTheRest() {
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.IRON_PICKAXE, 1).give(Items.BUCKET, 2);
        f.foodUnits = 100;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("iron_pickaxe"));
        assertFalse(names(plan).contains("bucket"));
        // axe 3 + flint and steel 1 + shield 1 + shears 2 + armor 24
        assertEquals(31, find(plan, "iron_ingot").count());
    }

    @Test
    public void heldIngotsCoverTheCombinedNeed() {
        f.give(Items.IRON_INGOT, 40);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(null, find(plan, "iron_ingot"));
        assertTrue(names(plan).contains("iron_pickaxe"));
        f.give(Items.IRON_INGOT, -1);
        assertEquals(40, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
    }

    @Test
    public void waterBucketCountsAsABucketButNotAsAnEmptyOne() {
        f.give(Items.WATER_BUCKET, 1).give(Items.BUCKET, 1);
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "bucket"));
        FakeFacts lone = new FakeFacts().give(Items.WATER_BUCKET, 1);
        // one empty bucket short, so ask for exactly one more empty bucket (3 ingots), not two
        assertEquals(new KitNeed("bucket", 1), find(KitPlanner.plan(lone, cfg, 8), "bucket"));
    }

    @Test
    public void betterToolsSatisfyWorseOnes() {
        f.give(Items.DIAMOND_PICKAXE, 1).give(Items.NETHERITE_AXE, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("stone_pickaxe"));
        assertFalse(names(plan).contains("iron_pickaxe"));
        assertFalse(names(plan).contains("stone_axe"));
        assertFalse(names(plan).contains("iron_axe"));
    }

    @Test
    public void armorPlans() {
        cfg.armorPlan = ArmorPlan.CHEST_HELMET;
        List<KitNeed> two = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(two).containsAll(List.of("iron_chestplate", "iron_helmet")));
        assertFalse(names(two).contains("iron_leggings"));
        assertEquals(40 - 11, find(two, "iron_ingot").count());

        cfg.armorPlan = ArmorPlan.NONE;
        List<KitNeed> none = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(none).contains("iron_chestplate"));
        assertEquals(16, find(none, "iron_ingot").count());
    }

    @Test
    public void wornArmorCountsAsOwned() {
        f.give(Items.IRON_CHESTPLATE, 1);
        f.worn.add(Items.IRON_CHESTPLATE);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("iron_chestplate"));
        assertFalse(names(plan).contains(KitNeed.EQUIP_ARMOR));
        assertEquals(40 - 8, find(plan, "iron_ingot").count());
    }

    @Test
    public void armorInTheBackpackGetsEquippedAfterTheCrafts() {
        f.give(Items.IRON_HELMET, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(plan, KitNeed.EQUIP_ARMOR));
        assertTrue(names(plan).indexOf(KitNeed.EQUIP_ARMOR) > names(plan).indexOf("iron_boots"));
        assertTrue(names(plan).indexOf(KitNeed.EQUIP_ARMOR) < names(plan).indexOf("wool"));
        assertEquals(List.of(Items.IRON_HELMET), KitPlanner.toEquip(f, cfg));
    }

    @Test
    public void equipsTheBestTierWeHold() {
        f.give(Items.IRON_HELMET, 1).give(Items.DIAMOND_HELMET, 1);
        assertEquals(List.of(Items.DIAMOND_HELMET), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.DIAMOND_HELMET);
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
    }

    @Test
    public void shearsComeBeforeWool() {
        List<String> n = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(n.indexOf("shears") < n.indexOf("wool"));
        assertEquals(24, find(KitPlanner.plan(f, cfg, 8), "wool").count());
    }

    @Test
    public void woolIsSizedByBedsAndWoolWeHold() {
        f.give(Items.RED_WOOL, 10);
        // only 9 of the 10 make beds, so the task (which counts every wool) must hold 10 + 15 more
        assertEquals(25, find(KitPlanner.plan(f, cfg, 8), "wool").count());
        FakeFacts beds = new FakeFacts().give(Items.WHITE_BED, 2);
        // two beds are six wool already, so 18 more, held as 18 wool
        assertEquals(18, find(KitPlanner.plan(beds, cfg, 8), "wool").count());
        assertEquals(null, find(KitPlanner.plan(new FakeFacts().give(Items.WHITE_BED, 8), cfg, 8), "wool"));
        assertEquals(null, find(KitPlanner.plan(f, cfg, 0), "wool"));
    }

    @Test
    public void bedShortfallIsTheWoolNeedInWholeBeds() {
        assertEquals(24, KitPlanner.woolShortfall(f, 8));
        assertEquals(8, KitPlanner.bedsShort(f, 8));
        // a held bed is three wool, whatever colour
        FakeFacts beds = new FakeFacts().give(Items.RED_BED, 2).give(Items.WHITE_BED, 1);
        assertEquals(15, KitPlanner.woolShortfall(beds, 8));
        assertEquals(5, KitPlanner.bedsShort(beds, 8));
        // wool in sets of three counts like beds do, scraps do not
        FakeFacts wool = new FakeFacts().give(Items.RED_WOOL, 5).give(Items.WHITE_WOOL, 2);
        assertEquals(21, KitPlanner.woolShortfall(wool, 8));
        assertEquals(7, KitPlanner.bedsShort(wool, 8));
        // a spare is a ninth bed, and nothing is owed once we hold enough
        assertEquals(1, KitPlanner.bedsShort(new FakeFacts().give(Items.WHITE_BED, 8), 9));
        assertEquals(0, KitPlanner.bedsShort(new FakeFacts().give(Items.WHITE_BED, 9), 9));
        assertEquals(0, KitPlanner.bedsShort(f, 0));
        // and the planner asks for exactly the wool this says is missing
        assertEquals(wool.count(Items.RED_WOOL) + wool.count(Items.WHITE_WOOL) + KitPlanner.woolShortfall(wool, 8),
                find(KitPlanner.plan(wool, cfg, 8), "wool").count());
    }

    @Test
    public void woolCountsPerColourInSetsOfThree() {
        // a bed needs three of ONE colour: 5 red + 4 white is 3 + 3
        f.give(Items.RED_WOOL, 5).give(Items.WHITE_WOOL, 4);
        assertEquals(6, KitPlanner.usableWool(f));
        assertEquals(9 + 24 - 6, find(KitPlanner.plan(f, cfg, 8), "wool").count());
        // 24 wool in odd leftovers is no beds at all
        FakeFacts scraps = new FakeFacts();
        for (Item wool : adris.altoclef.util.helpers.ItemHelper.WOOL) {
            scraps.give(wool, 2);
        }
        assertEquals(0, KitPlanner.usableWool(scraps));
        assertEquals(32 + 24, find(KitPlanner.plan(scraps, cfg, 8), "wool").count());
        // a full set in one colour is done
        assertEquals(null, find(KitPlanner.plan(new FakeFacts().give(Items.WHITE_WOOL, 24), cfg, 8), "wool"));
        assertEquals(null, find(KitPlanner.plan(new FakeFacts().give(Items.WHITE_WOOL, 12).give(Items.RED_WOOL, 12), cfg, 8), "wool"));
    }

    @Test
    public void stoneToolsMetIgnoresFoodAndFurnace() {
        assertFalse(KitPlanner.stoneToolsMet(f));
        f.give(Items.STONE_PICKAXE, 1);
        assertFalse(KitPlanner.stoneToolsMet(f));
        f.give(Items.IRON_SWORD, 1);
        assertTrue(KitPlanner.stoneToolsMet(f));
        // the weapon the kit makes is an axe
        assertTrue(KitPlanner.stoneToolsMet(new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1)));
        assertFalse(KitPlanner.stoneToolsMet(new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.WOODEN_AXE, 1)));
    }

    @Test
    public void foodNeedsAreEarlyAndAtTheEnd() {
        f.foodUnits = 80;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(new KitNeed(KitNeed.FOOD, 100), plan.get(plan.size() - 1));
        assertEquals(1, plan.stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).count());
        f.foodUnits = 100;
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), KitNeed.FOOD));
        f.foodUnits = 10;
        assertEquals(2, KitPlanner.plan(f, cfg, 8).stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).count());
    }

    @Test
    public void completeKitIsEmptyAndStaysEmpty() {
        FakeFacts full = complete();
        assertTrue(KitPlanner.plan(full, cfg, 8).isEmpty());
        assertTrue(KitPlanner.plan(full, cfg, 8).isEmpty());
        assertTrue(KitPlanner.gather(full, cfg, 8).isEmpty());
        assertTrue(KitPlanner.essentialsMet(full, cfg));
    }

    @Test
    public void losingOneThingBringsBackExactlyThatThing() {
        FakeFacts full = complete();
        full.items.put(Items.SHEARS, 0);
        List<KitNeed> plan = KitPlanner.plan(full, cfg, 8);
        assertEquals(List.of("iron_ingot", "shears"), names(plan));
        assertEquals(2, find(plan, "iron_ingot").count());
    }

    @Test
    public void essentialsNeedPickaxeLightAndTwoBuckets() {
        FakeFacts full = complete();
        full.items.put(Items.BUCKET, 1);
        assertFalse(KitPlanner.essentialsMet(full, cfg));
        full.give(Items.LAVA_BUCKET, 1);
        assertTrue(KitPlanner.essentialsMet(full, cfg));
        full.items.put(Items.FLINT_AND_STEEL, 0);
        assertFalse(KitPlanner.essentialsMet(full, cfg));
        full.give(Items.FIRE_CHARGE, 1);
        assertTrue(KitPlanner.essentialsMet(full, cfg));
    }

    @Test
    public void progressOfTracksTheRightNumber() {
        f.foodUnits = 33;
        f.buildBlocks = 7;
        f.give(Items.IRON_INGOT, 4);
        FoodPlan food = FoodPlan.ofBeds(f, cfg, 8);
        assertEquals(33, KitPlanner.progressOf(f, new KitNeed(KitNeed.FOOD, 70), food));
        assertEquals(7, KitPlanner.progressOf(f, new KitNeed(KitNeed.BUILD_BLOCKS, 32), food));
        assertEquals(4, KitPlanner.progressOf(f, new KitNeed("iron_ingot", 40), food));
    }

    @Test
    public void fiveIngotsOfGoldMakeTheHelmetGoldAndSaveFiveIron() {
        f.give(Items.GOLD_INGOT, 5);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("iron_helmet"));
        // same spot in the order, so nothing else moves
        assertEquals(names(plan).indexOf("iron_chestplate") + 1, names(plan).indexOf("golden_helmet"));
        assertEquals(35, find(plan, "iron_ingot").count());
        assertEquals(35, KitPlanner.ingotsNeeded(f, cfg));
        assertTrue(names(plan).contains("iron_boots"));
    }

    @Test
    public void fourIngotsOfGoldMakeTheBootsGold() {
        f.give(Items.GOLD_INGOT, 4);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(plan).contains("golden_boots"));
        assertFalse(names(plan).contains("iron_boots"));
        assertTrue(names(plan).contains("iron_helmet"));
        assertEquals(36, find(plan, "iron_ingot").count());
    }

    @Test
    public void noGoldMeansTheSamePlanAsEver() {
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(40, find(plan, "iron_ingot").count());
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("golden_boots"));
        f.give(Items.GOLD_INGOT, 3);
        assertEquals(40, KitPlanner.ingotsNeeded(f, cfg));
    }

    @Test
    public void goldInOtherShapesCountsToo() {
        // a block is nine, nine nuggets are one, raw gold gets smelted. none of it is mined for
        assertEquals(35, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_BLOCK, 1), cfg));
        assertEquals(35, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_NUGGET, 45), cfg));
        assertEquals(35, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.RAW_GOLD, 5), cfg));
        assertEquals(36, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_NUGGET, 36), cfg));
        assertEquals(35, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_INGOT, 2).give(Items.GOLD_NUGGET, 27), cfg));
        assertEquals(40, KitPlanner.ingotsNeeded(new FakeFacts().give(Items.GOLD_NUGGET, 35), cfg));
    }

    @Test
    public void aHeldGoldenHelmetIsJustEquipped() {
        f.give(Items.GOLDEN_HELMET, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("iron_helmet"));
        assertEquals(35, find(plan, "iron_ingot").count());
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(plan, KitNeed.EQUIP_ARMOR));
        assertEquals(List.of(Items.GOLDEN_HELMET), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.GOLDEN_HELMET);
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), KitNeed.EQUIP_ARMOR));
    }

    @Test
    public void goldHeldAsBootsSwapsTheBootsSlot() {
        f.give(Items.GOLDEN_BOOTS, 1).give(Items.GOLD_INGOT, 9);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        // the boots are already ours, so the nine ingots do not turn into a helmet as well
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains("iron_boots"));
        assertTrue(names(plan).contains("iron_helmet"));
        assertEquals(List.of(Items.GOLDEN_BOOTS), KitPlanner.toEquip(f, cfg));
    }

    @Test
    public void goldAfterTheIronHelmetWasMadeStillGoesGold() {
        f.give(Items.IRON_HELMET, 1).give(Items.GOLD_INGOT, 5);
        f.worn.add(Items.IRON_HELMET);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(plan).contains("golden_helmet"));
        assertEquals(35, find(plan, "iron_ingot").count());
        // crafted: the gold one is held, the iron one stays on until the equip step swaps them
        f.items.put(Items.GOLD_INGOT, 0);
        f.give(Items.GOLDEN_HELMET, 1);
        plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("golden_helmet"));
        assertEquals(new KitNeed(KitNeed.EQUIP_ARMOR, 1), find(plan, KitNeed.EQUIP_ARMOR));
        assertEquals(List.of(Items.GOLDEN_HELMET), KitPlanner.toEquip(f, cfg));
        // and it sticks: wearing it and having no gold left must not bring the iron helmet back
        f.worn.clear();
        f.worn.add(Items.GOLDEN_HELMET);
        plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("golden_helmet"));
        assertFalse(names(plan).contains(KitNeed.EQUIP_ARMOR));
        assertEquals(35, find(plan, "iron_ingot").count());
    }

    @Test
    public void aGoldPieceOutsideThePlanIsStillWorn() {
        cfg.armorPlan = ArmorPlan.CHEST_HELMET;
        f.give(Items.GOLDEN_BOOTS, 1);
        assertEquals(List.of(Items.GOLDEN_BOOTS), KitPlanner.toEquip(f, cfg));
        cfg.armorPlan = ArmorPlan.NONE;
        assertEquals(List.of(Items.GOLDEN_BOOTS), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.GOLDEN_BOOTS);
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
        // nothing gold to swap in the none plan, so the gold stays a gate matter
        assertEquals(16, find(KitPlanner.plan(new FakeFacts().give(Items.GOLD_INGOT, 9), cfg, 8), "iron_ingot").count());
    }

    @Test
    public void onlyOneGoldPieceIsWornWhenSeveralAreHeld() {
        f.give(Items.GOLDEN_HELMET, 1).give(Items.GOLDEN_BOOTS, 1);
        assertEquals(List.of(Items.GOLDEN_HELMET), KitPlanner.toEquip(f, cfg));
        f.worn.add(Items.GOLDEN_BOOTS);
        // boots on already: the helmet in the bag is not a second wardrobe change
        assertTrue(KitPlanner.toEquip(f, cfg).isEmpty());
    }

    // ---- wood budget ----

    @Test
    public void woodBudgetForAFreshRunIsFifteenLogs() {
        // planks: table x2 8 + axe 3 + wooden pick 3 + shield 6 + 24 for eight beds = 44. sticks: axe 2, two stone picks 4,
        // wooden pick 2, stone axe 2, iron pick 2, iron axe 2, ladders 7 = 21, which is 6 crafts of 2 planks. 56 + a log
        // of slack is 60, and 60 planks are 15 logs
        assertEquals(15, KitPlanner.woodNeed(f, cfg, 8));
    }

    @Test
    public void firstBatchIsTheTableAndTheAxeThenTheWholeBudgetAfterTheAxe() {
        List<KitNeed> gather = KitPlanner.gather(f, cfg, 8);
        assertEquals(List.of("log", "wooden_axe", "log", "cobblestone", "stone_pickaxe", "stone_axe", "furnace", "food"), names(gather));
        // logs in hand but no axe yet: the small batch is done, the axe is next, and the big target does not move
        f.give(Items.OAK_LOG, 3);
        gather = KitPlanner.gather(f, cfg, 8);
        assertEquals(List.of("wooden_axe", "log", "cobblestone", "stone_pickaxe", "stone_axe", "furnace", "food"), names(gather));
        assertEquals(new KitNeed("log", 15), gather.get(1));
        // axe made (the table and some planks went with it), still short: the wood comes before the stone tools
        FakeFacts after = new FakeFacts().give(Items.WOODEN_AXE, 1).give(Items.OAK_LOG, 1).give(Items.OAK_PLANKS, 5);
        after.give(Items.CRAFTING_TABLE, 1);
        assertEquals("log", KitPlanner.gather(after, cfg, 8).get(0).catalogueName());
    }

    @Test
    public void anyAxeIsTheAxe() {
        for (Item axe : new Item[]{Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE}) {
            FakeFacts held = new FakeFacts().give(axe, 1);
            List<String> names = names(KitPlanner.gather(held, cfg, 8));
            assertFalse(axe.toString(), names.contains("wooden_axe"));
            // no axe to wait for means the budget is the first thing asked
            assertEquals("log", names.get(0));
        }
    }

    @Test
    public void theKitMakesAxesAndNeverSwords() {
        // an axe hits harder than a sword of its tier (WeaponPick), so the sword is no craft of ours
        List<String> all = names(KitPlanner.plan(f, cfg, 8));
        assertTrue(all.contains("stone_axe"));
        assertTrue(all.contains("iron_axe"));
        assertFalse(all.contains("stone_sword"));
        assertFalse(all.contains("iron_sword"));
        assertTrue(names(KitPlanner.gather(f, cfg, 8)).contains("stone_axe"));
        // and the weapon axes are not the log axe: the stone one waits for the stone pick, the first batch is the wooden one
        assertEquals(List.of("log", "wooden_axe"), names(KitPlanner.gather(f, cfg, 8)).subList(0, 2));
    }

    @Test
    public void aBetterAxeCoversTheWorseOnesAndASwordCoversNone() {
        assertEquals(1, KitPlanner.have(new FakeFacts().give(Items.STONE_AXE, 1), "wooden_axe"));
        assertEquals(1, KitPlanner.have(new FakeFacts().give(Items.IRON_AXE, 1), "stone_axe"));
        assertEquals(0, KitPlanner.have(new FakeFacts().give(Items.STONE_AXE, 1), "iron_axe"));
        // nothing swings like an axe in the kit's eyes, a sword in the bag still leaves the axe to make
        assertEquals(0, KitPlanner.have(new FakeFacts().give(Items.IRON_SWORD, 1), "stone_axe"));
        assertEquals(new KitNeed("stone_axe", 1), find(KitPlanner.gather(new FakeFacts().give(Items.OAK_LOG, 15)
                .give(Items.WOODEN_AXE, 1).give(Items.COBBLESTONE, 40).give(Items.STONE_PICKAXE, 2).give(Items.FURNACE, 1)
                .give(Items.IRON_SWORD, 1), cfg, 8), "stone_axe"));
    }

    @Test
    public void budgetEndsExactlyWhereTheWoodDoes() {
        FakeFacts enough = new FakeFacts().give(Items.OAK_LOG, 15);
        assertEquals(0, KitPlanner.woodNeed(enough, cfg, 8));
        assertFalse(names(KitPlanner.gather(enough, cfg, 8)).contains("log"));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.OAK_LOG, 14), cfg, 8));
        // planks and logs are the same currency, four to one
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.SPRUCE_PLANKS, 60), cfg, 8));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.SPRUCE_PLANKS, 59), cfg, 8));
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.OAK_LOG, 10).give(Items.OAK_PLANKS, 20), cfg, 8));
    }

    @Test
    public void heldSticksTakeOffThePlanksThatWouldMakeThem() {
        // 21 sticks wanted is 12 planks, so 20 sticks in the bag leave 50 planks of budget
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 20).give(Items.OAK_PLANKS, 50), cfg, 8));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 20).give(Items.OAK_PLANKS, 49), cfg, 8));
        // 17 of them is one craft short, 16 is five sticks short and a stick over a craft still costs the craft
        assertEquals(0, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 17).give(Items.OAK_PLANKS, 50), cfg, 8));
        assertEquals(1, KitPlanner.woodNeed(new FakeFacts().give(Items.STICK, 16).give(Items.OAK_PLANKS, 51), cfg, 8));
    }

    @Test
    public void bedPlanksAreOnlyForBedsWeStillHaveToMake() {
        assertEquals(9, KitPlanner.woodNeed(f, cfg, 0));
        assertEquals(15, KitPlanner.woodNeed(f, cfg, 8));
        // a bed in the bag is its three planks already spent
        assertEquals(9, KitPlanner.woodNeed(new FakeFacts().give(Items.WHITE_BED, 8), cfg, 8));
    }

    @Test
    public void aTableInTheBagAndToolsAlreadyMadeShrinkTheTableShare() {
        int fresh = KitPlanner.woodNeed(f, cfg, 0);
        // a held table is 8 planks we do not need
        assertEquals(fresh - 2, KitPlanner.woodNeed(new FakeFacts().give(Items.CRAFTING_TABLE, 1), cfg, 0));
        // axe and wooden pick made means the table went down already: one spare is left to budget
        FakeFacts made = new FakeFacts().give(Items.WOODEN_AXE, 1).give(Items.WOODEN_PICKAXE, 1);
        assertTrue(KitPlanner.woodNeed(made, cfg, 0) < fresh);
    }

    // the log: the table went down for the axe, left the bag, and the head need flipped from the axe to log
    @Test
    public void aTablePlacedNextToUsCountsAsHeld() {
        // 4 planks and some sticks in the bag: enough for the axe, not for an axe AND a table
        FakeFacts placed = new FakeFacts().give(Items.OAK_PLANKS, 3).give(Items.STICK, 2);
        assertEquals("log", KitPlanner.gather(placed, cfg, 8).get(0).catalogueName());
        placed.tablePlaced = true;
        assertEquals("wooden_axe", KitPlanner.gather(placed, cfg, 8).get(0).catalogueName());
        // and the same with the table in the bag, it is the same table
        FakeFacts bag = new FakeFacts().give(Items.OAK_PLANKS, 3).give(Items.STICK, 2).give(Items.CRAFTING_TABLE, 1);
        assertEquals(names(KitPlanner.gather(bag, cfg, 8)), names(KitPlanner.gather(placed, cfg, 8)));
    }

    @Test
    public void aPlacedTableTakesTheTablePlanksOutOfTheBudgetToo() {
        int fresh = KitPlanner.woodNeed(f, cfg, 0);
        FakeFacts placed = new FakeFacts();
        placed.tablePlaced = true;
        assertEquals(fresh - 2, KitPlanner.woodNeed(placed, cfg, 0));
        assertEquals(KitPlanner.woodNeed(new FakeFacts().give(Items.CRAFTING_TABLE, 1), cfg, 0), KitPlanner.woodNeed(placed, cfg, 0));
    }

    @Test
    public void ironPhaseDoesNotAskForWoodOnceTheOreIsInTheBag() {
        // the whole point: no cave to surface trip for one more log
        assertEquals("log", names(KitPlanner.plan(f, cfg, 8)).get(0));
        FakeFacts raw = new FakeFacts().give(Items.RAW_IRON, 1);
        assertFalse(names(KitPlanner.plan(raw, cfg, 8)).contains("log"));
        FakeFacts ingots = new FakeFacts().give(Items.IRON_INGOT, 3);
        assertFalse(names(KitPlanner.plan(ingots, cfg, 8)).contains("log"));
        // iron cooking in a furnace we loaded is iron too
        FakeFacts cooking = new FakeFacts().cooking("iron_ingot", 8, 60);
        assertFalse(names(KitPlanner.plan(cooking, cfg, 8)).contains("log"));
    }

    @Test
    public void ironPhaseNeverAsksForAnAxeOrTheBedPlanks() {
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("wooden_axe"));
        // a complete kit with no wood at all is still complete: wool in hand is the iron phase's last word on the beds
        FakeFacts full = complete();
        full.items.remove(Items.OAK_LOG);
        full.items.remove(Items.WOODEN_AXE);
        assertTrue(KitPlanner.plan(full, cfg, 8).isEmpty());
    }

    // ---- stone budget ----

    @Test
    public void stoneBudgetForAFreshRunIsNineteen() {
        // two picks 6, axe 3, furnace 8, and 2 of slack
        assertEquals(19, KitPlanner.stoneNeed(f, cfg));
    }

    @Test
    public void heldCobbleTakesOffTheStoneBudgetAndStaysAtZeroThroughTheCrafts() {
        assertEquals(9, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLESTONE, 10), cfg));
        assertEquals(0, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLESTONE, 19), cfg));
        assertEquals(0, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLESTONE, 40), cfg));
        // the picks are crafted: 6 cobble gone and 6 wanted gone, so a need that was met stays met (no second trip)
        FakeFacts crafted = new FakeFacts().give(Items.COBBLESTONE, 13).give(Items.STONE_PICKAXE, 2);
        assertEquals(0, KitPlanner.stoneNeed(crafted, cfg));
        // and the axe too
        crafted.give(Items.COBBLESTONE, -3).give(Items.STONE_AXE, 1);
        assertEquals(0, KitPlanner.stoneNeed(crafted, cfg));
        // everything made: nothing left to want, not even the slack
        assertEquals(0, KitPlanner.stoneNeed(new FakeFacts().give(Items.STONE_PICKAXE, 2).give(Items.STONE_AXE, 1)
                .give(Items.FURNACE, 1), cfg));
    }

    @Test
    public void cobbledDeepslateIsNotCobbleToTheRecipes() {
        // the catalogue recipes only take Items.COBBLESTONE, so deepslate in the bag would not reach the crafts
        assertEquals(19, KitPlanner.stoneNeed(new FakeFacts().give(Items.COBBLED_DEEPSLATE, 30), cfg));
    }

    @Test
    public void stoneBudgetShrinksWithWhatIsAlreadyMade() {
        // a furnace in the bag is 8 less
        assertEquals(11, KitPlanner.stoneNeed(new FakeFacts().give(Items.FURNACE, 1), cfg));
        // a blast furnace counts as a furnace
        assertEquals(11, KitPlanner.stoneNeed(new FakeFacts().give(Items.BLAST_FURNACE, 1), cfg));
        // one pick down: one more to make
        assertEquals(16, KitPlanner.stoneNeed(new FakeFacts().give(Items.STONE_PICKAXE, 1), cfg));
    }

    @Test
    public void anIronPickCoversTheStoneBudgetOfTheStonePicks() {
        // axe and furnace only: 11 and 2 of slack
        assertEquals(13, KitPlanner.stoneNeed(new FakeFacts().give(Items.IRON_PICKAXE, 1), cfg));
        assertEquals(13, KitPlanner.stoneNeed(new FakeFacts().give(Items.DIAMOND_PICKAXE, 1), cfg));
    }

    @Test
    public void aWornStonePickIsAFreshOneInTheBudget() {
        f.spent.put(Items.STONE_PICKAXE, 1);
        // the worn one does not count, so both picks are still to make
        assertEquals(19, KitPlanner.stoneNeed(f, cfg));
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(16, KitPlanner.stoneNeed(f, cfg));
    }

    // ---- stone floor (what the movements must not spend) ----

    @Test
    public void stoneFloorIsTheWholeKitWithoutSlackOrWhatWeHold() {
        // two picks 6, axe 3, furnace 8: the slack is for mining, not for keeping
        assertEquals(17, KitPlanner.stoneFloor(f, cfg));
        // holding cobble does not shrink it, the held cobble IS what it keeps
        assertEquals(17, KitPlanner.stoneFloor(new FakeFacts().give(Items.COBBLESTONE, 18), cfg));
        assertEquals(17, KitPlanner.stoneFloor(new FakeFacts().give(Items.COBBLESTONE, 3), cfg));
        // and deepslate is no cobble but it is not a reason to change the number either
        assertEquals(17, KitPlanner.stoneFloor(new FakeFacts().give(Items.COBBLED_DEEPSLATE, 30), cfg));
    }

    @Test
    public void stoneFloorFallsAsTheStoneItemsGetMade() {
        // the whole log: picks crafted, then the axe, then the furnace
        FakeFacts made = new FakeFacts().give(Items.COBBLESTONE, 12).give(Items.STONE_PICKAXE, 2);
        assertEquals(11, KitPlanner.stoneFloor(made, cfg));
        made.give(Items.STONE_AXE, 1);
        assertEquals(8, KitPlanner.stoneFloor(made, cfg));
        made.give(Items.FURNACE, 1);
        assertEquals(0, KitPlanner.stoneFloor(made, cfg));
        // one pick down, and an iron pick covers both of them
        assertEquals(14, KitPlanner.stoneFloor(new FakeFacts().give(Items.STONE_PICKAXE, 1), cfg));
        assertEquals(11, KitPlanner.stoneFloor(new FakeFacts().give(Items.IRON_PICKAXE, 1), cfg));
    }

    @Test
    public void aFurnaceOnTheGroundIsHeldForTheStoneFloor() {
        // standing next to us and coming back to the bag: 8 less to keep
        f.furnacePlaced = true;
        assertEquals(9, KitPlanner.stoneFloor(f, cfg));
        // and what is left to mine agrees: the planner skips the furnace need for it, so no 8 cobble for it either
        assertEquals(11, KitPlanner.stoneNeed(f, cfg));
        // a furnace in the bag AND one on the ground is still one furnace
        f.give(Items.FURNACE, 1);
        assertEquals(9, KitPlanner.stoneFloor(f, cfg));
        // the table never cost any cobble, so it changes nothing
        FakeFacts table = new FakeFacts();
        table.tablePlaced = true;
        assertEquals(17, KitPlanner.stoneFloor(table, cfg));
    }

    @Test
    public void stoneIsMinedAfterTheWoodAndBeforeTheCrafts() {
        f.give(Items.OAK_LOG, 15).give(Items.WOODEN_AXE, 1);
        List<KitNeed> gather = KitPlanner.gather(f, cfg, 8);
        assertEquals(List.of("cobblestone", "stone_pickaxe", "stone_axe", "furnace", "food"), names(gather));
        // the total is held plus the shortfall, same shape as the log need
        FakeFacts some = new FakeFacts().give(Items.OAK_LOG, 15).give(Items.WOODEN_AXE, 1).give(Items.COBBLESTONE, 5);
        assertEquals(new KitNeed("cobblestone", 19), find(KitPlanner.gather(some, cfg, 8), "cobblestone"));
        // enough of it: no gather entry, the crafts go straight in
        some.give(Items.COBBLESTONE, 14);
        assertEquals(List.of("stone_pickaxe", "stone_axe", "furnace", "food"), names(KitPlanner.gather(some, cfg, 8)));
        // the wood is still first when it is short
        assertEquals("log", KitPlanner.gather(new FakeFacts().give(Items.COBBLESTONE, 2), cfg, 8).get(0).catalogueName());
    }

    // ---- the split smelt (SmeltSplit)

    // the split's extra furnaces are never in the plan: the mine brings back the spare cobble, the split uses what is there
    @Test
    public void theSplitAsksNoCobble() {
        assertTrue(KitPlanner.ingotsNeeded(f, cfg) >= 27);
        assertEquals(19, KitPlanner.stoneNeed(f, cfg));
        assertEquals(17, KitPlanner.stoneFloor(f, cfg));
        try {
            f.smeltBatch = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
            assertEquals(19, KitPlanner.stoneNeed(f, cfg));
            assertEquals(17, KitPlanner.stoneFloor(f, cfg));
        } finally {
            SmeltSplit.clear();
        }
    }

    // the coal rounds per load: the loads the owed iron would get, then the loads the batch still has
    @Test
    public void smeltLoadsFollowTheOwedIronThenTheBatch() {
        assertEquals(3, KitPlanner.smeltLoads(f, cfg));
        // a blast furnace takes it all
        assertEquals(1, KitPlanner.smeltLoads(new FakeFacts().give(Items.BLAST_FURNACE, 1), cfg));
        // a leftover is one load, cooking ingots count as held
        int owed = KitPlanner.ingotsNeeded(f, cfg);
        assertEquals(1, KitPlanner.smeltLoads(new FakeFacts().give(Items.IRON_INGOT, owed - 15), cfg));
        assertEquals(1, KitPlanner.smeltLoads(new FakeFacts().cooking("iron_ingot", owed - 10, 60), cfg));
        try {
            SmeltSplit.Batch batch = SmeltSplit.start(37, SmeltSplit.sizes(37, 3));
            f.smeltBatch = batch;
            batch.loaded(0);
            assertEquals(2, KitPlanner.smeltLoads(f, cfg));
            batch.loaded(1);
            assertEquals(1, KitPlanner.smeltLoads(f, cfg));
        } finally {
            SmeltSplit.clear();
        }
    }

    @Test
    public void cobbleIsAGatheringNeedSoItNeverHoldsTheTable() {
        assertTrue(new KitNeed("cobblestone", 18).isGathering());
        assertFalse(new KitNeed("cobblestone", 18).isCraft());
        assertTrue(KitNeed.isCraftName("stone_pickaxe"));
    }

    @Test
    public void theIronPhaseNeverGathersTheStoneKitAgain() {
        // a worn pick in the iron phase is a just in time craft, not a trip
        FakeFacts raw = new FakeFacts().give(Items.RAW_IRON, 1);
        assertFalse(names(KitPlanner.plan(raw, cfg, 8)).contains("cobblestone"));
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("cobblestone"));
        FakeFacts worn = complete();
        worn.items.put(Items.STONE_PICKAXE, 0);
        worn.spent.put(Items.STONE_PICKAXE, 1);
        assertFalse(names(KitPlanner.plan(worn, cfg, 8)).contains("cobblestone"));
    }

    // ---- durable picks ----

    @Test
    public void gatherWantsASparePickAndPlanWantsOne() {
        f.give(Items.STONE_PICKAXE, 1).give(Items.OAK_LOG, 15);
        assertEquals(new KitNeed("stone_pickaxe", 2), find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
    }

    @Test
    public void anIronPickCoversTheWholeStonePickQuantity() {
        f.give(Items.IRON_PICKAXE, 1);
        assertEquals(null, find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
        FakeFacts diamond = new FakeFacts().give(Items.DIAMOND_PICKAXE, 1);
        assertEquals(null, find(KitPlanner.gather(diamond, cfg, 8), "stone_pickaxe"));
        // and the wooden pick it would have been made with is no wood we budget for
        assertTrue(KitPlanner.woodNeed(diamond, cfg, 8) < KitPlanner.woodNeed(new FakeFacts(), cfg, 8));
        // a stone pick is not an iron pick, though, the cover only goes upward
        assertTrue(names(KitPlanner.plan(new FakeFacts().give(Items.STONE_PICKAXE, 2), cfg, 8)).contains("iron_pickaxe"));
    }

    @Test
    public void aWornPickIsMadeAgainAboveTheOneInTheBag() {
        // the planner never sees the worn one in count(), but the catalogue does, so the target starts above it
        f.spent.put(Items.STONE_PICKAXE, 1);
        f.give(Items.OAK_LOG, 15);
        assertEquals(new KitNeed("stone_pickaxe", 3), find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
        assertEquals(new KitNeed("stone_pickaxe", 2), find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
        // one fresh next to it and the plan is happy, the gather still wants its spare
        f.give(Items.STONE_PICKAXE, 1);
        assertEquals(null, find(KitPlanner.plan(f, cfg, 8), "stone_pickaxe"));
        assertEquals(new KitNeed("stone_pickaxe", 3), find(KitPlanner.gather(f, cfg, 8), "stone_pickaxe"));
    }

    @Test
    public void aWornIronPickIsReplacedWithTheIngotsForIt() {
        FakeFacts worn = complete();
        worn.items.put(Items.IRON_PICKAXE, 0);
        worn.spent.put(Items.IRON_PICKAXE, 1);
        List<KitNeed> plan = KitPlanner.plan(worn, cfg, 8);
        assertEquals(List.of("iron_ingot", "iron_pickaxe"), names(plan));
        assertEquals(new KitNeed("iron_pickaxe", 2), find(plan, "iron_pickaxe"));
        assertEquals(3, find(plan, "iron_ingot").count());
    }

    @Test
    public void wearThresholdIsEightyFivePercentAndOnlyForPicks() {
        // stone: 131 uses, 111.35 is the line
        assertFalse(KitPlanner.wornOut(Items.STONE_PICKAXE, 111, 131));
        assertTrue(KitPlanner.wornOut(Items.STONE_PICKAXE, 112, 131));
        // iron: 250 uses, 212.5
        assertFalse(KitPlanner.wornOut(Items.IRON_PICKAXE, 212, 250));
        assertTrue(KitPlanner.wornOut(Items.IRON_PICKAXE, 213, 250));
        // wooden: 59 uses, 50.15
        assertFalse(KitPlanner.wornOut(Items.WOODEN_PICKAXE, 50, 59));
        assertTrue(KitPlanner.wornOut(Items.WOODEN_PICKAXE, 51, 59));
        assertFalse(KitPlanner.wornOut(Items.STONE_PICKAXE, 0, 131));
        // swords and axes wear out of the fight in their own time, and diamond is not ours to second guess
        assertFalse(KitPlanner.wornOut(Items.STONE_SWORD, 130, 131));
        assertFalse(KitPlanner.wornOut(Items.IRON_AXE, 249, 250));
        assertFalse(KitPlanner.wornOut(Items.DIAMOND_PICKAXE, 1500, 1561));
        // something that cannot break is not worn
        assertFalse(KitPlanner.wornOut(Items.STONE_PICKAXE, 5, 0));
    }
}
