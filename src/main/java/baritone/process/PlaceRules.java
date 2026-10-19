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

package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// what a block gets to decide for itself once it's down, and what a movement can't put down without aiming. no
// world in here, the tests ask it straight
public final class PlaceRules {

    // anything with one of these comes out however the click was aimed. by name so a modded log is a log too
    private static final Set<String> AIMED = Set.of("facing", "axis", "half", "type", "rotation", "orientation", "face", "hinge");
    // the path thread asks per node, and there's only so many states
    private static final Map<BlockState, Boolean> AIM_PROOF = new ConcurrentHashMap<>();
    private static final Map<BlockState, Boolean> UNDER_FEET = new ConcurrentHashMap<>();

    private PlaceRules() {
    }

    // vanilla works these out from the neighbours on placement and again on every neighbour update, so asking for them
    // is asking for the neighbours. a stair next to a sideways stair is a corner whether the file likes it or not.
    // by block AND property: a fence's north is literally PipeBlock.NORTH, and a vine's north is a face somebody chose
    public static boolean derived(Block block, Property<?> prop) {
        if (block instanceof StairBlock) {
            return prop == StairBlock.SHAPE;
        }
        if (block instanceof CrossCollisionBlock) { // fences, panes, iron bars
            return prop == CrossCollisionBlock.NORTH || prop == CrossCollisionBlock.EAST
                    || prop == CrossCollisionBlock.SOUTH || prop == CrossCollisionBlock.WEST;
        }
        if (block instanceof WallBlock) {
            return prop == WallBlock.NORTH || prop == WallBlock.EAST || prop == WallBlock.SOUTH
                    || prop == WallBlock.WEST || prop == WallBlock.UP;
        }
        if (block instanceof FenceGateBlock) {
            return prop == FenceGateBlock.IN_WALL;
        }
        if (block instanceof RedstoneWireBlock) {
            return prop == RedstoneWireBlock.NORTH || prop == RedstoneWireBlock.EAST
                    || prop == RedstoneWireBlock.SOUTH || prop == RedstoneWireBlock.WEST;
        }
        if (block instanceof SnowyBlock) {
            return prop == SnowyBlock.SNOWY;
        }
        return false;
    }

    // a block that comes out right whichever face a movement happens to click: a full cube with nothing to aim.
    // a vertical log is a full cube too and a side click lays it down, so having the property at all is a no
    public static boolean aimProof(BlockState state) {
        return AIM_PROOF.computeIfAbsent(state, PlaceRules::computeAimProof);
    }

    private static boolean computeAimProof(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (AIMED.contains(prop.getName())) {
                return false;
            }
        }
        VoxelShape shape = collision(state);
        return shape != null && Block.isShapeFullBlock(shape);
    }

    // can this go into the cell we're standing on top of. a pillar clicks it in at the top of the jump, which clears a
    // block or a slab but never a fence post sticking half a block into our feet
    public static boolean fitsUnderFeet(BlockState state) {
        return UNDER_FEET.computeIfAbsent(state, PlaceRules::computeUnderFeet);
    }

    private static boolean computeUnderFeet(BlockState state) {
        VoxelShape shape = collision(state);
        return shape != null && (shape.isEmpty() || shape.max(Direction.Axis.Y) <= 1.0);
    }

    // null when the block wanted a real world to answer, which is a no for both questions
    private static VoxelShape collision(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Exception e) {
            return null;
        }
    }
}
