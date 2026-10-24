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

package baritone.pathing.sim;

// what PlayerSim needs to know about the world, a block at a time. the game one reads real blocks, the tests make a few up
public interface CollisionProvider {

    // appends the collision boxes of one block cell, in world coordinates
    void boxes(int x, int y, int z, BoxList out);

    // the same boxes as an array of 6 doubles each, when the provider keeps them like that and can hand them over without
    // copying (the caller only reads it). null when it doesn't: then ask boxes()
    default double[] boxArray(int x, int y, int z) {
        return null;
    }

    // false for anything the sim does not do the maths for. standing in or on one of these makes the sim give up instead of
    // lying. fluids, climbables, cobwebs, powder snow and so on never are. a provider that also answers the questions below
    // (friction, speed and jump factors, bounce) can call ice, slime and soul sand plain, one that doesn't must not
    boolean plain(int x, int y, int z);

    // Block.getFriction, 0.6 for nearly everything, 0.98 for ice, 0.989 blue ice, 0.8 slime
    default float friction(int x, int y, int z) {
        return 0.6F;
    }

    // Block.getSpeedFactor (0.4 soul sand and honey) and getJumpFactor (0.5 honey)
    default float speedFactor(int x, int y, int z) {
        return 1.0F;
    }

    default float jumpFactor(int x, int y, int z) {
        return 1.0F;
    }

    // Block.getBounceRestitution (1 for slime), 0 where the block is in BlockTags.SUPPRESSES_BOUNCE
    default float bounce(int x, int y, int z) {
        return 0.0F;
    }

    // SlimeBlock.stepOn: walking on it takes most of the sideways speed away every tick
    default boolean slimeStep(int x, int y, int z) {
        return false;
    }

    // in BlockTags.CLIMBABLE (ladders, vines, scaffolding...): standing in one of these, LivingEntity.onClimbable
    default boolean climbable(int x, int y, int z) {
        return false;
    }

    // water in the cell: how high its surface is in the cell (FluidState.getHeight: a source is 8/9, water with water over it
    // a whole block), or -1 for none. a provider that answers this can call water plain (PlayerSim swims), one that doesn't
    // must not
    default float water(int x, int y, int z) {
        return -1F;
    }

    // Entity.getOnPos counts the supporting block itself (not the cell under the feet) for walls and fence gates, and for
    // fences too when the offset is half a block or less: 0 for a plain block, 1 fence, 2 wall or fence gate
    default int supportKind(int x, int y, int z) {
        return 0;
    }
}
