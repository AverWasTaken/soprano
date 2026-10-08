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

    // underground with the starter kit and the food, nothing iron yet
    private static FakeFacts mining() {
        FakeFacts f = new FakeFacts();
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3);
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
        assertEquals(new KitNeed("iron_ingot", 39), plan.get(1));
    }

    @Test
    public void twoRawIronIsNotEnoughYet() {
        FakeFacts f = mining().give(Items.RAW_IRON, 2);
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", 39), KitPlanner.plan(f, cfg, BEDS).get(0));
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
        // 39 it always was, held and pending ingots come off it (KitPlanner.iron), so the planner does not double plan
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        assertEquals(3, f.pendingOutput(Items.IRON_INGOT));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_ingot", 39), plan.get(0));
        assertEquals(1, plan.stream().filter(n -> n.catalogueName().equals("iron_ingot")).count());
        // and with the whole 39 cooking there is nothing left to mine
        assertNull(plan.stream().filter(n -> n.catalogueName().equals("iron_ingot") && n.count() == 3).findFirst().orElse(null));
        assertFalse(names(KitPlanner.plan(mining().cooking("iron_ingot", 39, 400), cfg, BEDS)).contains("iron_ingot"));
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
        FakeFacts f = mining().give(Items.RAW_IRON, 39);
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", 39), KitPlanner.plan(f, cfg, BEDS).get(0));
    }

    @Test
    public void theSettingOffIsTheOldPlan() {
        FakeFacts f = mining().give(Items.RAW_IRON, 3);
        f.earlyIronPick = false;
        assertFalse(due(f));
        assertEquals(new KitNeed("iron_ingot", 39), KitPlanner.plan(f, cfg, BEDS).get(0));
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
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 39), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_pickaxe", 1), f, cfg));
        assertFalse(EarlyIronPick.isEarlyBatch(null, f, cfg));
        // only the pickaxe left to pay for: the ore is all in, that is the big batch
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 3), complete().give(Items.RAW_IRON, 3), cfg));
    }

    // everything except the iron pickaxe
    private FakeFacts complete() {
        FakeFacts full = mining();
        for (Item i : new Item[]{Items.IRON_SWORD, Items.FLINT_AND_STEEL, Items.SHIELD, Items.SHEARS,
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
        assertEquals(SmeltFiller.Trip.FILLER, SmeltFiller.decide(true, false, interrupt, f.gameTime(), f.furnaceJobs(), cfg).trip());
        // done: go, whatever the mining need is up to
        SmeltFiller.Decision go = SmeltFiller.decide(true, false, interrupt, f.seconds(31).gameTime(), f.furnaceJobs(), cfg);
        assertEquals(SmeltFiller.Trip.COLLECT, go.trip());
        assertEquals(SmeltFiller.Why.INTERRUPT, go.why());
        // without the early pick rule the same moment waits for the end of the need
        assertEquals(SmeltFiller.Trip.FILLER, SmeltFiller.decide(true, false, false, f.gameTime(), f.furnaceJobs(), cfg).trip());
    }

    @Test
    public void theCookingScheduleKeepsMiningAndBlocksThePickUntilTheIngotsAreBack() {
        FakeFacts f = mining().cooking("iron_ingot", 3, 30);
        SmeltFiller.Schedule s = SmeltFiller.schedule(f, cfg, BEDS);
        assertEquals("iron_ingot", s.runnable().get(0).catalogueName());
        assertTrue(names(s.blocked()).contains("iron_pickaxe"));
        // 31 s on, the job is due
        assertTrue(FurnaceJobs.anyDue(f.furnaceJobs(), f.seconds(31).gameTime(), 0));
    }

    // the 16:08 run: the first raw iron went into the furnace, the bag stopped showing three, the early need vanished and the
    // 39 need took the head (and then pulled the ore out of the furnace again)
    @Test
    public void aLoadInFlightKeepsTheEarlyNeedWithTheOreOutOfTheBag() {
        FakeFacts f = mining();
        f.earlyLoad = true;
        assertTrue(due(f));
        List<KitNeed> plan = KitPlanner.plan(f, cfg, BEDS);
        assertEquals(new KitNeed("iron_ingot", 3), plan.get(0));
        assertEquals(new KitNeed("iron_ingot", 39), plan.get(1));
        // even with a single ore still in the bag (the move goes one at a time)
        f.give(Items.RAW_IRON, 1);
        assertEquals(new KitNeed("iron_ingot", 3), KitPlanner.plan(f, cfg, BEDS).get(0));
        // and the same bag without the flag is the old plan
        FakeFacts without = mining().give(Items.RAW_IRON, 1);
        assertEquals(new KitNeed("iron_ingot", 39), KitPlanner.plan(without, cfg, BEDS).get(0));
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
        assertFalse(EarlyIronPick.isEarlyBatch(new KitNeed("iron_ingot", 39), f, cfg));
    }

    @Test
    public void theFlightWindowHasAnEnd() {
        assertFalse(EarlyIronPick.inFlight(-1, 100));
        assertTrue(EarlyIronPick.inFlight(100, 100));
        assertTrue(EarlyIronPick.inFlight(100, 100 + EarlyIronPick.LOAD_WINDOW - 1));
        assertFalse(EarlyIronPick.inFlight(100, 100 + EarlyIronPick.LOAD_WINDOW));
        // a clock that went backwards (a relog, another world) is not a flight
        assertFalse(EarlyIronPick.inFlight(5000, 10));
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
}
