package adris.altoclef.tasks.speedrun.gamer;

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
import static org.junit.Assert.assertTrue;

public class HeadLatchTest {
    private static final KitNeed IRON = new KitNeed("iron_ingot", 39);
    private static final KitNeed PICK = new KitNeed("stone_pickaxe", 2);
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 70);

    private FakeFacts f;
    private OverworldConfig cfg;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        f = new FakeFacts().give(Items.STONE_PICKAXE, 1);
        cfg = new OverworldConfig();
    }

    @Test
    public void theRunningNeedStaysHeadInsideTheDwell() {
        // the planner flipped the order under us, 2 s into the task
        assertEquals(IRON, HeadLatch.pick(IRON, 0, 40, List.of(FOOD, IRON), f));
        assertEquals(IRON, HeadLatch.pick(IRON, 0, HeadLatch.DWELL_TICKS - 1, List.of(PICK, IRON), f));
    }

    @Test
    public void thePlannerTakesOverWhenTheDwellIsUp() {
        assertEquals(FOOD, HeadLatch.pick(IRON, 0, HeadLatch.DWELL_TICKS, List.of(FOOD, IRON), f));
        // a task that has run for minutes is never held, this is only for the first few seconds
        assertEquals(PICK, HeadLatch.pick(IRON, 100, 6000, List.of(PICK, IRON), f));
    }

    @Test
    public void aNeedThatIsNoLongerOwedIsLetGo() {
        // satisfied (gone from the list)
        assertEquals(FOOD, HeadLatch.pick(IRON, 0, 10, List.of(FOOD), f));
        // or asked for differently, that is a different job
        assertEquals(FOOD, HeadLatch.pick(IRON, 0, 10, List.of(FOOD, new KitNeed("iron_ingot", 36)), f));
    }

    @Test
    public void theSameHeadOrNoRunningNeedIsJustTheHead() {
        assertEquals(IRON, HeadLatch.pick(IRON, 0, 10, List.of(IRON, FOOD), f));
        assertEquals(FOOD, HeadLatch.pick(null, 0, 10, List.of(FOOD, IRON), f));
        assertEquals(FOOD, HeadLatch.pick(null, 0, 10, List.of(FOOD), f));
    }

    @Test
    public void aClockThatWentBackwardsDoesNotHoldForEver() {
        assertEquals(FOOD, HeadLatch.pick(IRON, 5000, 10, List.of(FOOD, IRON), f));
    }

    @Test
    public void aMissingPickaxeCutsInAtOnceButAWornOneDoesNot() {
        FakeFacts none = new FakeFacts();
        assertEquals(PICK, HeadLatch.pick(IRON, 0, 10, List.of(PICK, IRON), none));
        // worn out is still in the bag (MinecraftFacts keeps it in spent, not count)
        FakeFacts worn = new FakeFacts();
        worn.spent.put(Items.STONE_PICKAXE, 1);
        assertEquals(IRON, HeadLatch.pick(IRON, 0, 10, List.of(PICK, IRON), worn));
        // a pick of any tier is a pick
        assertEquals(IRON, HeadLatch.pick(IRON, 0, 10, List.of(PICK, IRON), new FakeFacts().give(Items.DIAMOND_PICKAXE, 1)));
        // and only a pickaxe need is that urgent, a missing sword waits like anything else
        assertEquals(IRON, HeadLatch.pick(IRON, 0, 10, List.of(new KitNeed("stone_axe", 1), IRON), none));
    }

    // ---- what the planner puts first in IRON ----

    private static List<String> names(List<KitNeed> needs) {
        return needs.stream().map(KitNeed::catalogueName).toList();
    }

    // down the mine with one raw ore in the bag, everything of the starter kit but the pick, food in hand
    private FakeFacts mine() {
        FakeFacts m = new FakeFacts().give(Items.STONE_AXE, 1).give(Items.FURNACE, 1).give(Items.LADDER, 3).give(Items.RAW_IRON, 1);
        m.foodUnits = 70;
        return m;
    }

    @Test
    public void aWornStonePickDoesNotPreemptTheIron() {
        FakeFacts m = mine();
        m.spent.put(Items.STONE_PICKAXE, 1);
        List<KitNeed> plan = KitPlanner.plan(m, cfg, 8);
        assertEquals("iron_ingot", plan.get(0).catalogueName());
        // still planned, one past the one we hold, but behind all the iron work
        assertEquals(new KitNeed("stone_pickaxe", 2), plan.stream().filter(n -> n.catalogueName().equals("stone_pickaxe")).findFirst().orElseThrow());
        assertTrue(names(plan).indexOf("stone_pickaxe") > names(plan).indexOf("iron_boots"));
    }

    @Test
    public void noPickAtAllIsTheOneThingThatComesFirst() {
        List<KitNeed> plan = KitPlanner.plan(mine(), cfg, 8);
        assertEquals(new KitNeed("stone_pickaxe", 1), plan.get(0));
    }

    @Test
    public void anIronPickMakesTheWornStoneOneMoot() {
        FakeFacts m = mine().give(Items.IRON_PICKAXE, 1);
        m.spent.put(Items.STONE_PICKAXE, 1);
        assertFalse(names(KitPlanner.plan(m, cfg, 8)).contains("stone_pickaxe"));
    }

    @Test
    public void theGatherKeepsTheEightyFivePercentRule() {
        // the spare pick is asked for right away in the gather, wherever in the kit order it falls
        FakeFacts g = new FakeFacts().give(Items.OAK_LOG, 15);
        g.spent.put(Items.STONE_PICKAXE, 1);
        assertEquals(new KitNeed("stone_pickaxe", 3), KitPlanner.gather(g, cfg, 8).stream()
                .filter(n -> n.catalogueName().equals("stone_pickaxe")).findFirst().orElseThrow());
    }

    @Test
    public void aReplacementIsNeverAReasonToGoChoppingMidIron() {
        // planks and cobble in the bag, a worn pick: no log trip in the plan, the craft uses what we hold
        FakeFacts m = mine().give(Items.OAK_PLANKS, 4).give(Items.COBBLESTONE, 20);
        m.spent.put(Items.STONE_PICKAXE, 1);
        assertFalse(names(KitPlanner.plan(m, cfg, 8)).contains("log"));
    }

    // the 16:08 flip: three raw iron going into a furnace one at a time, the planner's early need dropped out of the list
    // mid move and the latch let go of it (it is not owed any more), so the head was the 39 need
    @Test
    public void theEarlyBatchStaysTheHeadWhileItsOreIsMidMove() {
        KitNeed early = new KitNeed("iron_ingot", 3);
        FakeFacts moving = new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1)
                .give(Items.LADDER, 3).give(Items.RAW_IRON, 1);
        moving.foodUnits = 70;
        moving.earlyLoad = true;
        List<KitNeed> plan = KitPlanner.plan(moving, cfg, 8);
        assertEquals(early, plan.get(0));
        assertEquals(early, HeadLatch.pick(early, 0, 20, plan, moving));
        // without the flight the plan has no early need and the latch can only hand over, which is what used to happen
        FakeFacts plain = new FakeFacts().give(Items.STONE_PICKAXE, 1).give(Items.STONE_AXE, 1).give(Items.FURNACE, 1)
                .give(Items.LADDER, 3).give(Items.RAW_IRON, 1);
        plain.foodUnits = 70;
        List<KitNeed> old = KitPlanner.plan(plain, cfg, 8);
        assertEquals(new KitNeed("iron_ingot", 40), HeadLatch.pick(early, 0, 20, old, plain));
    }
}
