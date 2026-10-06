package adris.altoclef.tasks.speedrun.gamer;

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
        f = new FakeFacts();
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    private static KitNeed find(List<KitNeed> needs, String name) {
        return needs.stream().filter(n -> n.catalogueName().equals(name)).findFirst().orElse(null);
    }

    // everything the default kit asks for, so a test can take one thing away
    private FakeFacts complete() {
        FakeFacts full = new FakeFacts();
        for (Item i : new Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.FURNACE, Items.IRON_PICKAXE, Items.IRON_SWORD,
                Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS, Items.IRON_CHESTPLATE, Items.IRON_HELMET,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            full.give(i, 1);
        }
        full.give(Items.BUCKET, 2).give(Items.WHITE_WOOL, 24);
        full.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        full.foodUnits = 100;
        return full;
    }

    @Test
    public void freshStartGather() {
        List<KitNeed> gather = KitPlanner.gather(f, cfg);
        assertEquals(List.of(new KitNeed("stone_pickaxe", 1), new KitNeed("stone_sword", 1), new KitNeed("furnace", 1),
                new KitNeed(KitNeed.FOOD, 70)), gather);
    }

    @Test
    public void freshStartFullPlanOrder() {
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(List.of("stone_pickaxe", "stone_sword", "food", "iron_ingot", "iron_pickaxe", "iron_sword", "bucket",
                "flint_and_steel", "shield", "shears", "iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots",
                "wool", "food"), names(plan));
    }

    @Test
    public void oneCombinedIronNeedSizedByEverythingMissing() {
        // pickaxe 3 + sword 2 + 2 buckets 6 + flint and steel 1 + shield 1 + shears 2 + armor 24
        assertEquals(39, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
        assertEquals(39, KitPlanner.ingotsNeeded(f, cfg));
        assertEquals(1, KitPlanner.plan(f, cfg, 8).stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
    }

    @Test
    public void startedGatherPlanDoesNotAskForStationsAgain() {
        // the furnace gets placed for smelting and then we hold none: that must not send us off to craft another
        assertFalse(names(KitPlanner.plan(f, cfg, 8)).contains("furnace"));
        assertTrue(names(KitPlanner.gather(f, cfg)).contains("furnace"));
    }

    @Test
    public void partialKitOnlyAsksForTheRest() {
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.IRON_PICKAXE, 1).give(Items.BUCKET, 2);
        f.foodUnits = 100;
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("iron_pickaxe"));
        assertFalse(names(plan).contains("bucket"));
        // sword 2 + flint and steel 1 + shield 1 + shears 2 + armor 24
        assertEquals(30, find(plan, "iron_ingot").count());
    }

    @Test
    public void heldIngotsCoverTheCombinedNeed() {
        f.give(Items.IRON_INGOT, 39);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertEquals(null, find(plan, "iron_ingot"));
        assertTrue(names(plan).contains("iron_pickaxe"));
        f.give(Items.IRON_INGOT, -1);
        assertEquals(39, find(KitPlanner.plan(f, cfg, 8), "iron_ingot").count());
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
        f.give(Items.DIAMOND_PICKAXE, 1).give(Items.NETHERITE_SWORD, 1);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("stone_pickaxe"));
        assertFalse(names(plan).contains("iron_pickaxe"));
        assertFalse(names(plan).contains("stone_sword"));
        assertFalse(names(plan).contains("iron_sword"));
    }

    @Test
    public void armorPlans() {
        cfg.armorPlan = ArmorPlan.CHEST_HELMET;
        List<KitNeed> two = KitPlanner.plan(f, cfg, 8);
        assertTrue(names(two).containsAll(List.of("iron_chestplate", "iron_helmet")));
        assertFalse(names(two).contains("iron_leggings"));
        assertEquals(39 - 11, find(two, "iron_ingot").count());

        cfg.armorPlan = ArmorPlan.NONE;
        List<KitNeed> none = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(none).contains("iron_chestplate"));
        assertEquals(15, find(none, "iron_ingot").count());
    }

    @Test
    public void wornArmorCountsAsOwned() {
        f.give(Items.IRON_CHESTPLATE, 1);
        f.worn.add(Items.IRON_CHESTPLATE);
        List<KitNeed> plan = KitPlanner.plan(f, cfg, 8);
        assertFalse(names(plan).contains("iron_chestplate"));
        assertFalse(names(plan).contains(KitNeed.EQUIP_ARMOR));
        assertEquals(39 - 8, find(plan, "iron_ingot").count());
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
        assertTrue(KitPlanner.gather(full, cfg).isEmpty());
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
        assertEquals(33, KitPlanner.progressOf(f, new KitNeed(KitNeed.FOOD, 70)));
        assertEquals(7, KitPlanner.progressOf(f, new KitNeed(KitNeed.BUILD_BLOCKS, 32)));
        assertEquals(4, KitPlanner.progressOf(f, new KitNeed("iron_ingot", 39)));
    }
}
