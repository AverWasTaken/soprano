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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// what a block gets to decide for itself once it's down, and what a movement can't put down without aiming. no
// world in here, the tests ask it straight
public final class PlaceRules {

    // anything with one of these comes out however the click was aimed. by name so a modded log is a log too. "type"
    // is a slab's top or bottom, which half of the face we clicked
    private static final Set<String> AIMED = Set.of("facing", "axis", "half", "type", "rotation", "orientation", "face", "hinge");
    // the path thread asks per node, and there's only so many states
    private static final Map<BlockState, Boolean> AIM_PROOF = new ConcurrentHashMap<>();
    // per block class, there's a lot fewer of those than states
    private static final Map<Class<?>, Boolean> USABLE = new ConcurrentHashMap<>();
    // the two ways a block gets a say in a right click, by name and signature
    private static final String USE_WITHOUT_ITEM = "useWithoutItem";
    private static final Class<?>[] USE_WITHOUT_ITEM_ARGS = {BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class};
    private static final String USE_ITEM_ON = "useItemOn";
    private static final Class<?>[] USE_ITEM_ON_ARGS = {ItemStack.class, BlockState.class, Level.class, BlockPos.class, Player.class, InteractionHand.class, BlockHitResult.class};
    // if the names ever stop matching (remapped, renamed) every lookup would say "doesn't override" and we'd click chests
    // open unsneaked. so if the base class doesn't have them either, everything counts as usable
    private static final boolean USE_NAMES_OK = declares(BlockBehaviour.class, USE_WITHOUT_ITEM, USE_WITHOUT_ITEM_ARGS)
            && declares(BlockBehaviour.class, USE_ITEM_ON, USE_ITEM_ON_ARGS);

    private PlaceRules() {
    }

    // vanilla works these out from the neighbours on placement and again on every neighbour update, so asking for them
    // is asking for the neighbours. a stair next to a sideways stair is a corner whether the file likes it or not.
    // by block AND property: a fence's north is literally PipeBlock.NORTH, and a vine's north is a face somebody chose
    public static boolean derived(Block block, Property<?> prop) {
        if (prop == BlockStateProperties.WATERLOGGED) {
            // the builder doesn't pour into blocks (it sneaks, the bucket goes one out), so a wet stair the file wants
            // would be a break and place loop forever. dry or wet, a stair is a stair
            return true;
        }
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
            // open is whoever walked through last, us included (traverse opens gates on the way). same for doors and
            // trapdoors below, and vanilla places all three open if there's power, so the file can't win that one anyway
            return prop == FenceGateBlock.IN_WALL || prop == FenceGateBlock.OPEN;
        }
        if (block instanceof RedstoneWireBlock) {
            return prop == RedstoneWireBlock.NORTH || prop == RedstoneWireBlock.EAST
                    || prop == RedstoneWireBlock.SOUTH || prop == RedstoneWireBlock.WEST
                    || prop == RedstoneWireBlock.POWER;
        }
        if (block instanceof LeavesBlock) {
            // distance is the log count, persistent is "a player put this here", which we are
            return prop == LeavesBlock.DISTANCE || prop == LeavesBlock.PERSISTENT;
        }
        if (block instanceof NoteBlock) {
            return prop == NoteBlock.INSTRUMENT; // whatever's under it
        }
        if (block instanceof DoorBlock) {
            return prop == DoorBlock.POWERED || prop == DoorBlock.OPEN;
        }
        if (block instanceof TrapDoorBlock) {
            return prop == TrapDoorBlock.POWERED || prop == TrapDoorBlock.OPEN;
        }
        if (block instanceof SnowyBlock) {
            return prop == SnowyBlock.SNOWY;
        }
        return false;
    }

    // a right click on this does something with a block in hand (opens, flips, eats, ties a lead), so it only turns into a
    // placement sneaking. on purpose by "the class has its own use method" and not a list: a fence takes a lead and gets
    // a sneak it didn't need, but a list that forgot one clicks a chest open and stares into it
    public static boolean usable(BlockState state) {
        if (state.hasBlockEntity()) {
            return true; // same call MovementHelper.opensWhenClicked makes, signs and beds and friends
        }
        return !USE_NAMES_OK || USABLE.computeIfAbsent(state.getBlock().getClass(), PlaceRules::overridesUse);
    }

    private static boolean overridesUse(Class<?> type) {
        for (Class<?> c = type; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
            if (declares(c, USE_WITHOUT_ITEM, USE_WITHOUT_ITEM_ARGS) || declares(c, USE_ITEM_ON, USE_ITEM_ON_ARGS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declares(Class<?> c, String name, Class<?>[] args) {
        try {
            c.getDeclaredMethod(name, args);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (RuntimeException | LinkageError e) {
            return true; // couldn't tell, so it's usable. a sneak too many is just a crouch
        }
    }

    // the placement itself asks whether we're sneaking: a chest joins a neighbour or doesn't, a candle goes into the one
    // we clicked or next to it, scaffolding stacks or goes where we aimed. for these the planner tries both
    public static boolean readsSneak(Block block) {
        return block instanceof ChestBlock || block instanceof CeilingHangingSignBlock || block instanceof SpeleothemBlock
                || block instanceof CandleBlock || block instanceof SeaPickleBlock || block instanceof SegmentableBlock
                || block instanceof TurtleEggBlock || block instanceof ScaffoldingBlock;
    }

    // a block that comes out right whichever face a movement happens to click: a full cube with nothing to aim.
    // a vertical log is a full cube too and a side click lays it down, so having the property at all is a no
    public static boolean aimProof(BlockState state) {
        return AIM_PROOF.computeIfAbsent(state, PlaceRules::computeAimProof);
    }

    // has something that comes off which way the click was aimed. a fence or a wall is not a full cube, but nothing on it
    // depends on where we were looking (the sides are derived). a slab does, its type is which half we clicked
    public static boolean aimed(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (AIMED.contains(prop.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean computeAimProof(BlockState state) {
        if (aimed(state)) {
            return false;
        }
        VoxelShape shape = collision(state);
        return shape != null && Block.isShapeFullBlock(shape);
    }

    // null when the block wanted a real world to answer, which is a no
    private static VoxelShape collision(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Exception e) {
            return null;
        }
    }
}
