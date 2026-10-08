package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EndGearTest {
    private final EndConfig cfg = new EndConfig();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private FakeFacts ready() {
        FakeFacts f = new FakeFacts().with(Items.WHITE_BED, 8).with(Items.IRON_SWORD, 1).with(Items.WATER_BUCKET, 1)
                .with(Items.IRON_PICKAXE, 1);
        f.wear(Items.IRON_HELMET).wear(Items.IRON_CHESTPLATE).wear(Items.IRON_LEGGINGS).wear(Items.IRON_BOOTS);
        f.armorPoints = 15;
        return f;
    }

    private static RunState diedInEndAt(long gameTime) {
        RunState s = new RunState();
        RunState.Death d = new RunState.Death();
        d.dimension = "END";
        d.gameTime = gameTime;
        s.deaths.add(d);
        return s;
    }

    @Test
    public void anEndDeathRelaxesTheBedsEvenOnAFreshAttemptCount() {
        // PhaseMachine.regress puts the attempt back to 1 on the way from DRAGON to END_PREP
        assertFalse(EndGear.relaxed(1, 0));
        assertTrue(EndGear.relaxed(2, 0));
        assertTrue(EndGear.relaxed(1, 1));
        assertTrue(EndGear.relaxed(1, EndRules.endDeaths(diedInEndAt(100))));
    }

    @Test
    public void fullKitMissesNothing() {
        assertTrue(EndGear.missing(ready(), new RunState(), cfg, cfg.beds).none());
    }

    @Test
    public void emptyHandsMissEverything() {
        FakeFacts f = new FakeFacts();
        f.blocks = 0;
        f.food = 0;
        EndGear.Gap gap = EndGear.missing(f, new RunState(), cfg, cfg.beds);
        assertEquals(8, gap.beds());
        assertTrue(gap.weapon());
        assertTrue(gap.waterBucket());
        assertTrue(gap.pickaxe());
        assertEquals(cfg.buildBlocks, gap.buildBlocks());
        assertTrue(gap.food());
        assertFalse(gap.none());
    }

    @Test
    public void bedsAreCountedAcrossColours() {
        FakeFacts f = ready().with(Items.WHITE_BED, 5).with(Items.RED_BED, 2);
        assertEquals(1, EndGear.missing(f, new RunState(), cfg, cfg.beds).beds());
    }

    @Test
    public void secondAttemptCanGoWithNoBeds() {
        FakeFacts f = ready().with(Items.WHITE_BED, 0);
        assertTrue(EndGear.missing(f, new RunState(), cfg, 0).none());
    }

    @Test
    public void buildBlocksGateIsTheMinimumButTheTargetIsTheFullStack() {
        FakeFacts f = ready();
        f.blocks = cfg.minBuildBlocks;
        assertEquals(0, EndGear.missing(f, new RunState(), cfg, cfg.beds).buildBlocks());
        f.blocks = cfg.minBuildBlocks - 1;
        assertEquals(cfg.buildBlocks, EndGear.missing(f, new RunState(), cfg, cfg.beds).buildBlocks());
    }

    @Test
    public void gearLeftInTheEndCountsWhileItIsStillThere() {
        FakeFacts f = new FakeFacts().with(Items.IRON_SWORD, 1);
        RunState s = diedInEndAt(90_000);
        s.endDrops.put("water_bucket", 1);
        s.endDrops.put("white_bed", 3);
        s.endDrops.put("iron_pickaxe", 1);
        f.gameTime = 92_000;
        EndGear.Gap gap = EndGear.missing(f, s, cfg, cfg.beds);
        assertFalse(gap.waterBucket());
        assertFalse(gap.pickaxe());
        assertEquals(5, gap.beds());
    }

    @Test
    public void despawnedDropsAreForgotten() {
        FakeFacts f = new FakeFacts();
        RunState s = diedInEndAt(90_000);
        s.endDrops.put("water_bucket", 1);
        // 6000 ticks is the despawn, margin makes it 5400
        f.gameTime = 90_000 + cfg.dropLifetimeTicks;
        assertTrue(EndGear.missing(f, s, cfg, cfg.beds).waterBucket());
        f.gameTime = 90_000 + cfg.dropLifetimeTicks - 1;
        assertFalse(EndGear.missing(f, s, cfg, cfg.beds).waterBucket());
    }

    @Test
    public void dropsWithoutADeathInTheEndAreStaleCache() {
        RunState s = new RunState();
        s.endDrops.put("water_bucket", 1);
        assertTrue(EndGear.missing(new FakeFacts(), s, cfg, cfg.beds).waterBucket());
        RunState diedElsewhere = new RunState();
        RunState.Death d = new RunState.Death();
        d.dimension = "NETHER";
        d.gameTime = 99_999;
        diedElsewhere.deaths.add(d);
        diedElsewhere.endDrops.put("water_bucket", 1);
        assertTrue(EndGear.missing(new FakeFacts(), diedElsewhere, cfg, cfg.beds).waterBucket());
    }

    @Test
    public void diamondToolsSatisfyTheGate() {
        FakeFacts f = ready().with(Items.IRON_SWORD, 0).with(Items.IRON_PICKAXE, 0).with(Items.DIAMOND_SWORD, 1).with(Items.DIAMOND_PICKAXE, 1);
        assertTrue(EndGear.missing(f, new RunState(), cfg, cfg.beds).none());
    }

    @Test
    public void armorWeHoldButDoNotWearGetsWorn() {
        FakeFacts f = ready();
        f.worn.remove(Items.IRON_CHESTPLATE);
        f.with(Items.DIAMOND_CHESTPLATE, 1);
        List<Item> wear = EndGear.armorToWear(f);
        assertEquals(List.of(Items.DIAMOND_CHESTPLATE), wear);
    }

    @Test
    public void wornIronBeatsASpareDiamondPieceInTheSameSlot() {
        FakeFacts f = ready().with(Items.DIAMOND_HELMET, 1);
        assertTrue(EndGear.armorToWear(f).isEmpty());
    }

    @Test
    public void goldenBootsDoNotHoldTheBootsSlot() {
        FakeFacts f = ready();
        f.worn.remove(Items.IRON_BOOTS);
        f.wear(Items.GOLDEN_BOOTS);
        assertEquals(List.of(Items.IRON_BOOTS), EndGear.armorToWear(f));
    }

    @Test
    public void lowArmorIsReportedNotBlocking() {
        FakeFacts f = ready();
        f.armorPoints = 4;
        assertTrue(EndGear.armorLow(f, cfg));
        assertTrue(EndGear.missing(f, new RunState(), cfg, cfg.beds).none());
    }

    @Test
    public void spawnBedShortfallAsksForOneMoreAndGoesNegativeWithASpare() {
        FakeFacts f = ready();
        RunState s = new RunState();
        // 8 held, 8 required: nothing short, but a spawn bed makes it 1
        assertEquals(0, EndGear.bedShortfall(f, s, cfg, cfg.beds, false));
        assertEquals(1, EndGear.bedShortfall(f, s, cfg, cfg.beds, true));
        // placing the spawn bed takes one out of the inventory and the want goes away with it, no crafting loop
        f.with(Items.WHITE_BED, 9);
        assertEquals(0, EndGear.bedShortfall(f, s, cfg, cfg.beds, true));
        f.with(Items.WHITE_BED, 8);
        assertEquals(0, EndGear.bedShortfall(f, s, cfg, cfg.beds, false));
        assertEquals(-8, EndGear.bedShortfall(f, s, cfg, 0, false));
    }

    @Test
    public void spawnBedWantFollowsConfigAndState() {
        RunState s = new RunState();
        assertTrue(EndGear.wantSpawnBed(s, cfg));
        assertEquals(9, EndGear.bedTarget(cfg, true));
        assertEquals(8, EndGear.bedTarget(cfg, false));
        s.spawnBedSet = true;
        assertFalse(EndGear.wantSpawnBed(s, cfg));
        s.spawnBedSet = false;
        s.spawnBedGaveUp = true;
        assertFalse(EndGear.wantSpawnBed(s, cfg));
        cfg.placeSpawnNearPortal = false;
        s.spawnBedGaveUp = false;
        assertFalse(EndGear.wantSpawnBed(s, cfg));
    }

    @Test
    public void pickupOrderIsBedsToolsBucketArmor() {
        FakeFacts f = new FakeFacts().with(Items.WHITE_BED, 2);
        assertEquals(Items.RED_BED, EndGear.nextPickup(f, cfg, item -> item == Items.RED_BED || item == Items.IRON_PICKAXE));
        assertEquals(Items.IRON_PICKAXE, EndGear.nextPickup(f.with(Items.WHITE_BED, 8), cfg, item -> item == Items.IRON_PICKAXE || item == Items.WATER_BUCKET));
        assertEquals(Items.WATER_BUCKET, EndGear.nextPickup(f.with(Items.IRON_PICKAXE, 1).with(Items.IRON_SWORD, 1), cfg, item -> item == Items.WATER_BUCKET || item == Items.DIAMOND_HELMET));
        assertEquals(Items.DIAMOND_HELMET, EndGear.nextPickup(f.with(Items.WATER_BUCKET, 1), cfg, item -> item == Items.DIAMOND_HELMET));
    }

    @Test
    public void nothingToFetchWhenWeAlreadyHaveIt() {
        FakeFacts f = ready();
        assertNull(EndGear.nextPickup(f, cfg, item -> item == Items.IRON_PICKAXE || item == Items.WATER_BUCKET
                || item == Items.WHITE_BED || item == Items.IRON_HELMET || item == Items.DIAMOND_HELMET));
    }
}
