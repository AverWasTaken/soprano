package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.FoodPlan;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.FoodHelper;
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

    // the gap as END_PREP asks for it: the engine builds the tick's FoodPlan from the same facts, for that phase, and hands it in
    private EndGear.Gap missing(FakeFacts f, RunState s, int beds) {
        GamerConfig all = new GamerConfig();
        all.end = cfg;
        return EndGear.missing(f, s, cfg, beds, FoodPlan.of(f, all, GamerPhase.END_PREP));
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
        assertTrue(missing(ready(), new RunState(), cfg.beds).none());
    }

    @Test
    public void emptyHandsMissEverything() {
        FakeFacts f = new FakeFacts();
        f.blocks = 0;
        f.food = 0;
        EndGear.Gap gap = missing(f, new RunState(), cfg.beds);
        assertEquals(8, gap.beds());
        assertTrue(gap.weapon());
        assertTrue(gap.waterBucket());
        assertTrue(gap.pickaxe());
        assertEquals(cfg.buildBlocks, gap.buildBlocks());
        assertTrue(gap.food());
        assertFalse(gap.none());
    }

    @Test
    public void foodGateIsTheEndsOwnFloorNotTheOverworldMinimum() {
        FakeFacts f = ready();
        // 24 is the End's line and 70 the overworld's: a bag in between is fine to walk in with
        f.food = cfg.minFoodUnits;
        assertFalse(missing(f, new RunState(), cfg.beds).food());
        f.food = cfg.minFoodUnits - 1;
        assertTrue(missing(f, new RunState(), cfg.beds).food());
        assertTrue(new OverworldConfig().minFoodUnits > cfg.minFoodUnits);
    }

    @Test
    public void rawMeatNothingCanCookIsWorthItsRawValueAtTheGate() {
        // three raw porkchop book as 24 and the food chain eats them at 3 apiece
        FakeFacts f = ready().with(Items.PORKCHOP, 3);
        f.food = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        // the bare bag figure the gate used to read: exactly on the line, so it never fired
        assertEquals(cfg.minFoodUnits, f.food);
        // what there is to eat is 9, and nothing standing here cooks it
        assertTrue(missing(f, new RunState(), cfg.beds).food());
        // real cooked food on top of it is another matter
        f.with(Items.COOKED_BEEF, 3);
        f.food += 3 * FoodHelper.plannedNutrition(Items.COOKED_BEEF);
        assertFalse(missing(f, new RunState(), cfg.beds).food());
    }

    @Test
    public void aSmokerStandingNextToRawMeatDoesNotMakeItDinnerAtTheGate() {
        // three raw porkchop with a smoker and coal at hand: the kit would cook them, END_PREP never walks back to the smoker
        FakeFacts f = ready().with(Items.PORKCHOP, 3).with(Items.COAL, 2);
        f.smokerPlaced = true;
        f.food = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        assertEquals(cfg.minFoodUnits, f.food);
        assertTrue(missing(f, new RunState(), cfg.beds).food());
    }

    @Test
    public void bedsAreCountedAcrossColours() {
        FakeFacts f = ready().with(Items.WHITE_BED, 5).with(Items.RED_BED, 2);
        assertEquals(1, missing(f, new RunState(), cfg.beds).beds());
    }

    @Test
    public void secondAttemptCanGoWithNoBeds() {
        FakeFacts f = ready().with(Items.WHITE_BED, 0);
        assertTrue(missing(f, new RunState(), 0).none());
    }

    @Test
    public void buildBlocksGateIsTheMinimumButTheTargetIsTheFullStack() {
        FakeFacts f = ready();
        f.blocks = cfg.minBuildBlocks;
        assertEquals(0, missing(f, new RunState(), cfg.beds).buildBlocks());
        f.blocks = cfg.minBuildBlocks - 1;
        assertEquals(cfg.buildBlocks, missing(f, new RunState(), cfg.beds).buildBlocks());
    }

    @Test
    public void gearLeftInTheEndCountsWhileItIsStillThere() {
        FakeFacts f = new FakeFacts().with(Items.IRON_SWORD, 1);
        RunState s = diedInEndAt(90_000);
        s.endDrops.put("water_bucket", 1);
        s.endDrops.put("white_bed", 3);
        s.endDrops.put("iron_pickaxe", 1);
        f.gameTime = 92_000;
        EndGear.Gap gap = missing(f, s, cfg.beds);
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
        assertTrue(missing(f, s, cfg.beds).waterBucket());
        f.gameTime = 90_000 + cfg.dropLifetimeTicks - 1;
        assertFalse(missing(f, s, cfg.beds).waterBucket());
    }

    @Test
    public void dropsWithoutADeathInTheEndAreStaleCache() {
        RunState s = new RunState();
        s.endDrops.put("water_bucket", 1);
        assertTrue(missing(new FakeFacts(), s, cfg.beds).waterBucket());
        RunState diedElsewhere = new RunState();
        RunState.Death d = new RunState.Death();
        d.dimension = "NETHER";
        d.gameTime = 99_999;
        diedElsewhere.deaths.add(d);
        diedElsewhere.endDrops.put("water_bucket", 1);
        assertTrue(missing(new FakeFacts(), diedElsewhere, cfg.beds).waterBucket());
    }

    @Test
    public void diamondToolsSatisfyTheGate() {
        FakeFacts f = ready().with(Items.IRON_SWORD, 0).with(Items.IRON_PICKAXE, 0).with(Items.DIAMOND_SWORD, 1).with(Items.DIAMOND_PICKAXE, 1);
        assertTrue(missing(f, new RunState(), cfg.beds).none());
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
        assertTrue(missing(f, new RunState(), cfg.beds).none());
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
