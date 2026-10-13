package baritone.pathing.calc;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
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
import static org.junit.Assert.assertTrue;

// a pond is not priced as a boat ride: BoatTrip wades anything under boatMinWaterLength. same fake world as GodBridgeCostTest,
// and the same skip when there's no shadow BaritoneAPI around
public class BoatRunCostTest {

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
        Ctx(BlockStateInterface bsi) {
            super(null, true, null, null, bsi, new ToolSet(null) {
                @Override
                public double getStrVsBlock(BlockState state) {
                    float hardness = state.getBlock().defaultDestroyTime();
                    return hardness < 0 ? -1 : hardness == 0 ? 1 : 8.0 / (hardness * 30);
                }
            }, false, false, true, 0, 1.0f, 20.0, true);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean boats;
    private int minLength;

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
        boats = settings.allowBoats.value;
        minLength = settings.boatMinWaterLength.value;
        settings.considerPotionEffects.value = false;
        settings.allowBoats.value = true;
        settings.boatMinWaterLength.value = 8;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.allowBoats.value = boats;
            settings.boatMinWaterLength.value = minLength;
        }
    }

    // a basin of water with a stone bank all round, floor at 62, water at 63. the cells a boat fits in are the ones with water on
    // all sides, so lx by lz of them inside water that is 2 bigger each way
    private static MapWorld basin(int lx, int lz) {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= lx + 3; x++) {
            for (int z = -3; z <= lz + 3; z++) {
                w.set(x, 62, z, Blocks.STONE.defaultBlockState());
                boolean water = x >= -1 && x <= lx && z >= -1 && z <= lz;
                w.set(x, 63, z, water ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    @Test
    public void aShortPondIsWadedNotRowed() {
        Ctx probe = new Ctx(basin(7, 7));
        assertTrue("the boat has to be cheaper or this test says nothing", probe.boatWaterSpeed < probe.waterWalkSpeed);
        // 7 boatable cells in a row, one short of the 8 BoatTrip wants
        assertEquals(probe.waterWalkSpeed, probe.waterCost(3, 63, 3), 0);
    }

    @Test
    public void aLongEnoughPondIsRowed() {
        Ctx probe = new Ctx(basin(8, 8));
        assertEquals(probe.boatWaterSpeed, probe.waterCost(3, 63, 3), 0);
        // every cell of the row, the ends of it too
        assertEquals(probe.boatWaterSpeed, probe.waterCost(0, 63, 0), 0);
        assertEquals(probe.boatWaterSpeed, probe.waterCost(7, 63, 7), 0);
    }

    @Test
    public void aNarrowRiverIsWadedWhicheverWayYouLookAtIt() {
        // 5 wide and 12 long: a crossing is 5 blocks of water and BoatTrip would wade it, the long way alone is not enough
        Ctx river = new Ctx(basin(12, 5));
        assertEquals(river.waterWalkSpeed, river.waterCost(6, 63, 2), 0);
        assertEquals(river.waterWalkSpeed, river.waterCost(6, 63, 0), 0);
        // the same river turned on its side
        Ctx turned = new Ctx(basin(5, 12));
        assertEquals(turned.waterWalkSpeed, turned.waterCost(2, 63, 6), 0);
    }

    @Test
    public void theMinimumIsTheSettingNotAConstant() {
        settings.boatMinWaterLength.value = 4;
        Ctx probe = new Ctx(basin(4, 4));
        assertEquals(probe.boatWaterSpeed, probe.waterCost(2, 63, 2), 0);
        settings.boatMinWaterLength.value = 5;
        probe = new Ctx(basin(4, 4));
        assertEquals(probe.waterWalkSpeed, probe.waterCost(2, 63, 2), 0);
    }

    @Test
    public void aCellABoatDoesNotFitInIsNeverRowed() {
        // the water against the bank of a big lake: the stone beside it means no boat, long rows or not
        Ctx probe = new Ctx(basin(20, 20));
        assertEquals(probe.waterWalkSpeed, probe.waterCost(10, 63, -1), 0);
        assertEquals(probe.boatWaterSpeed, probe.waterCost(10, 63, 10), 0);
    }
}
