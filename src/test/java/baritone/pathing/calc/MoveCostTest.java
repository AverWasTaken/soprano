/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.calc;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.MutableMoveResult;
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

import static baritone.api.pathing.movement.ActionCosts.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// what the planner charges for a move against how long the move really takes. each test says where the real number
// comes from. same fake world as FallingCostTest, and the same skip when there's no shadow BaritoneAPI around
public class MoveCostTest {

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
            }, false, false, true, 0, 1.0f, 20.0, false);
        }
    }

    private Settings settings;
    private boolean potions;
    private boolean diagonalAscend;

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
        // the fake tool set would go ask a player for haste, and there is no player
        settings.considerPotionEffects.value = false;
        diagonalAscend = settings.allowDiagonalAscend.value;
        settings.allowDiagonalAscend.value = true;
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.considerPotionEffects.value = potions;
            settings.allowDiagonalAscend.value = diagonalAscend;
        }
    }

    // stone floor at 63 everywhere in a 7x7
    private static MapWorld floor() {
        MapWorld w = new MapWorld();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                w.set(x, 63, z, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    // ---- fall time ----

    // the floor next door sits `drop` blocks lower, starting at the usual 63
    private static MapWorld ledge(int drop) {
        MapWorld w = floor();
        w.set(1, 63, 0, Blocks.AIR.defaultBlockState());
        w.set(1, 63 - drop, 0, Blocks.STONE.defaultBlockState());
        return w;
    }

    @Test
    public void fallingOntoGroundPaysForTheRealDrop() {
        // feet go from 64 to 62: two blocks of falling, not the three that the old indexing charged
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(ledge(2)), 0, 64, 0, 1, 0, res);
        assertEquals(62, res.y);
        assertEquals(WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[2], res.cost, 1e-9);
    }

    @Test
    public void fallingIntoWaterPaysForTheWholeDepth() {
        // water is landed in, feet end up at the water cell (61), a drop of three. the old number was already right for this one
        MapWorld w = floor();
        w.set(1, 63, 0, Blocks.AIR.defaultBlockState());
        w.set(1, 60, 0, Blocks.STONE.defaultBlockState());
        w.set(1, 61, 0, Blocks.WATER.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(new Ctx(w), 0, 64, 0, 1, 0, res);
        assertEquals(61, res.y);
        assertEquals(WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[3], res.cost, 1e-9);
    }

    // ---- diagonals ----

    private static final double ROOT_2 = Math.sqrt(2);

    private static double diagonal(MapWorld w) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        return res.cost;
    }

    @Test
    public void edgingAroundACornerIsWalkSpeedOverTheDiagonal() {
        // a block on one corner means the executor walks (Diagonal.sprint refuses when a corner is blocked), so one
        // walked root 2 and not root 2 squared. two sprinted straights are 7.13, the edge has to beat that or we L-walk
        MapWorld w = floor();
        w.set(0, 64, 1, Blocks.STONE.defaultBlockState());
        w.set(0, 65, 1, Blocks.STONE.defaultBlockState());
        double cost = diagonal(w);
        assertEquals(WALK_ONE_BLOCK_COST * ROOT_2, cost, 1e-9);
        assertTrue(cost < 2 * SPRINT_ONE_BLOCK_COST);
    }

    @Test
    public void anOpenDiagonalStillSprints() {
        assertEquals(SPRINT_ONE_BLOCK_COST * ROOT_2, diagonal(floor()), 1e-9);
    }

    @Test
    public void diagonalAscendPaysTheJumpPenalty() {
        MapWorld w = floor();
        w.set(1, 64, 1, Blocks.STONE.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        assertEquals(65, res.y);
        assertEquals(WALK_ONE_BLOCK_COST * ROOT_2 + JUMP_ONE_BLOCK_COST + settings.jumpPenalty.value, res.cost, 1e-9);
    }

    @Test
    public void diagonalAscendOntoABottomSlabIsAWalk() {
        // vanilla steps up half a block by itself, same as MovementAscend. no jump, no penalty
        MapWorld w = floor();
        w.set(1, 64, 1, Blocks.STONE_SLAB.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        assertEquals(65, res.y);
        assertEquals(WALK_ONE_BLOCK_COST * ROOT_2, res.cost, 1e-9);
    }

    @Test
    public void youCantDiagonalAscendOffASlabOntoAFullBlock() {
        MapWorld w = floor();
        w.set(0, 63, 0, Blocks.STONE_SLAB.defaultBlockState());
        w.set(1, 64, 1, Blocks.STONE.defaultBlockState());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(new Ctx(w), 0, 64, 0, 1, 1, res);
        assertEquals(COST_INF, res.cost, 0);
    }

    // ---- the drop heuristic ----

    @Test
    public void theDescentEstimateNeverBeatsAFreeFall() {
        // nobody gets down faster than gravity, so for any fall we plan on this is a lower bound
        for (int n = 1; n <= 24; n++) {
            assertTrue("a " + n + " block drop", GoalYLevel.calculate(0, n) <= FALL_N_BLOCKS_COST[n] + 1e-9);
        }
    }

    @Test
    public void aCliffLooksLikeACliffAndNotAStaircase() {
        // 10 blocks of falling is 17 ticks. the old estimate said 39, which is 4.7 blocks of staircase worth of sprinting
        assertTrue(GoalYLevel.calculate(0, 10) < 14);
        assertEquals(GoalYLevel.calculate(0, 10), GoalBlock.calculate(0, 10, 0), 1e-9);
    }

    @Test
    public void aFallMoveNeverCostsLessThanTheEstimateSaysItShould() {
        // the estimate is added to the flat distance, so it only stays admissible if a fall (1 block out, n down) is
        // worth at least that much on the heuristic. WALK_OFF 3.7 is a hair over the 3.56 a block of flat
        for (int n = 1; n <= 24; n++) {
            double saved = GoalBlock.calculate(1, n, 0) - GoalBlock.calculate(0, 0, 0);
            assertTrue("fall of " + n, saved <= WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[n] + 1e-9);
        }
    }

    @Test
    public void climbingStillLooksAtLeastAsHardAsItIs() {
        // going up isn't changed, one block of it is the jump itself
        assertEquals(JUMP_ONE_BLOCK_COST * 3, GoalYLevel.calculate(8, 5), 1e-9);
    }
}
