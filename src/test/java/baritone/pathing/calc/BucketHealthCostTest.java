package baritone.pathing.calc;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a bucket drop is offered unless a miss would kill. same fake world as GodBridgeCostTest, and the same skip when there's
// no shadow BaritoneAPI around
public class BucketHealthCostTest {

    private static final class MapWorld extends BlockStateInterface {
        final Map<Long, BlockState> blocks = new HashMap<>();

        MapWorld() {
            super(new BetterWorldBorder(new WorldBorder()), -64, 384);
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        @Override
        protected BlockState getUncached(int x, int y, int z) {
            // y comes in shifted by -minY
            return blocks.getOrDefault(BlockPos.asLong(x, y - 64, z), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean isLoaded(int x, int z) {
            return true;
        }

        @Override
        public boolean worldContainsLoadedChunk(int x, int z) {
            return true;
        }
    }

    private static final class Ctx extends CalculationContext {
        Ctx(BlockStateInterface bsi, boolean bucket, double health) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, bucket, true, 0, 1.0f, health, false);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean fast;

    @Before
    public void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            settings = BaritoneAPI.getSettings();
        } catch (Throwable t) {
            Assume.assumeNoException("needs the shadow BaritoneAPI from the bench harness", t);
        }
        potions = settings.considerPotionEffects.value;
        fast = settings.fastMode.value;
        settings.considerPotionEffects.value = false;
        settings.fastMode.value = false;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.fastMode.value = fast;
        }
    }

    // stone floor at 63, a ledge to stand on at (0, 62 + height), and an open column at x=1 to drop down. the drop lands on y=64
    private static MapWorld cliff(int blocksFallen) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        for (int y = 64; y < 64 + blocksFallen; y++) {
            w.set(0, y - 1, 0, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    private static MutableMoveResult drop(int blocksFallen, boolean bucket, double health) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(cliff(blocksFallen), bucket, health), 0, 64 + blocksFallen, 0, 1, 0, res);
        return res;
    }

    @Test
    public void aTwentyBlockBucketDropNeedsToSurviveAMiss() {
        // 20 blocks is 17 damage when the bucket misses
        MutableMoveResult full = drop(20, true, 20);
        assertEquals(64, full.y);
        assertTrue(full.cost < ActionCosts.COST_INF);
        assertTrue("hp 18 survives a miss with 1 left", drop(20, true, 18).cost < ActionCosts.COST_INF);
        assertEquals("hp 17 dies on a miss", ActionCosts.COST_INF, drop(20, true, 17).cost, 0);
    }

    @Test
    public void aShortBucketDropIsOfferedAtAnyHealth() {
        // 10 blocks is 7 damage, 8 hp survives a miss
        assertTrue(drop(10, true, 8).cost < ActionCosts.COST_INF);
        assertEquals(ActionCosts.COST_INF, drop(10, true, 7).cost, 0);
        assertTrue(drop(3, true, 1).cost < ActionCosts.COST_INF);
    }

    @Test
    public void thePricedOutBucketDoesNotTurnIntoAHurtingFallWithoutFastMode() {
        assertEquals(ActionCosts.COST_INF, drop(20, true, 10).cost, 0);
    }

    @Test
    public void midAirKeepsTheBucketWhateverTheHealth() {
        // the fall is already on, the bucket is the only save left. the gate is a planning thing
        CalculationContext low = new Ctx(cliff(20), true, 4);
        MutableMoveResult res = new MutableMoveResult();
        boolean bucket = MovementDescend.dynamicFallCost(low, 0, 84, 0, 1, 0, 0, low.get(1, 82, 0), res, false);
        assertTrue(bucket);
        assertEquals(64, res.y);
        // and the same call as the planner makes it is refused
        res = new MutableMoveResult();
        assertFalse(MovementDescend.dynamicFallCost(low, 0, 84, 0, 1, 0, 0, low.get(1, 82, 0), res, true, true));
    }

    @Test
    public void thePureGateAgreesWithTheFallDamageRule() {
        assertTrue(ExperimentalMovement.canAffordBucketMiss(20, 20));
        assertTrue(ExperimentalMovement.canAffordBucketMiss(18, 20));
        assertFalse(ExperimentalMovement.canAffordBucketMiss(17, 20));
        assertTrue(ExperimentalMovement.canAffordBucketMiss(0.5, 3)); // nothing to miss under the safe distance
        assertTrue(ExperimentalMovement.canAffordBucketMiss(5.5, 7)); // absorption makes for fractional hp
    }
}
