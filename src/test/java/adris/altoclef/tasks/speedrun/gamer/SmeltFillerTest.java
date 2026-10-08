package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Decision;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Schedule;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Trip;
import adris.altoclef.tasks.speedrun.gamer.SmeltFiller.Why;
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
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3);
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
    public void foodInTheSmokerCountsAsPendingNotAsHeld() {
        FakeFacts f = atTheFurnace();
        f.foodUnits = 10;
        f.cookingFood("cooked_mutton", 10, 6, 50);
        assertEquals(10, f.foodUnits());
        assertEquals(60, f.pendingFoodUnits());
        // 10 + 60 covers the 70 unit need but not an 80 one, and the bag number never lied about it
        assertNull(find(KitPlanner.gather(f, cfg, BEDS), KitNeed.FOOD));
        cfg.minFoodUnits = 80;
        assertEquals(new KitNeed(KitNeed.FOOD, 80), find(KitPlanner.gather(f, cfg, BEDS), KitNeed.FOOD));
        assertEquals(10 + 60, KitPlanner.progressOf(f, new KitNeed(KitNeed.FOOD, 80)));
    }

    @Test
    public void ironJobsAreNotFood() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400);
        assertEquals(0, f.pendingFoodUnits());
    }

    @Test
    public void aSecondBatchOfMeatWaitsForTheSmokerToBeEmptied() {
        FakeFacts f = atTheFurnace();
        f.foodUnits = 0;
        f.cookingFood("cooked_porkchop", 3, 8, 15).give(Items.MUTTON, 7);
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        assertTrue(SmeltFiller.foodBlocked(f, food));
        // no raw meat left to load, so the food task has nothing to wait for and may hunt
        FakeFacts hunter = atTheFurnace().cookingFood("cooked_porkchop", 3, 8, 15);
        assertFalse(SmeltFiller.foodBlocked(hunter, food));
        // and no job, no block
        assertFalse(SmeltFiller.foodBlocked(atTheFurnace().give(Items.MUTTON, 7), food));
        // only the food need ever waits
        assertFalse(SmeltFiller.foodBlocked(f, new KitNeed("log", 4)));
    }

    @Test
    public void gatherKeepsWorkingOnWoodWhileTheFoodCooks() {
        FakeFacts f = new FakeFacts();
        f.cookingFood("cooked_mutton", 7, 6, 35).give(Items.MUTTON, 3);
        List<KitNeed> runnable = SmeltFiller.gatherRunnable(f, cfg, BEDS);
        assertEquals(KitPlanner.gather(f, cfg, BEDS).stream().filter(n -> !KitNeed.FOOD.equals(n.catalogueName())).toList(),
                runnable);
        assertFalse(runnable.isEmpty());
        // the food need is not in the runnable list while the first batch cooks, even when it is still short
        assertNull(find(runnable, KitNeed.FOOD));
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
    public void ironFreeCraftsStayRunnableWhileTheIronCooks() {
        FakeFacts f = new FakeFacts().cooking("iron_ingot", 39, 400);
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1);
        f.foodUnits = 70;
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        // ladders are sticks, no ingots, so they are not stuck behind the furnace like the pickaxe is
        assertEquals(new KitNeed("ladder", 3), s.runnable().get(0));
        assertNull(find(s.blocked(), "ladder"));
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

    // atBoundary / blocking / phaseEnding spelled out so the tests below read as sentences
    private Decision decide(boolean fillerLeft, boolean atBoundary, boolean blocking, boolean phaseEnding, long now,
                            List<RunState.FurnaceJob> jobs) {
        return SmeltFiller.decide(fillerLeft, atBoundary, blocking, phaseEnding, now, jobs, cfg);
    }

    @Test
    public void nothingToDoMeansWaitingAtTheFurnaceUntilItIsDone() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long start = jobs.get(0).startTick;
        assertEquals(new Decision(Trip.WAIT, Why.NONE), decide(false, true, false, false, start, jobs));
        assertEquals(new Decision(Trip.COLLECT, Why.PHASE_END), decide(false, true, false, false, start + 100 * 20, jobs));
    }

    @Test
    public void aFinishedFurnaceIsOnlyFetchedBetweenTwoNeeds() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        // mid need: keep going, even though it is long done
        assertEquals(Trip.FILLER, decide(true, false, false, false, late, jobs).trip());
        assertEquals(new Decision(Trip.COLLECT, Why.BOUNDARY), decide(true, true, false, false, late, jobs));
        // at a boundary but not done yet: filler
        assertEquals(Trip.FILLER, decide(true, true, false, false, jobs.get(0).startTick, jobs).trip());
    }

    @Test
    public void nearlyDoneCountsAsDone() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long soon = jobs.get(0).doneTick - Math.round(cfg.furnaceWaitSeconds * 20) + 1;
        assertEquals(Trip.COLLECT, decide(true, true, false, false, soon, jobs).trip());
        assertEquals(Trip.FILLER, decide(true, true, false, false, soon - 40, jobs).trip());
    }

    @Test
    public void aNeedWaitingOnTheOutputPullsUsBackMidNeed() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        // not at a boundary, but the pickaxe cannot start without those ingots
        assertEquals(new Decision(Trip.COLLECT, Why.BLOCKING), decide(true, false, true, false, late, jobs));
        // and blocked on something that is not done cooking yet changes nothing
        assertEquals(Trip.FILLER, decide(true, false, true, false, jobs.get(0).startTick, jobs).trip());
    }

    @Test
    public void aPhaseWithNothingLeftButTheFurnaceGoesBackWhenItIsDue() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        // stock-up is still runnable, but the plan is empty: the stock-up is not worth a delayed phase
        assertEquals(new Decision(Trip.COLLECT, Why.PHASE_END), decide(true, false, false, true, late, jobs));
        assertEquals(Trip.FILLER, decide(true, false, false, true, jobs.get(0).startTick, jobs).trip());
    }

    @Test
    public void anEmptyFillerIsWhatMakesUsWait() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long start = jobs.get(0).startTick;
        assertEquals(Trip.FILLER, decide(true, false, false, false, start, jobs).trip());
        assertEquals(Trip.WAIT, decide(false, false, false, false, start, jobs).trip());
    }

    @Test
    public void theFirstPlannedNeedBeingBlockedIsWhatBlocksThePlan() {
        // 39 cooking, no ingots in the bag: the pickaxe leads the plan and cannot be paid for
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS);
        assertTrue(s.blocking());
        // three ingots in the bag pay for the pickaxe, the head runs and nothing is stuck
        assertFalse(SmeltFiller.schedule(atTheFurnace().give(Items.IRON_INGOT, 3).cooking("iron_ingot", 36, 400), cfg, BEDS).blocking());
        // no job, no blocked list
        assertFalse(SmeltFiller.schedule(atTheFurnace(), cfg, BEDS).blocking());
    }

    @Test
    public void foodBehindItsOwnSmokerBlocksTheGatherPlan() {
        FakeFacts f = new FakeFacts().cookingFood("cooked_mutton", 7, 6, 35).give(Items.MUTTON, 3);
        List<KitNeed> plan = KitPlanner.gather(f, cfg, BEDS);
        // wood leads the gather plan, the food is behind it
        assertFalse(SmeltFiller.gatherBlocking(f, plan));
        assertTrue(SmeltFiller.gatherBlocking(f, List.of(new KitNeed(KitNeed.FOOD, 70))));
        assertFalse(SmeltFiller.gatherBlocking(f, List.of()));
    }

    @Test
    public void farAwayWorkIsNotFilteredByDistanceAnyMore() {
        // wool and food used to wait for a sheep or an animal inside the leash, now the filler is just the filler
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 39, 400), cfg, BEDS);
        assertEquals(2, s.runnable().stream().filter(n -> n.catalogueName().equals("wool")).count());
        assertEquals(2, s.runnable().stream().filter(n -> n.catalogueName().equals("food")).count());
        assertEquals(1, s.runnable().stream().filter(n -> n.catalogueName().equals("flint")).count());
    }


    @Test
    public void bedPlanksAreCappedAndShrinkWithBedsHeld() {
        assertEquals(0, SmeltFiller.planksWanted(List.of(), new FakeFacts().give(Items.WHITE_BED, 8), cfg, BEDS));
        assertEquals(12, SmeltFiller.planksWanted(List.of(), new FakeFacts(), cfg, BEDS));
        assertEquals(3, SmeltFiller.planksWanted(List.of(), new FakeFacts().give(Items.WHITE_BED, 7), cfg, BEDS));
    }
}
