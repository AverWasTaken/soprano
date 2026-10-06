package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.GatherPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.IronPhase;
import adris.altoclef.tasks.speedrun.gamer.phases.PortalPhase;
import baritone.api.utils.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the pure bits of the overworld phases: isDone over fake facts, the config defaults, and the small helpers
public class OverworldPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    private final RunState state = new RunState();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void phasesNameThemselves() {
        assertEquals(GamerPhase.GATHER, new GatherPhase().phase());
        assertEquals(GamerPhase.IRON, new IronPhase().phase());
        assertEquals(GamerPhase.PORTAL, new PortalPhase().phase());
        assertEquals("Getting started", new GatherPhase().hud());
        assertEquals(null, new IronPhase().hudState());
    }

    @Test
    public void gatherIsDoneWithStoneToolsFurnaceAndFood() {
        GatherPhase gather = new GatherPhase();
        FakeFacts f = new FakeFacts();
        assertFalse(gather.isDone(f, state, cfg));
        f.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1);
        f.foodUnits = 70;
        assertFalse("no furnace yet", gather.isDone(f, state, cfg));
        f.give(Items.FURNACE, 1);
        assertTrue(gather.isDone(f, state, cfg));
        f.foodUnits = 69;
        assertFalse("food under the minimum", gather.isDone(f, state, cfg));
    }

    @Test
    public void gatherAcceptsBetterToolsAndABlastFurnace() {
        FakeFacts f = new FakeFacts().give(Items.IRON_PICKAXE, 1).give(Items.DIAMOND_SWORD, 1).give(Items.BLAST_FURNACE, 1);
        f.foodUnits = 100;
        assertTrue(new GatherPhase().isDone(f, state, cfg));
    }

    @Test
    public void ironIsDoneOnlyWithTheWholeKitWoolAndFood() {
        IronPhase iron = new IronPhase();
        FakeFacts f = new FakeFacts();
        assertFalse(iron.isDone(f, state, cfg));
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS, Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS)) {
            f.give(i, 1);
        }
        f.worn.addAll(List.of(Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS));
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        assertFalse("no wool", iron.isDone(f, state, cfg));
        f.give(Items.GREEN_WOOL, 24);
        assertTrue(iron.isDone(f, state, cfg));
        f.foodUnits = 99;
        assertFalse("food under the target", iron.isDone(f, state, cfg));
    }

    @Test
    public void ironIsNotDoneWhileIronIsStillCooking() {
        IronPhase iron = new IronPhase();
        cfg.end.beds = 2;
        cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.NONE;
        FakeFacts f = new FakeFacts().give(Items.WHITE_WOOL, 6);
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS)) {
            f.give(i, 1);
        }
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        assertTrue("the kit is whole", iron.isDone(f, state, cfg));
        // a job nobody collected (more ingots than the kit needed): leaving now would leave them in the furnace
        f.cooking("iron_ingot", 5, 30);
        assertFalse(iron.isDone(f, state, cfg));
    }

    @Test
    public void ironWantsWoolForTheConfiguredBedCount() {
        IronPhase iron = new IronPhase();
        cfg.end.beds = 2;
        FakeFacts f = new FakeFacts().give(Items.WHITE_WOOL, 6);
        for (var i : List.of(Items.STONE_PICKAXE, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.FLINT_AND_STEEL,
                Items.SHIELD, Items.SHEARS)) {
            f.give(i, 1);
        }
        f.give(Items.BUCKET, 2).give(Items.LADDER, 3);
        f.foodUnits = 100;
        cfg.overworld.armorPlan = OverworldConfig.ArmorPlan.NONE;
        assertTrue(iron.isDone(f, state, cfg));
    }

    @Test
    public void portalIsDoneInTheNetherOnly() {
        PortalPhase portal = new PortalPhase();
        FakeFacts f = new FakeFacts();
        assertFalse(portal.isDone(f, state, cfg));
        f.dimension = Dimension.END;
        assertFalse(portal.isDone(f, state, cfg));
        f.dimension = Dimension.NETHER;
        assertTrue(portal.isDone(f, state, cfg));
    }

    @Test
    public void gatherSkipsOnTimeoutOnceBothStoneToolsAreHeld() {
        GatherPhase gather = new GatherPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, gather.onTimeout(ctx, 1, "slow"));
        assertEquals(Timeout.STUCK, gather.onTimeout(ctx, 2, "slow"));
        ctx.facts.give(Items.STONE_PICKAXE, 1).give(Items.STONE_SWORD, 1);
        assertEquals(Timeout.SKIP, gather.onTimeout(ctx, 1, "no food"));
        assertEquals(Timeout.SKIP, gather.onTimeout(ctx, 2, "no food"));
    }

    @Test
    public void ironSkipsOnlyWithTheEssentials() {
        IronPhase iron = new IronPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, iron.onTimeout(ctx, 1, "slow"));
        assertEquals(Timeout.STUCK, iron.onTimeout(ctx, 2, "slow"));
        ctx.facts.give(Items.IRON_PICKAXE, 1).give(Items.FLINT_AND_STEEL, 1).give(Items.BUCKET, 2);
        assertEquals(Timeout.SKIP, iron.onTimeout(ctx, 2, "slow"));
    }

    @Test
    public void portalTimesOutOnceIntoObsidianThenStops() {
        PortalPhase portal = new PortalPhase();
        StubContext ctx = new StubContext();
        assertEquals(Timeout.RETRY, portal.onTimeout(ctx, 1, "stalled"));
        assertEquals("OBSIDIAN", ctx.state.portalMethod);
        assertEquals(Timeout.STUCK, portal.onTimeout(ctx, 2, "stalled"));
    }

    @Test
    public void configDefaultsStaySane() {
        OverworldConfig o = cfg.overworld;
        assertEquals(70, o.minFoodUnits);
        assertEquals(100, o.targetFoodUnits);
        assertEquals(7.0, o.castGiveUpMinutes, 0);
        assertEquals(30.0, o.tablePickupSeconds, 0);
        assertEquals(60, o.ruinedPortalLootRadius);
        assertEquals(48, o.villageLootRadius);
        assertEquals(6, o.villageChestJobRadius);
        assertEquals(3, o.villageLootMaxChests);
        assertEquals(240.0, o.villageLootSeconds, 0);
        assertEquals(48, o.villageBedRadius);
        assertEquals(16, o.villageBedEvidenceRadius);
        assertEquals(120.0, o.villageBedSeconds, 0);
        assertEquals(20.0, o.villageBedEachSeconds, 0);
        assertEquals(32, o.portalBuildBlocks);
        assertEquals(24, o.tableRecoverRadius);
        assertEquals(3.0, o.tableUseCooldownSeconds, 0);
        assertEquals(120.0, o.tableRecoverCooldownSeconds, 0);
        assertEquals(32, o.golemHuntRadius);
        assertEquals(5, o.golemMinBlocks);
        assertEquals(14.0, o.golemMinHealth, 0);
        assertEquals(0.3, o.golemSafeMargin, 0);
        assertEquals(2, o.golemMaxAttempts);
        // the margin is what puts our feet on a 3.0 level against a 2.7 golem
        assertTrue(o.golemSafeMargin > 0 && o.golemSafeMargin < 1);
        assertTrue(o.golemAbortHealth < o.golemMinHealth);
        assertTrue(o.noLavaSeconds < o.castGiveUpMinutes * 60);
        assertEquals(OverworldConfig.ArmorPlan.FULL_IRON, o.armorPlan);
        assertEquals(7, o.ironKit.size());
        assertEquals(3, o.starterKit.size());
    }

    @Test
    public void needKinds() {
        assertTrue(new KitNeed("iron_ingot", 3).isGathering());
        assertTrue(new KitNeed(KitNeed.FOOD, 70).isGathering());
        assertFalse(new KitNeed("iron_pickaxe", 1).isGathering());
        assertTrue(new KitNeed(KitNeed.EQUIP_ARMOR, 1).isSpecial());
        assertFalse(new KitNeed("wool", 24).isSpecial());
    }

    @Test
    public void hudWordsAreHumanReadable() {
        FakeFacts f = new FakeFacts();
        assertEquals("Getting food", KitRunner.words(new KitNeed(KitNeed.FOOD, 70), f));
        assertEquals("Looking for iron", KitRunner.words(new KitNeed("iron_ingot", 39), f));
        f.give(Items.RAW_IRON, 3);
        assertEquals("Smelting iron", KitRunner.words(new KitNeed("iron_ingot", 39), f));
        assertEquals("Chopping wood", KitRunner.words(new KitNeed("stone_pickaxe", 1), f));
        f.give(Items.WOODEN_PICKAXE, 1);
        assertEquals("Mining stone", KitRunner.words(new KitNeed("stone_pickaxe", 1), f));
        assertEquals("Collecting wool", KitRunner.words(new KitNeed("wool", 24), f));
        f.give(Items.SHEARS, 1);
        assertEquals("Shearing sheep", KitRunner.words(new KitNeed("wool", 24), f));
        assertEquals("Making iron chestplate", KitRunner.words(new KitNeed("iron_chestplate", 1), f));
    }

    @Test
    public void portalChestHeuristic() {
        assertTrue(RuinedPortalLoot.plausible(70, false, true));
        assertFalse("no netherrack, a village chest", RuinedPortalLoot.plausible(70, false, false));
        assertFalse("underwater", RuinedPortalLoot.plausible(70, true, true));
        assertFalse("buried", RuinedPortalLoot.plausible(30, false, true));
    }

    @Test
    public void pillagerRadius() {
        List<Vec3> pillagers = List.of(new Vec3(100, 70, 100));
        assertTrue(DangerFilter.nearAny(new BlockPos(120, 70, 100), pillagers, 40));
        assertFalse(DangerFilter.nearAny(new BlockPos(150, 70, 100), pillagers, 40));
        assertFalse(DangerFilter.nearAny(new BlockPos(0, 0, 0), List.of(), 40));
    }
}
