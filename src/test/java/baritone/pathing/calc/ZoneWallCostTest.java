package baritone.pathing.calc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.MovementHelper;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

// a cell we may not walk through is priced as a block to mine, and air mines in a tick. the pack zone walls also go in as
// break protected (see RunZones.blocksAt), which is what makes them solid. a plain walk-through rule (the dragon cloud, the
// bastion planner) has to keep the old price, a bot standing in one of those cells needs its way out
public class ZoneWallCostTest {

    private static final int FLOOR = 63;

    private Settings settings;
    private boolean potions;

    @Before
    public void setUp() {
        ItemFixture.boot();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        // the tool set asks the player about haste otherwise, and there is no player
        potions = settings.considerPotionEffects.value;
        settings.considerPotionEffects.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) settings.considerPotionEffects.value = potions;
        AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().clear();
        AltoClefSettings.getInstance().getBreakAvoiders().clear();
    }

    @Test
    public void aZoneCellOfAirIsAWallAndACloudCellIsNot() {
        VineLogPathTest.MapWorld w = new VineLogPathTest.MapWorld();
        for (int x = -4; x <= 4; x++) {
            w.set(x, FLOOR, 0, Blocks.STONE.defaultBlockState());
        }
        BlockPos zoneCell = new BlockPos(2, FLOOR + 1, 0);
        BlockPos cloudCell = new BlockPos(-2, FLOOR + 1, 0);
        Predicate<BlockPos> zone = zoneCell::equals;
        Predicate<BlockPos> cloud = cloudCell::equals;
        AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().add(zone);
        AltoClefSettings.getInstance().getForceAvoidWalkThroughPredicates().add(cloud);
        AltoClefSettings.getInstance().getBreakAvoiders().add(zone);
        // (the context reads the rules once, when it is made)
        VineLogPathTest.Ctx ctx = new VineLogPathTest.Ctx(w);

        assertEquals(ActionCosts.COST_INF, MovementHelper.getMiningDurationTicks(ctx, zoneCell.getX(), zoneCell.getY(), zoneCell.getZ(), false), 0);
        double cloudCost = MovementHelper.getMiningDurationTicks(ctx, cloudCell.getX(), cloudCell.getY(), cloudCell.getZ(), false);
        assertTrue("cloud cell keeps its mining price: " + cloudCost, cloudCost > 0 && cloudCost < ActionCosts.COST_INF);
        // and a cell nobody walls is plain air, free
        assertEquals(0, MovementHelper.getMiningDurationTicks(ctx, 0, FLOOR + 1, 0, false), 0);
    }
}
