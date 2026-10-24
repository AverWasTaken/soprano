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

    // everything about a cell, worked out the first time anything about it is asked. a sim asks about the same few dozen cells
    // thousands of times (each tick looks at every cell round the box, the pilot's staging tries a few hundred key presses a
    // tick, a search a few thousand), and a fence's or a wall's shape is a dozen boxes put together from scratch every time.
    // a table indexed straight off the position, a later cell taking an earlier one's slot: no hashing, no boxing, no
    // garbage. one of these lives for one search or one tick of the pilot (see reset), so blocks changing isn't a worry
    private static final int SLOTS = 1 << 12;
    private static final double[] NO_BOXES = new double[0];
    private static final byte PLAIN = 1, SLIME = 2, CLIMB = 4;
    private final long[] keys = new long[SLOTS];
    private final double[][] shapes = new double[SLOTS][];
    private final float[] friction = new float[SLOTS], speed = new float[SLOTS], jump = new float[SLOTS], bounce = new float[SLOTS],
            water = new float[SLOTS];
    private final byte[] flags = new byte[SLOTS], support = new byte[SLOTS];
    private final BoxList cell = new BoxList();

    {
        java.util.Arrays.fill(keys, Long.MIN_VALUE);
    }

    // this one again for the next tick, forgetting every cell, when it's still over the same block cache; a new one otherwise.
    // a tick's worth of cells is a handful of KB, a new table every tick would be ten times that in garbage
    public static SafeCollision forTick(SafeCollision last, BlockStateInterface bsi) {
        if (last == null || last.bsi != bsi) {
            return new SafeCollision(bsi);
        }
        java.util.Arrays.fill(last.keys, Long.MIN_VALUE);
        return last;
    }

    private int slot(int x, int y, int z) {
        long key = net.minecraft.core.BlockPos.asLong(x, y, z); // packed, not hashed: one key is one cell
        int i = (x * 31 + z) * 31 + y & (SLOTS - 1); // neighbours in different slots: a sim looks at a 3x4x3 block at a time
        if (keys[i] != key) {
            fill(i, key, x, y, z);
        }
        return i;
    }

    private void fill(int i, long key, int x, int y, int z) {
        cell.clear();
        world.boxes(x, y, z, cell);
        shapes[i] = cell.size() == 0 ? NO_BOXES : java.util.Arrays.copyOf(cell.raw(), cell.size() * 6);
        friction[i] = world.friction(x, y, z);
        speed[i] = world.speedFactor(x, y, z);
        jump[i] = world.jumpFactor(x, y, z);
        bounce[i] = world.bounce(x, y, z);
        water[i] = world.water(x, y, z);
        support[i] = (byte) world.supportKind(x, y, z);
        flags[i] = (byte) ((plainNow(x, y, z) ? PLAIN : 0) | (world.slimeStep(x, y, z) ? SLIME : 0) | (world.climbable(x, y, z) ? CLIMB : 0));
        keys[i] = key;
    }

    @Override
    public double[] boxArray(int x, int y, int z) {
        return shapes[slot(x, y, z)];
    }

    @Override
    public void boxes(int x, int y, int z, BoxList out) {
        double[] known = shapes[slot(x, y, z)];
        for (int o = 0; o < known.length; o += 6) {
            out.add(known[o], known[o + 1], known[o + 2], known[o + 3], known[o + 4], known[o + 5]);
        }
    }

    @Override
    public boolean plain(int x, int y, int z) {
        return (flags[slot(x, y, z)] & PLAIN) != 0;
    }

    @Override
    public float friction(int x, int y, int z) {
        return friction[slot(x, y, z)];
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        return speed[slot(x, y, z)];
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        return jump[slot(x, y, z)];
    }

    @Override
    public float bounce(int x, int y, int z) {
        return bounce[slot(x, y, z)];
    }

    @Override
    public boolean slimeStep(int x, int y, int z) {
        return (flags[slot(x, y, z)] & SLIME) != 0;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        return (flags[slot(x, y, z)] & CLIMB) != 0;
    }

    @Override
    public int supportKind(int x, int y, int z) {
        return support[slot(x, y, z)];
    }

    @Override
    public float water(int x, int y, int z) {
        return water[slot(x, y, z)];
    }

    // WorldCollision's plain is friction 0.6 and nothing else, for the movements that only know that. this one knows the
    // rest of it (PlayerSim does ice, slime, soul sand with the materials below), so what's left is the stuff the sim really
    // can't do: fluids, scaffolding, webs, powder snow, honey (it slides you down its sides), and anything that hurts
    private boolean plainNow(int x, int y, int z) {
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
        return (state.getFluidState().isEmpty() || WorldCollision.isWaterState(state)) && block != Blocks.SCAFFOLDING && block != Blocks.HONEY_BLOCK
                && block != Blocks.BUBBLE_COLUMN && !hazard(state);
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
