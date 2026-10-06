package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Trip;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
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

public class SmeltFillerTest {
    private static final int BEDS = 8;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        cfg = new OverworldConfig();
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    private static KitNeed find(List<KitNeed> needs, String name) {
        return needs.stream().filter(n -> n.catalogueName().equals(name)).findFirst().orElse(null);
    }

    // the starter kit, enough food to leave, and nothing iron yet: where the bot is when the first batch goes in
    private FakeFacts atTheFurnace() {
        FakeFacts f = new FakeFacts();
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1);
        f.foodUnits = 70;
        return f;
    }

    @Test
    public void withNoJobTheScheduleIsJustThePlan() {
        FakeFacts f = atTheFurnace();
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertEquals(KitPlanner.plan(f, cfg, BEDS), s.runnable());
        assertTrue(s.blocked().isEmpty());
        assertEquals(39, find(s.runnable(), "iron_ingot").count());
    }

    @Test
    public void ingotsInTheFurnaceSatisfyTheIronNeed() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400);
        assertEquals(39, f.pendingOutput(Items.IRON_INGOT));
        assertNull(find(KitPlanner.plan(f, cfg, BEDS), "iron_ingot"));
        // but not a smaller batch, that one still needs the rest
        FakeFacts small = atTheFurnace().cooking("iron_ingot", 20, 400);
        assertEquals(39, find(KitPlanner.plan(small, cfg, BEDS), "iron_ingot").count());
    }

    @Test
    public void heldAndCookingIngotsAddUp() {
        FakeFacts f = atTheFurnace().give(Items.IRON_INGOT, 10).cooking("iron_ingot", 29, 400);
        assertNull(find(KitPlanner.plan(f, cfg, BEDS), "iron_ingot"));
    }

    @Test
    public void craftsThatWantIngotsWeDoNotHoldAreBlocked() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS);
        assertEquals(List.of("iron_pickaxe", "iron_sword", "bucket", "flint_and_steel", "shield", "shears", "iron_chestplate",
                "iron_helmet", "iron_leggings", "iron_boots"), names(s.blocked()));
        for (String iron : List.of("iron_pickaxe", "bucket", "iron_boots")) {
            assertNull(iron, find(s.runnable(), iron));
        }
    }

    @Test
    public void heldIngotsPayForCraftsInPlanOrder() {
        // 3 pays the pickaxe, nothing is left for the sword
        Schedule s = SmeltFiller.schedule(atTheFurnace().give(Items.IRON_INGOT, 3).cooking("iron_ingot", 36, 400), cfg, BEDS);
        assertEquals(new KitNeed("iron_pickaxe", 1), find(s.runnable(), "iron_pickaxe"));
        assertNull(find(s.runnable(), "iron_sword"));
        assertEquals(new KitNeed("iron_sword", 1), find(s.blocked(), "iron_sword"));
        assertNull(find(s.blocked(), "iron_pickaxe"));
    }

    @Test
    public void fillerComesInUsefulnessOrder() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS);
        // a: what the kit wanted anyway (wool, the food target), then the portal's blocks
        // b: flint for the steel, planks for the shield, sticks and beds
        // c: the stock-up, in the order the config lists it
        assertEquals(List.of("wool", "food", "build_blocks", "flint", "planks", "food", "wool", "build_blocks", "log"),
                names(s.runnable()));
        assertEquals(new KitNeed("flint", 1), s.runnable().get(3));
        assertEquals(new KitNeed("planks", 6 + 2 + 12), s.runnable().get(4));
    }

    @Test
    public void stockUpIsCappedByTheConfig() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS);
        List<KitNeed> r = s.runnable();
        assertEquals(new KitNeed(KitNeed.FOOD, 100), r.get(1));
        assertEquals(new KitNeed(KitNeed.FOOD, 130), r.get(5));
        assertEquals(new KitNeed("wool", 3 * BEDS), r.get(0));
        // three more beds of wool on top of the eight
        assertEquals(new KitNeed("wool", 3 * (BEDS + 3)), r.get(6));
        assertEquals(new KitNeed(KitNeed.BUILD_BLOCKS, 32), r.get(2));
        assertEquals(new KitNeed(KitNeed.BUILD_BLOCKS, 64), r.get(7));
        assertEquals(new KitNeed("log", 8), r.get(8));
    }

    @Test
    public void everythingStockedMeansNothingRunnable() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400);
        f.foodUnits = 130;
        f.buildBlocks = 64;
        f.give(Items.WHITE_WOOL, 33).give(Items.OAK_LOG, 8).give(Items.OAK_PLANKS, 20).give(Items.FLINT, 1);
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertTrue(names(s.runnable()).toString(), s.runnable().isEmpty());
        assertFalse(s.blocked().isEmpty());
    }

    @Test
    public void ownedFlintAndSteelAsksForNoFlint() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400).give(Items.FLINT_AND_STEEL, 1);
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS).runnable(), "flint"));
        FakeFacts has = atTheFurnace().cooking("iron_ingot", 39, 400).give(Items.FLINT, 1);
        assertNull(find(SmeltFiller.schedule(has, cfg, BEDS).runnable(), "flint"));
    }

    @Test
    public void aTypoInTheExtrasIsIgnored() {
        cfg.smeltExtras = List.of(new OverworldConfig.KitItem("fod", 5), new OverworldConfig.KitItem("log", 8));
        List<String> n = names(SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS).runnable());
        assertEquals("log", n.get(n.size() - 1));
        assertFalse(n.contains("fod"));
    }

    @Test
    public void everyFillerNameIsInTheCatalogue() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS);
        for (KitNeed need : s.runnable()) {
            if (!need.isSpecial()) {
                assertTrue(need.catalogueName(), adris.altoclef.TaskCatalogue.taskExists(need.catalogueName()));
            }
        }
    }

    @Test
    public void nothingToDoMeansWaitingAtTheFurnaceUntilItIsDone() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long start = jobs.get(0).startTick;
        assertEquals(Trip.WAIT, SmeltFiller.trip(false, true, 0, start, jobs, cfg));
        assertEquals(Trip.COLLECT, SmeltFiller.trip(false, true, 0, start + 100 * 20, jobs, cfg));
    }

    @Test
    public void aFinishedFurnaceIsOnlyFetchedBetweenTwoNeeds() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        // mid need: keep going, even though it is long done
        assertEquals(Trip.FILLER, SmeltFiller.trip(true, false, 0, late, jobs, cfg));
        assertEquals(Trip.COLLECT, SmeltFiller.trip(true, true, 0, late, jobs, cfg));
        // at a boundary but not done yet: filler
        assertEquals(Trip.FILLER, SmeltFiller.trip(true, true, 0, jobs.get(0).startTick, jobs, cfg));
    }

    @Test
    public void nearlyDoneCountsAsDone() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long soon = jobs.get(0).doneTick - Math.round(cfg.furnaceWaitSeconds * 20) + 1;
        assertEquals(Trip.COLLECT, SmeltFiller.trip(true, true, 0, soon, jobs, cfg));
        assertEquals(Trip.FILLER, SmeltFiller.trip(true, true, 0, soon - 40, jobs, cfg));
    }

    @Test
    public void tiredOfTheLeashMeansWaitingThere() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long start = jobs.get(0).startTick;
        assertEquals(Trip.FILLER, SmeltFiller.trip(true, false, cfg.furnaceMaxPullbacks - 1, start, jobs, cfg));
        assertEquals(Trip.WAIT, SmeltFiller.trip(true, false, cfg.furnaceMaxPullbacks, start, jobs, cfg));
    }

    @Test
    public void leashIsTheSmallerOfTheConfigAndTheSimulationDistance() {
        assertEquals(64, SmeltFiller.leashBlocks(12, 64));
        // 5 chunks simulated: 80 blocks, minus a chunk
        assertEquals(64, SmeltFiller.leashBlocks(5, 100));
        assertEquals(48, SmeltFiller.leashBlocks(4, 64));
        // a server we cannot ask
        assertEquals(64, SmeltFiller.leashBlocks(0, 64));
        // never silly small
        assertEquals(16, SmeltFiller.leashBlocks(2, 64));
        assertEquals(16, SmeltFiller.leashBlocks(1, 64));
    }

    @Test
    public void pullBackHasHysteresis() {
        assertFalse(SmeltFiller.pullBack(false, 64, 64));
        assertTrue(SmeltFiller.pullBack(false, 64.5, 64));
        // once walking back, keep going to half the leash
        assertTrue(SmeltFiller.pullBack(true, 40, 64));
        assertFalse(SmeltFiller.pullBack(true, 32, 64));
        assertEquals(5.0, SmeltFiller.horizontal(0.5, 0.5, new RunState.Pos(3, 100, 4)), 1e-9);
    }

    @Test
    public void bedPlanksAreCappedAndShrinkWithBedsHeld() {
        assertEquals(0, SmeltFiller.planksWanted(List.of(), new FakeFacts().give(Items.WHITE_BED, 8), cfg, BEDS));
        assertEquals(12, SmeltFiller.planksWanted(List.of(), new FakeFacts(), cfg, BEDS));
        assertEquals(3, SmeltFiller.planksWanted(List.of(), new FakeFacts().give(Items.WHITE_BED, 7), cfg, BEDS));
    }
}
