package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class KitRunnerTest {
    private static class StubTask extends Task {
        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return false;
        }

        @Override
        protected String toDebugString() {
            return "stub";
        }
    }

    private final List<Integer> foodTargets = new ArrayList<>();
    private int built;
    private boolean unknown;
    private KitRunner runner;
    private StubContext ctx;

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        ctx = new StubContext();
        built = 0;
        unknown = false;
        foodTargets.clear();
        runner = new KitRunner((need, equip, food) -> {
            built++;
            if (KitNeed.FOOD.equals(need.catalogueName())) {
                foodTargets.add(food);
            }
            return unknown ? null : new StubTask();
        });
    }

    @Test
    public void sameNeedKeepsTheSameTaskObject() {
        List<KitNeed> needs = List.of(new KitNeed("iron_ingot", 39), new KitNeed("iron_pickaxe", 1));
        Task first = runner.run(ctx, needs);
        for (int i = 0; i < 50; i++) {
            assertSame(first, runner.run(ctx, new ArrayList<>(needs)));
        }
        assertEquals(1, built);
    }

    @Test
    public void aChangedNeedBuildsANewTask() {
        Task first = runner.run(ctx, List.of(new KitNeed("iron_ingot", 39)));
        Task second = runner.run(ctx, List.of(new KitNeed("iron_ingot", 36)));
        assertNotSame(first, second);
        Task third = runner.run(ctx, List.of(new KitNeed("iron_pickaxe", 1)));
        assertNotSame(second, third);
        assertEquals(3, built);
    }

    @Test
    public void onlyTheFirstNeedRuns() {
        runner.run(ctx, List.of(new KitNeed("iron_ingot", 39), new KitNeed("iron_pickaxe", 1)));
        assertEquals(1, built);
        assertEquals("Looking for iron", runner.hud());
    }

    @Test
    public void nothingToDoIsNoTask() {
        assertNull(runner.run(ctx, List.of()));
        assertNull(runner.hud());
    }

    @Test
    public void resetForgetsTheTask() {
        List<KitNeed> needs = List.of(new KitNeed("iron_ingot", 39));
        Task first = runner.run(ctx, needs);
        runner.reset();
        assertNotSame(first, runner.run(ctx, needs));
    }

    @Test
    public void anUnknownNameFailsOnceAndRunsNothing() {
        unknown = true;
        List<KitNeed> needs = List.of(new KitNeed("not_an_item", 1));
        assertNull(runner.run(ctx, needs));
        assertNull(runner.run(ctx, needs));
        assertNull(runner.run(ctx, needs));
        assertEquals(1, ctx.fails.size());
        assertTrue(ctx.fails.get(0).contains("not_an_item"));
    }

    @Test
    public void growingCountsTellTheWatchdog() {
        List<KitNeed> needs = List.of(new KitNeed("iron_ingot", 39));
        runner.run(ctx, needs);
        assertTrue(ctx.progress.isEmpty());
        ctx.facts.give(Items.IRON_INGOT, 1);
        runner.run(ctx, needs);
        assertEquals(1, ctx.progress.size());
        runner.run(ctx, needs);
        assertEquals("no change, no ping", 1, ctx.progress.size());
    }

    @Test
    public void aBagOfRottenFleshDoesNotSatisfyTheFoodNeed() {
        // 20 rotten flesh is 80 nutrition for CollectFoodTask, and 0 for what we would actually eat
        ctx.facts.give(Items.ROTTEN_FLESH, 20);
        ctx.facts.junkFoodUnits = 80;
        List<KitNeed> plan = KitPlanner.gather(ctx.facts, ctx.cfg.overworld, 8);
        KitNeed food = plan.stream().filter(n -> n.catalogueName().equals(KitNeed.FOOD)).findFirst().orElseThrow();
        assertEquals(70, food.count());
        assertEquals(150, KitRunner.foodTarget(food, ctx.facts));
        runner.run(ctx, List.of(food));
        assertEquals(List.of(150), foodTargets);
    }

    @Test
    public void junkPickedUpLaterRebuildsTheFoodTask() {
        KitNeed food = new KitNeed(KitNeed.FOOD, 70);
        Task first = runner.run(ctx, List.of(food));
        ctx.facts.junkFoodUnits = 8;
        Task second = runner.run(ctx, List.of(food));
        assertNotSame(first, second);
        assertEquals(List.of(70, 78), foodTargets);
    }

    @Test
    public void nonFoodNeedsIgnoreJunk() {
        ctx.facts.junkFoodUnits = 80;
        assertEquals(32, KitRunner.foodTarget(new KitNeed(KitNeed.BUILD_BLOCKS, 32), ctx.facts));
    }

    @Test
    public void equipNeedUsesTheItemsWeCarry() {
        ctx.facts.give(Items.IRON_HELMET, 1);
        List<List<Item>> seen = new ArrayList<>();
        KitRunner r = new KitRunner((need, equip, food) -> {
            seen.add(equip);
            return new StubTask();
        });
        r.run(ctx, List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)));
        assertEquals(List.of(List.of(Items.IRON_HELMET)), seen);
    }
}
