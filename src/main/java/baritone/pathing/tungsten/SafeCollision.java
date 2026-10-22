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

package baritone.pathing.tungsten;

import baritone.pathing.sim.BoxList;
import baritone.pathing.sim.CollisionProvider;
import baritone.pathing.sim.WorldCollision;
import baritone.utils.BlockStateInterface;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;

import java.util.IdentityHashMap;

// WorldCollision, plus the blocks that are fine as far as the physics goes but hurt (or teleport, or stick) when the box
// touches them. those count as not plain, so the sim gives up on any tick that would touch one and the search never plans
// through it. also not plain: anything outside the loaded chunks, where the cache can't tell air from a wall
public final class SafeCollision implements CollisionProvider {

    private final BlockStateInterface bsi;
    private final WorldCollision world;
    private final IdentityHashMap<BlockState, Boolean> plain = new IdentityHashMap<>();

    public SafeCollision(BlockStateInterface bsi) {
        this.bsi = bsi;
        this.world = new WorldCollision(bsi);
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        world.boxes(x, y, z, out);
    }

    // WorldCollision's plain is friction 0.6 and nothing else, for the movements that only know that. this one knows the
    // rest of it (PlayerSim does ice, slime, soul sand with the materials below), so what's left is the stuff the sim really
    // can't do: fluids, scaffolding, webs, powder snow, honey (it slides you down its sides), and anything that hurts
    @Override
    public boolean plain(int x, int y, int z) {
        if (!bsi.isLoaded(x, z)) {
            return false;
        }
        BlockState state = bsi.get0(x, y, z);
        if (state.isAir()) {
            return true;
        }
        return plain.computeIfAbsent(state, SafeCollision::plainState);
    }

    private static boolean plainState(BlockState state) {
        Block block = state.getBlock();
        // climbables are fine now, PlayerSim does ladders and vines (LivingEntity.handleOnClimbable). scaffolding isn't
        return state.getFluidState().isEmpty() && block != Blocks.SCAFFOLDING && block != Blocks.HONEY_BLOCK
                && block != Blocks.BUBBLE_COLUMN && !hazard(state);
    }

    @Override
    public float friction(int x, int y, int z) {
        return world.friction(x, y, z);
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        return world.speedFactor(x, y, z);
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        return world.jumpFactor(x, y, z);
    }

    @Override
    public float bounce(int x, int y, int z) {
        return world.bounce(x, y, z);
    }

    @Override
    public boolean slimeStep(int x, int y, int z) {
        return world.slimeStep(x, y, z);
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        return world.climbable(x, y, z);
    }

    @Override
    public int supportKind(int x, int y, int z) {
        return world.supportKind(x, y, z);
    }

    public static boolean hazard(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block instanceof BaseFireBlock
                || block instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)
                || block == Blocks.WITHER_ROSE
                || block == Blocks.POINTED_DRIPSTONE
                || block == Blocks.END_PORTAL
                || block == Blocks.NETHER_PORTAL
                || block == Blocks.END_GATEWAY
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.COBWEB
                || block == Blocks.POWDER_SNOW
                || block == Blocks.TRIPWIRE;
    }
}
