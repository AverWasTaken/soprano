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
import static org.junit.Assert.assertNotNull;
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
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3);
        f.foodUnits = 70;
        return f;
    }

    @Test
    public void withNoJobTheScheduleIsJustThePlan() {
        FakeFacts f = atTheFurnace();
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertEquals(KitPlanner.plan(f, cfg, BEDS), s.runnable());
        assertTrue(s.blocked().isEmpty());
        assertEquals(40, find(s.runnable(), "iron_ingot").count());
    }

    @Test
    public void ingotsInTheFurnaceSatisfyTheIronNeed() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        assertEquals(40, f.pendingOutput(Items.IRON_INGOT));
        assertNull(find(KitPlanner.plan(f, cfg, BEDS), "iron_ingot"));
        // but not a smaller batch, that one still needs the rest
        FakeFacts small = atTheFurnace().cooking("iron_ingot", 20, 400);
        assertEquals(40, find(KitPlanner.plan(small, cfg, BEDS), "iron_ingot").count());
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
        assertEquals(10 + 60, KitPlanner.progressOf(f, new KitNeed(KitNeed.FOOD, 80), FoodPlan.ofBeds(f, cfg, BEDS)));
    }

    @Test
    public void ironJobsAreNotFood() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
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
        FakeFacts f = atTheFurnace().give(Items.IRON_INGOT, 10).cooking("iron_ingot", 30, 400);
        assertNull(find(KitPlanner.plan(f, cfg, BEDS), "iron_ingot"));
    }

    @Test
    public void craftsThatWantIngotsWeDoNotHoldAreBlocked() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        assertEquals(List.of("iron_pickaxe", "iron_axe", "bucket", "flint_and_steel", "shield", "shears", "iron_chestplate",
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
        assertNull(find(s.runnable(), "iron_axe"));
        assertEquals(new KitNeed("iron_axe", 1), find(s.blocked(), "iron_axe"));
        assertNull(find(s.blocked(), "iron_pickaxe"));
    }

    @Test
    public void fillerComesInUsefulnessOrder() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
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
        FakeFacts f = new FakeFacts().cooking("iron_ingot", 40, 400);
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1);
        f.foodUnits = 70;
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        // ladders are sticks, no ingots, so they are not stuck behind the furnace like the pickaxe is
        assertEquals(new KitNeed("ladder", 3), s.runnable().get(0));
        assertNull(find(s.blocked(), "ladder"));
    }

    @Test
    public void stockUpIsCappedByTheConfig() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
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
    public void heldPlanksCountTowardsTheLogStockUp() {
        // 3 logs and 20 planks is 32 planks of wood, the 8 log target is met without a trip
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400).give(Items.OAK_LOG, 3).give(Items.OAK_PLANKS, 20);
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS).runnable(), "log"));
        // 3 logs and 8 planks is 20 of the 32: the old answer asked for 5 more logs, 2 logs' worth of planks make it 3 more
        FakeFacts shy = atTheFurnace().cooking("iron_ingot", 39, 400).give(Items.OAK_LOG, 3).give(Items.OAK_PLANKS, 8);
        assertEquals(new KitNeed("log", 6), find(SmeltFiller.schedule(shy, cfg, BEDS).runnable(), "log"));
        assertEquals(0, SmeltFiller.logStockTarget(new FakeFacts().give(Items.OAK_PLANKS, 32), 8, true));
        assertEquals(8, SmeltFiller.logStockTarget(new FakeFacts().give(Items.OAK_PLANKS, 3), 8, true));
        assertEquals(8, SmeltFiller.logStockTarget(new FakeFacts(), 8, true));
    }

    @Test
    public void noLogStockUpDownTheMine() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 39, 400);
        assertNotNull(find(SmeltFiller.schedule(f, cfg, BEDS, true).runnable(), "log"));
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS, false).runnable(), "log"));
        // the rest of the filler (the plan, the prep) is not touched by depth, only the stock-ups are
        Schedule up = SmeltFiller.schedule(f, cfg, BEDS, true);
        assertEquals(up.runnable().stream().filter(n -> !up.isStockUp(n)).toList(), SmeltFiller.schedule(f, cfg, BEDS, false).runnable());
    }

    @Test
    public void everythingStockedMeansNothingRunnable() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        f.foodUnits = 130;
        f.buildBlocks = 64;
        f.give(Items.WHITE_WOOL, 33).give(Items.OAK_LOG, 8).give(Items.OAK_PLANKS, 20).give(Items.FLINT, 1);
        Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertTrue(names(s.runnable()).toString(), s.runnable().isEmpty());
        assertFalse(s.blocked().isEmpty());
    }

    @Test
    public void ownedFlintAndSteelAsksForNoFlint() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400).give(Items.FLINT_AND_STEEL, 1);
        assertNull(find(SmeltFiller.schedule(f, cfg, BEDS).runnable(), "flint"));
        FakeFacts has = atTheFurnace().cooking("iron_ingot", 40, 400).give(Items.FLINT, 1);
        assertNull(find(SmeltFiller.schedule(has, cfg, BEDS).runnable(), "flint"));
    }

    @Test
    public void aTypoInTheExtrasIsIgnored() {
        cfg.smeltExtras = List.of(new OverworldConfig.KitItem("fod", 5), new OverworldConfig.KitItem("log", 8));
        List<String> n = names(SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS).runnable());
        assertEquals("log", n.get(n.size() - 1));
        assertFalse(n.contains("fod"));
    }

    @Test
    public void everyFillerNameIsInTheCatalogue() {
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
        for (KitNeed need : s.runnable()) {
            if (!need.isSpecial()) {
                assertTrue(need.catalogueName(), adris.altoclef.TaskCatalogue.taskExists(need.catalogueName()));
            }
        }
    }

    // ---- stock-ups are surface work

    @Test
    public void downTheMineNoStockUpIsRunnable() {
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 400);
        f.foodUnits = 20;
        Schedule deep = SmeltFiller.schedule(f, cfg, BEDS, false);
        assertTrue(deep.stockUps().isEmpty());
        assertNull(find(deep.runnable(), "log"));
        Schedule up = SmeltFiller.schedule(f, cfg, BEDS, true);
        assertEquals(List.of("food", "wool", "build_blocks", "log"), names(up.stockUps()));
        for (KitNeed extra : up.stockUps()) {
            assertTrue(up.runnable().contains(extra));
        }
        // the stock-up food is the +30 over the target, the plan's own 70 unit need is not one of them
        assertEquals(cfg.targetFoodUnits + 30, find(up.stockUps(), KitNeed.FOOD).count());
    }

    @Test
    public void aDueJobCutsAStockUpShortButNotARealNeed() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        assertEquals(new Decision(Trip.COLLECT, Why.STOCK_UP), SmeltFiller.decide(true, false, false, true, late, jobs, cfg));
        // an ordinary need mid way is still left to finish
        assertEquals(Trip.FILLER, SmeltFiller.decide(true, false, false, false, late, jobs, cfg).trip());
        // and a job that is not due waits for nobody
        assertEquals(Trip.FILLER, SmeltFiller.decide(true, false, false, true, jobs.get(0).startTick, jobs, cfg).trip());
    }

    private Decision decide(boolean fillerLeft, boolean atBoundary, boolean interrupt, long now, List<RunState.FurnaceJob> jobs) {
        return SmeltFiller.decide(fillerLeft, atBoundary, interrupt, now, jobs, cfg);
    }

    @Test
    public void nothingToDoMeansWaitingAtTheFurnaceUntilItIsDone() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long start = jobs.get(0).startTick;
        assertEquals(new Decision(Trip.WAIT, Why.NONE), decide(false, true, false, start, jobs));
        assertEquals(new Decision(Trip.COLLECT, Why.IDLE), decide(false, true, false, start + 100 * 20, jobs));
    }

    @Test
    public void aFinishedFurnaceIsOnlyFetchedBetweenTwoNeeds() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        // mid need: keep going, even though it is long done
        assertEquals(Trip.FILLER, decide(true, false, false, late, jobs).trip());
        assertEquals(new Decision(Trip.COLLECT, Why.BOUNDARY), decide(true, true, false, late, jobs));
        // at a boundary but not done yet: filler
        assertEquals(Trip.FILLER, decide(true, true, false, jobs.get(0).startTick, jobs).trip());
    }

    @Test
    public void nearlyDoneCountsAsDone() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long soon = jobs.get(0).doneTick - Math.round(cfg.furnaceWaitSeconds * 20) + 1;
        assertEquals(Trip.COLLECT, decide(true, true, false, soon, jobs).trip());
        assertEquals(Trip.FILLER, decide(true, true, false, soon - 40, jobs).trip());
    }

    @Test
    public void anIronCraftWaitingOnTheOutputDoesNotPullUsOffTheCurrentNeed() {
        // the ladder run: the pickaxe is blocked behind the cooking ingots for the whole cook, and that is no reason to leave
        FakeFacts f = atTheFurnace().cooking("iron_ingot", 40, 100);
        assertFalse(SmeltFiller.schedule(f, cfg, BEDS).blocked().isEmpty());
        long late = f.furnaceJobs().get(0).doneTick + 50;
        // the decision only ever hears about the interrupts below, a blocked craft is not one of them
        assertEquals(Trip.FILLER, decide(true, false, false, late, f.furnaceJobs()).trip());
        assertEquals(new Decision(Trip.COLLECT, Why.BOUNDARY), decide(true, true, false, late, f.furnaceJobs()));
    }

    @Test
    public void theEarlyPickAndAStuckSmokerAreWorthAnInterrupt() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long late = jobs.get(0).doneTick + 50;
        assertEquals(new Decision(Trip.COLLECT, Why.INTERRUPT), decide(true, false, true, late, jobs));
        // an interrupt for a job that is not done cooking yet changes nothing
        assertEquals(Trip.FILLER, decide(true, false, true, jobs.get(0).startTick, jobs).trip());
    }

    @Test
    public void anEmptyFillerIsWhatMakesUsWait() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 10, 100).furnaceJobs();
        long start = jobs.get(0).startTick;
        assertEquals(Trip.FILLER, decide(true, false, false, start, jobs).trip());
        assertEquals(Trip.WAIT, decide(false, false, false, start, jobs).trip());
    }

    @Test
    public void aVisitedFurnaceIsNotRevisitedBeforeItsNewTimer() {
        List<RunState.FurnaceJob> jobs = atTheFurnace().cooking("iron_ingot", 3, 30).furnaceJobs();
        RunState.FurnaceJob j = jobs.get(0);
        long now = 5000;
        long slack = Math.round(cfg.furnaceWaitSeconds * 20);
        // we got there, 3 items and 11 s of input left: the old slack would call this due after a second and turn us around
        FurnaceJobs.afterVisit(jobs, j, 3, slack + 20, now);
        assertEquals(Trip.FILLER, decide(true, true, false, now, jobs).trip());
        assertEquals(Trip.FILLER, decide(true, true, false, now + 20, jobs).trip());
        assertEquals(Trip.FILLER, decide(true, true, true, now + slack + 19, jobs).trip());
        assertEquals(Trip.COLLECT, decide(true, true, false, now + slack + 20, jobs).trip());
    }

    @Test
    public void foodBehindItsOwnSmokerIsTheInterruptNotAnIronCraft() {
        // GATHER's interrupt: the food is the head of the plan and the smoker is on the last batch
        FakeFacts f = atTheFurnace().cookingFood("cooked_mutton", 3, 6, 15).give(Items.MUTTON, 7);
        f.foodUnits = 0;
        assertTrue(SmeltFiller.gatherBlocking(f, List.of(new KitNeed(KitNeed.FOOD, 70))));
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
        Schedule s = SmeltFiller.schedule(atTheFurnace().cooking("iron_ingot", 40, 400), cfg, BEDS);
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

    // ---- standing by for a smoker (a smoker is 5 s an item, the furnace 10)

    @Test
    public void aRunningSmokerIsStoodByAndAFurnaceIsNot() {
        FakeFacts iron = atTheFurnace().cooking("iron_ingot", 40, 400);
        assertNull(SmeltFiller.smokerJob(iron.furnaceJobs()));
        assertFalse("the furnace keeps its filler", SmeltFiller.standBy(iron.furnaceJobs(), iron.gameTime(), -1));
        FakeFacts meat = atTheFurnace().cookingFood("cooked_mutton", 8, 6, 40);
        assertNotNull(SmeltFiller.smokerJob(meat.furnaceJobs()));
        assertTrue(SmeltFiller.standBy(meat.furnaceJobs(), meat.gameTime(), -1));
        assertFalse("no jobs at all", SmeltFiller.standBy(List.of(), 0, -1));
    }

    // 13:11:01: the beef the cook left in the smoker was never lit, got a "~25 s" job and the iron phase stood by it for "0 s", then
    // took the raw beef back out. only a smoker that is really cooking is stood by
    @Test
    public void meatLeftColdInASmokerIsNotStoodBy() {
        FakeFacts cold = atTheFurnace().cookingFood("cooked_beef", 5, 8, 0);
        cold.furnaceJobs().get(0).stranded = true;
        assertNull(SmeltFiller.smokerJob(cold.furnaceJobs()));
        assertFalse(SmeltFiller.standBy(cold.furnaceJobs(), cold.gameTime(), -1));
        // lit, it is a smoker like any other
        cold.furnaceJobs().get(0).stranded = false;
        assertNotNull(SmeltFiller.smokerJob(cold.furnaceJobs()));
        assertTrue(SmeltFiller.standBy(cold.furnaceJobs(), cold.gameTime(), -1));
        // and a cold one beside a real one does not hide it
        FakeFacts both = atTheFurnace().cookingFood("cooked_beef", 5, 8, 0).cookingFood("cooked_mutton", 3, 6, 15);
        both.furnaceJobs().get(0).stranded = true;
        assertEquals("cooked_mutton", SmeltFiller.smokerJob(both.furnaceJobs()).output);
    }

    @Test
    public void aSmokerBesideALongFurnaceBatchIsStillStoodBy() {
        FakeFacts both = atTheFurnace().cooking("iron_ingot", 40, 400).cookingFood("cooked_beef", 3, 8, 15);
        assertTrue(SmeltFiller.standBy(both.furnaceJobs(), both.gameTime(), -1));
        // the smoker is the one waited for, not the soonest of the lot
        assertEquals("smoker", SmeltFiller.smokerJob(both.furnaceJobs()).kind);
        // and once the smoker is out the furnace alone gets its filler back
        both.furnaceJobs().removeIf(j -> "smoker".equals(j.kind));
        assertFalse(SmeltFiller.standBy(both.furnaceJobs(), both.gameTime(), -1));
    }

    @Test
    public void theStandByEndsThirtySecondsPastDue() {
        FakeFacts meat = atTheFurnace().cookingFood("cooked_mutton", 8, 6, 40);
        RunState.FurnaceJob smoker = SmeltFiller.smokerJob(meat.furnaceJobs());
        long until = SmeltFiller.standByUntil(smoker, meat.gameTime());
        assertEquals(smoker.doneTick + 30 * 20, until);
        assertTrue(SmeltFiller.standBy(meat.furnaceJobs(), smoker.doneTick, until));
        assertTrue(SmeltFiller.standBy(meat.furnaceJobs(), until, until));
        assertFalse("a smoker that never finishes does not hold the run", SmeltFiller.standBy(meat.furnaceJobs(), until + 1, until));
    }

    @Test
    public void aBigBatchIsNotStoodByAtAll() {
        // a stack of 64 is five minutes, that is not a smoker to stand at. judged once, when the job is first seen
        FakeFacts big = atTheFurnace().cookingFood("cooked_beef", 64, 8, 320);
        RunState.FurnaceJob smoker = SmeltFiller.smokerJob(big.furnaceJobs());
        assertFalse(SmeltFiller.quickEnough(smoker, big.gameTime()));
        assertFalse(SmeltFiller.quickEnough(smoker, smoker.doneTick - 61 * 20));
        assertTrue(SmeltFiller.quickEnough(smoker, smoker.doneTick - 60 * 20));
        // 8 meat is 40 s
        FakeFacts small = atTheFurnace().cookingFood("cooked_mutton", 8, 6, 40);
        assertTrue(SmeltFiller.quickEnough(SmeltFiller.smokerJob(small.furnaceJobs()), small.gameTime()));
    }

    @Test
    public void aJobAlreadyPastDueGetsItsThirtySecondsFromNowNotFromThen() {
        FakeFacts meat = atTheFurnace().cookingFood("cooked_mutton", 8, 6, 40);
        RunState.FurnaceJob smoker = SmeltFiller.smokerJob(meat.furnaceJobs());
        long late = smoker.doneTick + 500;
        assertEquals(late + 30 * 20, SmeltFiller.standByUntil(smoker, late));
    }
}
