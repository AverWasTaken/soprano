package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.PortalPlanner.Method;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PortalPlannerTest {
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

    @Test
    public void poolGoesFirstUntilItGivesUp() {
        assertTrue(PortalPlanner.usePool(true, false, Method.CAST));
        assertTrue("a failed pool never comes back", !PortalPlanner.usePool(true, true, Method.CAST));
        assertTrue("switched off means the cast like before", !PortalPlanner.usePool(false, false, Method.CAST));
        assertTrue("obsidian is the end of the lava ways", !PortalPlanner.usePool(true, false, Method.OBSIDIAN));
    }

    @Test
    public void castsFirst() {
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 0, cfg, false, false));
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 300, cfg, true, true));
        assertEquals("the cast keeps most of the 14 minute portal budget", 7.0, cfg.castGiveUpMinutes, 0);
    }

    @Test
    public void givesUpTheCastOnTheClock() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit - 1, cfg, true, false));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, limit, cfg, true, true));
    }

    @Test
    public void theClockAloneNeverPicksObsidianWithoutADiamondPickaxe() {
        double limit = cfg.castGiveUpMinutes * 60;
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit, cfg, true, false));
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, limit * 10, cfg, false, false));
    }

    @Test
    public void aTimeoutDuringThePoolHandsOverToTheCastFirst() {
        assertEquals(Method.CAST, PortalPlanner.afterTimeout(Method.CAST, true, true));
        assertEquals(Method.CAST, PortalPlanner.afterTimeout(Method.CAST, true, false));
    }

    // maxAttempts 2: the pool stall is paid for, the cast still gets both of its tries
    @Test
    public void aPoolStallDoesNotUseUpTheCastsAttempt() {
        PortalPlanner.TimeoutPlan stall = PortalPlanner.onTimeout(Method.CAST, 1, 2, true, false, true);
        assertTrue(stall.retry());
        assertEquals(Method.CAST, stall.method());
        assertTrue(stall.poolTimedOut());
        // engine attempt 2 is the cast's first try: its timeout is still a retry, and may now go obsidian
        PortalPlanner.TimeoutPlan cast1 = PortalPlanner.onTimeout(Method.CAST, 2, 2, false, stall.poolTimedOut(), true);
        assertTrue(cast1.retry());
        assertEquals(Method.OBSIDIAN, cast1.method());
        // attempt 3 is the second cast try, after that the existing end of the line
        assertTrue(!PortalPlanner.onTimeout(Method.OBSIDIAN, 3, 2, false, true, true).retry());
        // no diamond pickaxe: the retry casts again
        assertEquals(Method.CAST, PortalPlanner.onTimeout(Method.CAST, 2, 2, false, true, false).method());
    }

    @Test
    public void withoutAPoolStallTheAttemptsAreTheOldTwo() {
        assertTrue(PortalPlanner.onTimeout(Method.CAST, 1, 2, false, false, true).retry());
        assertTrue(!PortalPlanner.onTimeout(Method.OBSIDIAN, 2, 2, false, false, true).retry());
    }

    @Test
    public void aTimeoutOutsideThePoolGoesObsidianOnlyWithThePickaxe() {
        assertEquals(Method.OBSIDIAN, PortalPlanner.afterTimeout(Method.CAST, false, true));
        assertEquals(Method.CAST, PortalPlanner.afterTimeout(Method.CAST, false, false));
        assertEquals(Method.OBSIDIAN, PortalPlanner.afterTimeout(Method.OBSIDIAN, true, false));
    }

    @Test
    public void noLavaFlipsEarlyOnlyWithThePickaxe() {
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 149, cfg, false, true));
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.CAST, 150, cfg, false, true));
        // no pickaxe: keep wandering for a lake until the clock says stop
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 400, cfg, false, false));
        // lava seen: the cast has a chance
        assertEquals(Method.CAST, PortalPlanner.decide(Method.CAST, 400, cfg, true, true));
    }

    @Test
    public void obsidianIsSticky() {
        assertEquals(Method.OBSIDIAN, PortalPlanner.decide(Method.OBSIDIAN, 0, cfg, true, false));
    }

    @Test
    public void parsesMethodNamesLeniently() {
        assertEquals(Method.CAST, PortalPlanner.parse(null));
        assertEquals(Method.CAST, PortalPlanner.parse("nonsense"));
        assertEquals(Method.OBSIDIAN, PortalPlanner.parse("OBSIDIAN"));
        assertEquals(Method.OBSIDIAN, PortalPlanner.parse("obsidian"));
        assertEquals(Method.CAST, PortalPlanner.parse(new RunState().portalMethod));
    }

    @Test
    public void gateOnABareInventoryAsksForEverythingInOrder() {
        FakeFacts f = new FakeFacts();
        List<String> names = PortalPlanner.gate(f, cfg, 10).stream().map(KitNeed::catalogueName).toList();
        assertEquals(List.of("food", "bucket", "water_bucket", "flint_and_steel", "build_blocks"), names);
    }

    @Test
    public void gateIsEmptyWhenReady() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1);
        f.foodUnits = 70;
        f.buildBlocks = 32;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void fireChargeIsALightToo() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FIRE_CHARGE, 1);
        f.foodUnits = 70;
        f.buildBlocks = 32;
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void gateAsksForOneMoreEmptyBucketWhenOnlyTheWaterOneIsHeld() {
        FakeFacts f = new FakeFacts().give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1);
        f.foodUnits = 70;
        f.buildBlocks = 32;
        assertEquals(List.of(new KitNeed("bucket", 1)), PortalPlanner.gate(f, cfg, 10));
    }

    @Test
    public void gateWantsArmorOn() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1)
                .give(Items.IRON_BOOTS, 1);
        f.foodUnits = 70;
        f.buildBlocks = 32;
        assertEquals(List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)), PortalPlanner.gate(f, cfg, 10));
        f.worn.add(Items.IRON_BOOTS);
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    private FakeFacts readyToGo() {
        FakeFacts f = new FakeFacts().give(Items.BUCKET, 1).give(Items.WATER_BUCKET, 1).give(Items.FLINT_AND_STEEL, 1);
        f.foodUnits = 70;
        f.buildBlocks = 32;
        return f;
    }

    @Test
    public void gateMakesAGoldHelmetFromGoldInTheBagThenWearsIt() {
        FakeFacts f = readyToGo().give(Items.GOLD_INGOT, 5);
        assertEquals(List.of(new KitNeed("golden_helmet", 1)), PortalPlanner.gate(f, cfg, 10));
        f.give(Items.GOLD_INGOT, -5).give(Items.GOLDEN_HELMET, 1);
        assertEquals(List.of(new KitNeed(KitNeed.EQUIP_ARMOR, 1)), PortalPlanner.gate(f, cfg, 10));
        f.worn.add(Items.GOLDEN_HELMET);
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void gateSettlesForGoldBootsAndThenForNothing() {
        assertEquals(List.of(new KitNeed("golden_boots", 1)), PortalPlanner.gate(readyToGo().give(Items.GOLD_INGOT, 4), cfg, 10));
        // no mining detour: three ingots are not a piece of armor and the gate lets us go
        assertTrue(PortalPlanner.gate(readyToGo().give(Items.GOLD_INGOT, 3), cfg, 10).isEmpty());
        assertTrue(PortalPlanner.gate(readyToGo(), cfg, 10).isEmpty());
    }

    @Test
    public void gateLeavesGoldAloneWhenAPieceIsAlreadyOn() {
        FakeFacts f = readyToGo().give(Items.GOLD_INGOT, 9).give(Items.GOLDEN_BOOTS, 1);
        f.worn.add(Items.GOLDEN_BOOTS);
        assertTrue(PortalPlanner.gate(f, cfg, 10).isEmpty());
    }

    @Test
    public void recordsTheNetherEndOnArrivalOnce() {
        RunState state = new RunState();
        FakeFacts f = new FakeFacts();
        f.x = 10;
        f.y = 70;
        f.z = -4;
        PortalPlanner.recordArrival(state, f);
        assertNull(state.netherPortal);
        f.dimension = Dimension.NETHER;
        PortalPlanner.recordArrival(state, f);
        assertNotNull(state.netherPortal);
        assertEquals(new RunState.Pos(10, 70, -4), state.netherPortal);
        f.x = 99;
        PortalPlanner.recordArrival(state, f);
        assertEquals(new RunState.Pos(10, 70, -4), state.netherPortal);
    }
}
