package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
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

public class EarlyIronPickTest {
    private static final int BEDS = 8;
    // the default kit's 40 ingots minus the shears' 2, the kit these tests run with (setUp)
    private static final int TOTAL = 38;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        // the pick on its own: the shears riding along are their own tests further down (withShears)
        cfg = new OverworldConfig();
        cfg.ironKit.removeIf(k -> k.item.equals("shears"));
    }

    private OverworldConfig withShears() {
        return new OverworldConfig();
    }

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    // underground with the starter kit and the food, nothing iron yet
    private static FakeFacts mining() {
        FakeFacts f = new FakeFacts();
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3);
        f.foodUnits = 70;
        return f;
    }

    private boolean due(FakeFacts f) {
        return EarlyIronPick.due(f, cfg, KitPlanner.ingotsNeeded(f, cfg));
    }

    @Test
    public void threeRawIronAndNoPickIsTheTrigger() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        assertTrue(due(f));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        // just the three first, the whole kit's worth right behind it
        assertEquals(new KitNeed("iron_ingot", 3), plan.get(0));
        assertEquals(new KitNeed("iron_ingot", TOTAL), plan.get(1));
    }

    @Test
    public void twoRawIronIsNotEnoughYet() {
        FakeFacts f = mining().give(Items.RAW_IRON, 2);
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(f, cfg, BEDS).get(0));
    }

    @Test
    public void anyIronOrBetterPickMeansNoEarlyBatch() {
        FakeFacts iron = mining().give(Items.RAW_IRON, 3).give(Items.IRON_PICKAXE, 1);
        assertFalse(due(iron));
        FakeFacts diamond = mining().give(Items.RAW_IRON, 3).give(Items.DIAMOND_PICKAXE, 1);
        assertFalse(due(diamond));
    }

    @Test
    public void doesNotFireAgainWhileTheFirstThreeCook() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3).cooking("iron_ingot", 3, 30);
        assertFalse(due(f));
        assertFalse(KitPlanner.plan(f, cfg, BEDS).contains(new KitNeed("iron_ingot", 3)));
    }

    @Test
    public void doesNotFireAgainOnceTheIngotsAreInTheBagOrTheCraftIsDone() {
        assertFalse(due(mining().give(Items.RAW_IRON, 3).give(Items.IRON_INGOT, 3)));
        assertFalse(due(mining().give(Items.RAW_IRON, 3).give(Items.IRON_PICKAXE, 1)));
    }

    @Test
    public void theEarlyJobsPendingIngotsCountAsHeld() {
        // three cooking: the pickaxe is paid for, the rest of the kit (36) still needs mining, and it is asked for as the
        // 40 it always was, held and pending ingots come off it (KitPlanner.iron), so the planner does not double plan
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        assertEquals(3, f.pendingOutput(Items.IRON_INGOT));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_ingot", TOTAL), plan.get(0));
        assertEquals(1, plan.stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
        // and with the whole 40 cooking there is nothing left to mine
        assertNull(plan.stream().filter(n -> n.catalogueName().equals("iron_ingot") && n.count() == 3).findFirst().orElse(null));
        assertFalse(names(KitPlanner.plan(mining().cooking("iron_ingot", 40, 400), cfg, BEDS)).contains("iron_ingot"));
    }

    @Test
    public void threeIngotsInTheBagMakeThePickTheHeadNeed() {
        FakeFacts f = mining().give(Items.IRON_INGOT, 3);
        assertTrue(EarlyIronPick.craftFirst(f, cfg));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_pickaxe", 1), plan.get(0));
        // the mining need is still there behind it, minus nothing: the pick eats the three it was sized with
        assertEquals("iron_ingot", plan.get(1).catalogueName());
        assertEquals(1, names(plan).stream().filter(n -> n.equals("iron_pickaxe")).count());
        // two is not three
        assertFalse(EarlyIronPick.craftFirst(mining().give(Items.IRON_INGOT, 2), cfg));
        assertEquals("iron_ingot", KitPlanner.plan(mining().give(Items.IRON_INGOT, 2), cfg, BEDS).get(0).catalogueName());
    }

    @Test
    public void allTheOreAlreadyMinedIsOneBigSmeltNotTwo() {
        FakeFacts f = mining().give(Items.RAW_IRON, 40);
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(f, cfg, BEDS).get(0));
    }

    @Test
    public void theSettingOffIsTheOldPlan() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        f.earlyIronPick = false;
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(f, cfg, BEDS).get(0));
        f.give(Items.IRON_INGOT, 3);
        assertFalse(EarlyIronPick.craftFirst(f, cfg));
        assertEquals("iron_ingot", KitPlanner.plan(f, cfg, BEDS).get(0).catalogueName());
    }

    @Test
    public void aKitWithoutAnIronPickHasNothingToRush() {
        cfg.ironKit.removeIf(k -> k.item.equals("iron_pickaxe"));
        assertFalse(due(mining().give(Items.RAW_IRON, 3)));
    }

    @Test
    public void theEarlyBatchIsRecognisedForSmeltSurface() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        assertTrue(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), f, cfg));
        // the big batch keeps the go up first rule
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", TOTAL), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_pickaxe", 1), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(null, f, cfg));
        // only the pickaxe left to pay for: the ore is all in, that is the big batch
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), complete().give(Items.RAW_IRON, 3), cfg));
    }

    // everything except the iron pickaxe
    private FakeFacts complete() {
        FakeFacts full = mining();
        for (Item i : new Item[]{Items.IRON_AXE, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS,
                Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS}) {
            full.give(i, 1);
        }
        full.give(Items.BUCKET, 2).give(Items.WHITE_WOOL, 24);
        full.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        return full;
    }

    @Test
    public void theFurnaceTripIsForcedOnlyWhileThePickIsStillOwed() {
        FakeFacts cooking = mining().cooking("iron_ingot", 3, 30);
        assertTrue(EarlyIronPick.collectNow(cooking, cfg));
        // already holding them (or the pick): nothing special, the normal boundaries apply
        assertFalse(EarlyIronPick.collectNow(mining().give(Items.IRON_INGOT, 3).cooking("iron_ingot", 3, 30), cfg));
        assertFalse(EarlyIronPick.collectNow(mining().give(Items.IRON_PICKAXE, 1).cooking("iron_ingot", 3, 30), cfg));
        // nothing cooking, nothing to go back for
        assertFalse(EarlyIronPick.collectNow(mining(), cfg));
        FakeFacts off = mining().cooking("iron_ingot", 3, 30);
        off.earlyIronPick = false;
        assertFalse(EarlyIronPick.collectNow(off, cfg));
    }

    @Test
    public void thePickWaitingOnItsIngotsPullsTheBotOutOfTheMineTheMomentTheyAreDone() {
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        SmeltFiller.Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        // the mining need leads and is runnable, collectNow is what says the pick is worth the detour
        assertEquals("iron_ingot", s.runnable().get(0).catalogueName());
        boolean interrupt = EarlyIronPick.collectNow(f, cfg);
        assertTrue(interrupt);
        // mid mining, nothing done yet: keep digging
        FurnacePlan.Moment mid = new FurnacePlan.Moment(true, false, interrupt, false, true);
        assertEquals(FurnacePlan.Call.LEAVE, FurnacePlan.plan(f.furnaceJobs(), mid, f.gameTime(), null).verdicts().get(0).call());
        // done: go, whatever the mining need is up to
        FurnacePlan.Verdict go = FurnacePlan.plan(f.furnaceJobs(), mid, f.seconds(31).gameTime(), null).verdicts().get(0);
        assertEquals(FurnacePlan.Call.COLLECT_NOW, go.call());
        assertEquals(FurnacePlan.Why.INTERRUPT, go.why());
        // without the early pick rule the same moment waits for the end of the need
        FurnacePlan.Moment plain = new FurnacePlan.Moment(true, false, false, false, true);
        assertEquals(FurnacePlan.Call.LEAVE, FurnacePlan.plan(f.furnaceJobs(), plain, f.gameTime(), null).verdicts().get(0).call());
    }

    @Test
    public void theCookingScheduleKeepsMiningAndBlocksThePickUntilTheIngotsAreBack() {
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        SmeltFiller.Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertEquals("iron_ingot", s.runnable().get(0).catalogueName());
        assertTrue(names(s.blocked()).contains("iron_pickaxe"));
        // 31 s on, the job is due
        assertTrue(FurnacePlan.anyDue(f.furnaceJobs(), f.seconds(31).gameTime()));
    }

    // the 16:08 run: the first raw iron went into the furnace, the bag stopped showing three, the early need vanished and the
    // 40 need took the head (and then pulled the ore out of the furnace again)
    @Test
    public void aLoadInFlightKeepsTheEarlyNeedWithTheOreOutOfTheBag() {
        FakeFacts f = mining();
        f.earlyLoad = true;
        assertTrue(due(f));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_ingot", 3), plan.get(0));
        assertEquals(new KitNeed("iron_ingot", TOTAL), plan.get(1));
        // even with a single ore still in the bag (the move goes one at a time)
        f.give(Items.RAW_IRON, 1);
        assertEquals(new KitNeed("iron_ingot", 3), KitPlanner.plan(f, cfg, BEDS).get(0));
        // and the same bag without the flag is the old plan
        FakeFacts without = mining().give(Items.RAW_IRON, 1);
        assertEquals(new KitNeed("iron_ingot", TOTAL), KitPlanner.plan(without, cfg, BEDS).get(0));
    }

    @Test
    public void aLoadInFlightEndsWhenItIsCookingOrDone() {
        FakeFacts cooking = mining().cooking("iron_ingot", 3, 30);
        cooking.earlyLoad = true;
        assertFalse(due(cooking));
        FakeFacts ingots = mining().give(Items.IRON_INGOT, 3);
        ingots.earlyLoad = true;
        assertFalse(due(ingots));
        FakeFacts pick = mining().give(Items.IRON_PICKAXE, 1);
        pick.earlyLoad = true;
        assertFalse(due(pick));
        FakeFacts off = mining();
        off.earlyLoad = true;
        off.earlyIronPick = false;
        assertFalse(due(off));
    }

    @Test
    public void aLoadInFlightStillCountsAsTheEarlyBatchForSmeltSurface() {
        FakeFacts f = mining();
        f.earlyLoad = true;
        assertTrue(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", TOTAL), f, cfg));
    }

    @Test
    public void theFlightWindowHasAnEnd() {
        assertFalse(FurnacePlan.earlyLoadInFlight(-1, 100));
        assertTrue(FurnacePlan.earlyLoadInFlight(100, 100));
        assertTrue(FurnacePlan.earlyLoadInFlight(100, 100 + FurnacePlan.EARLY_LOAD_TICKS - 1));
        assertFalse(FurnacePlan.earlyLoadInFlight(100, 100 + FurnacePlan.EARLY_LOAD_TICKS));
        // a clock that went backwards (a relog, another world) is not a flight
        assertFalse(FurnacePlan.earlyLoadInFlight(5000, 10));
    }

    @Test
    public void trackStartsTheFlightWhenTheEarlyBatchLeadsAndDoesNotRestartIt() {
        RunState state = new RunState();
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        f.gameTime = 400;
        KitNeed early = new KitNeed("iron_ingot", 3);
        EarlyIronPick.track(state, early, f, cfg);
        assertEquals(400, state.earlyLoadTick);
        // the facts see the flight now (MinecraftFacts reads the state), later ticks leave the stamp alone
        f.earlyLoad = true;
        f.gameTime = 460;
        EarlyIronPick.track(state, early, f, cfg);
        assertEquals(400, state.earlyLoadTick);
        // a head that is something else for a moment (food) does not end it either
        EarlyIronPick.track(state, new KitNeed(KitNeed.FOOD, 70), f, cfg);
        assertEquals(400, state.earlyLoadTick);
    }

    @Test
    public void trackLetsGoOnceTheEarlyNeedIsNoLongerDue() {
        RunState state = new RunState();
        state.earlyLoadTick = 400;
        FakeFacts f = mining().give(Items.IRON_INGOT, 3);
        f.earlyLoad = true;
        EarlyIronPick.track(state, new KitNeed("iron_pickaxe", 1), f, cfg);
        assertEquals(-1, state.earlyLoadTick);
        // and a run that never started one stays at none
        EarlyIronPick.track(state, null, mining(), cfg);
        assertEquals(-1, state.earlyLoadTick);
    }

    // ---- the shears ride along: 5 ore instead of 3 while the kit owes them

    @Test
    public void fiveWithShearsOwedThreeWithout() {
        OverworldConfig shears = withShears();
        FakeFacts f = mining();
        assertEquals(5, EarlyIronPick.batch(f, shears));
        assertEquals(3, EarlyIronPick.batch(f, cfg));
        // shears already in the bag: back to the pick's three
        assertEquals(3, EarlyIronPick.batch(mining().give(Items.SHEARS, 1), shears));
    }

    @Test
    public void withShearsOwedThreeRawIronIsNotTheTriggerFiveIs() {
        OverworldConfig shears = withShears();
        FakeFacts three = mining().give(Items.RAW_IRON, 3);
        assertFalse(EarlyIronPick.due(three, shears, KitPlanner.ingotsNeeded(three, shears)));
        FakeFacts five = mining().give(Items.RAW_IRON, 5);
        assertTrue(EarlyIronPick.due(five, shears, KitPlanner.ingotsNeeded(five, shears)));
        List<KitNeed> plan = KitPlanner.plan(five, shears, BEDS);
        assertEquals(new KitNeed("iron_ingot", 5), plan.get(0));
        assertTrue(EarlyIronPick.isEarlyBatch(plan.get(0), five, shears));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), five, shears));
    }

    @Test
    public void theTotalIronCountIsTheSameWithTheShearsInTheEarlyBatch() {
        // the shared need is sized by the whole kit, shears included, whatever the early batch is: nothing counted twice
        OverworldConfig shears = withShears();
        FakeFacts five = mining().give(Items.RAW_IRON, 5);
        int total = KitPlanner.ingotsNeeded(five, shears);
        assertEquals(KitPlanner.ingotsNeeded(five, cfg) + KitPlanner.ingotCost("shears"), total);
        List<KitNeed> plan = KitPlanner.plan(five, shears, BEDS);
        assertEquals(new KitNeed("iron_ingot", total), plan.get(1));
        assertEquals(2, plan.stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
        // the five cooking come off the shared need like any pending ingots: no second batch mined for them
        FakeFacts cooking = mining().cooking("iron_ingot", 5, 50);
        List<KitNeed> during = KitPlanner.plan(cooking, shears, BEDS);
        assertEquals(new KitNeed("iron_ingot", total), during.get(0));
        assertEquals(1, during.stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
    }

    @Test
    public void theEarlyBatchMakesThePickThenTheShearsBeforeAnyMoreMining() {
        OverworldConfig shears = withShears();
        FakeFacts out = mining().give(Items.IRON_INGOT, 5);
        assertTrue(EarlyIronPick.craftFirst(out, shears));
        List<KitNeed> plan = KitPlanner.plan(out, shears, BEDS);
        assertEquals(new KitNeed("iron_pickaxe", 1), plan.get(0));
        assertEquals(new KitNeed("shears", 1), plan.get(1));
        assertEquals("iron_ingot", plan.get(2).catalogueName());
        // the pick is made and two are left: the shears are the head now, the mining waits for them
        FakeFacts pick = mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_INGOT, 2);
        assertTrue(EarlyIronPick.shearsFirst(pick, shears));
        assertEquals(new KitNeed("shears", 1), KitPlanner.plan(pick, shears, BEDS).get(0));
        // three or four ingots (a chest, a load that came back short): the pick still goes first, the shears wait their turn
        FakeFacts three = mining().give(Items.IRON_INGOT, 3);
        assertTrue(EarlyIronPick.craftFirst(three, shears));
        assertFalse(EarlyIronPick.shearsWithThePick(three, shears));
        List<KitNeed> short3 = KitPlanner.plan(three, shears, BEDS);
        assertEquals(new KitNeed("iron_pickaxe", 1), short3.get(0));
        assertEquals("iron_ingot", short3.get(1).catalogueName());
        assertFalse(EarlyIronPick.shearsWithThePick(mining().give(Items.IRON_INGOT, 4), shears));
    }

    @Test
    public void theShearsNeverTakeThePicksIngots() {
        OverworldConfig shears = withShears();
        // pick still owed, two or three ingots in the bag: those are the pick's
        assertFalse(EarlyIronPick.shearsFirst(mining().give(Items.IRON_INGOT, 3), shears));
        assertFalse(EarlyIronPick.shearsFirst(mining().give(Items.IRON_INGOT, 2), shears));
        // shears held, or not in the kit, or the setting off: nothing to rush
        FakeFacts held = mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_INGOT, 2).give(Items.SHEARS, 1);
        assertFalse(EarlyIronPick.shearsFirst(held, shears));
        assertFalse(EarlyIronPick.shearsFirst(mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_INGOT, 2), cfg));
        FakeFacts off = mining().give(Items.IRON_PICKAXE, 1).give(Items.IRON_INGOT, 2);
        off.earlyIronPick = false;
        assertFalse(EarlyIronPick.shearsFirst(off, shears));
    }

    @Test
    public void theInterruptWaitsForAllFive() {
        OverworldConfig shears = withShears();
        assertTrue(EarlyIronPick.collectNow(mining().cooking("iron_ingot", 5, 50), shears));
        // three of the five out: still going back for the other two
        assertTrue(EarlyIronPick.collectNow(mining().give(Items.IRON_INGOT, 3).cooking("iron_ingot", 2, 20), shears));
        assertFalse(EarlyIronPick.collectNow(mining().give(Items.IRON_INGOT, 5).cooking("iron_ingot", 2, 20), shears));
    }

    private static RunState.FurnaceJob job(String output) {
        RunState.FurnaceJob job = new RunState.FurnaceJob();
        job.output = output;
        return job;
    }

    @Test
    public void onlyTheIronJobEndsTheEarlyLoad() {
        // the meat landing in a smoker mid load is not the iron cooking
        assertFalse(EarlyIronPick.endsLoad(List.of(job("cooked_beef"))));
        assertFalse(EarlyIronPick.endsLoad(List.of()));
        assertTrue(EarlyIronPick.endsLoad(List.of(job("iron_ingot"))));
        assertTrue(EarlyIronPick.endsLoad(List.of(job("cooked_porkchop"), job("iron_ingot"))));
    }
}
