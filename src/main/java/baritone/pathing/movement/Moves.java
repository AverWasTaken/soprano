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

package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.*;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;

/**
 * An enum of all possible movements attached to all possible directions they could be taken in
 *
 * @author leijurv
 */
public enum Moves {
    DOWNWARD(0, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementDownward(context.getBaritone(), src, src.below());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementDownward.cost(context, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementPillar(context.getBaritone(), src, src.above());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementPillar.cost(context, x, y, z);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.north());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.south());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.east());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.west());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x, src.y + 1, src.z - 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x, src.y + 1, src.z + 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x + 1, src.y + 1, src.z));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x - 1, src.y + 1, src.z));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x - 1, z);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.NORTH, Direction.EAST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z - 1, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.NORTH, Direction.WEST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z - 1, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.SOUTH, Direction.EAST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z + 1, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.SOUTH, Direction.WEST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z + 1, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.WEST, result);
        }
    },

    // 4 is NeoJump.MAX_DIST, which is package private over in movements. neos are always flat, hence dynamicY false
    NEO_NORTH(0, 0, -4, true, false) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementNeo.cost(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementNeo.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    NEO_SOUTH(0, 0, +4, true, false) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementNeo.cost(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementNeo.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    NEO_EAST(+4, 0, 0, true, false) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementNeo.cost(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementNeo.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    NEO_WEST(-4, 0, 0, true, false) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementNeo.cost(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementNeo.cost(context, x, y, z, Direction.WEST, result);
        }
    },

    // 7 is MomentumJump.MAX_DIST, which is package private over in movements. dy is wherever the landing is, so y is dynamic too.
    // the PAD ones land on a block in the middle, jump again, and end at the far landing: one dest per move, so they need their own
    MOMENTUM_NORTH(0, 0, -7, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.NORTH, false);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.NORTH, false, result);
        }
    },

    MOMENTUM_SOUTH(0, 0, +7, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.SOUTH, false);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.SOUTH, false, result);
        }
    },

    MOMENTUM_EAST(+7, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.EAST, false);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.EAST, false, result);
        }
    },

    MOMENTUM_WEST(-7, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.WEST, false);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.WEST, false, result);
        }
    },

    MOMENTUM_PAD_NORTH(0, 0, -7, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.NORTH, true);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.NORTH, true, result);
        }
    },

    MOMENTUM_PAD_SOUTH(0, 0, +7, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.SOUTH, true);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.SOUTH, true, result);
        }
    },

    MOMENTUM_PAD_EAST(+7, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.EAST, true);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.EAST, true, result);
        }
    },

    MOMENTUM_PAD_WEST(-7, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementMomentum.cost(context, src, Direction.WEST, true);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementMomentum.cost(context, x, y, z, Direction.WEST, true, result);
        }
    },

    // 4 is ClimbJump.MAX_DIST, which is package private over in movements. the landing is wherever the sim says it is, so both
    // x z and y are dynamic
    CLIMB_GRAB_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.grab(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.grab(context, x, y, z, Direction.NORTH, result);
        }
    },

    CLIMB_GRAB_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.grab(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.grab(context, x, y, z, Direction.SOUTH, result);
        }
    },

    CLIMB_GRAB_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.grab(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.grab(context, x, y, z, Direction.EAST, result);
        }
    },

    CLIMB_GRAB_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.grab(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.grab(context, x, y, z, Direction.WEST, result);
        }
    },

    CLIMB_LEAP_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.leap(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.leap(context, x, y, z, Direction.NORTH, result);
        }
    },

    CLIMB_LEAP_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.leap(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.leap(context, x, y, z, Direction.SOUTH, result);
        }
    },

    CLIMB_LEAP_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.leap(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.leap(context, x, y, z, Direction.EAST, result);
        }
    },

    CLIMB_LEAP_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimb.leap(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimb.leap(context, x, y, z, Direction.WEST, result);
        }
    },

    // any angle jumps, one bucket of directions and distances each, see TungstenJumps. the offset is a cell in the middle of the
    // bucket, for the loaded chunk check
    TUNGSTEN_NEAR_EAST(+2, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 0, result);
        }
    },

    TUNGSTEN_NEAR_SOUTHEAST(+1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 1, result);
        }
    },

    TUNGSTEN_NEAR_SOUTH(+0, 0, +2, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 2);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 2, result);
        }
    },

    TUNGSTEN_NEAR_SOUTHWEST(-1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 3);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 3, result);
        }
    },

    TUNGSTEN_NEAR_WEST(-2, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 4);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 4, result);
        }
    },

    TUNGSTEN_NEAR_NORTHWEST(-1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 5);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 5, result);
        }
    },

    TUNGSTEN_NEAR_NORTH(+0, 0, -2, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 6);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 6, result);
        }
    },

    TUNGSTEN_NEAR_NORTHEAST(+1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 7);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 7, result);
        }
    },

    TUNGSTEN_FAR_EAST(+4, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 8);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 8, result);
        }
    },

    TUNGSTEN_FAR_SOUTHEAST(+3, 0, +3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 9);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 9, result);
        }
    },

    TUNGSTEN_FAR_SOUTH(+0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 10);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 10, result);
        }
    },

    TUNGSTEN_FAR_SOUTHWEST(-3, 0, +3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 11);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 11, result);
        }
    },

    TUNGSTEN_FAR_WEST(-4, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 12);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 12, result);
        }
    },

    TUNGSTEN_FAR_NORTHWEST(-3, 0, -3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 13);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 13, result);
        }
    },

    TUNGSTEN_FAR_NORTH(+0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 14);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 14, result);
        }
    },

    TUNGSTEN_FAR_NORTHEAST(+3, 0, -3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 15);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 15, result);
        }
    },

    // the same, for jumps two or more blocks down (see TungstenJumps.BANDS)
    TUNGSTEN_DROP_NEAR_EAST(+2, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 16);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 16, result);
        }
    },

    TUNGSTEN_DROP_NEAR_SOUTHEAST(+1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 17);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 17, result);
        }
    },

    TUNGSTEN_DROP_NEAR_SOUTH(+0, 0, +2, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 18);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 18, result);
        }
    },

    TUNGSTEN_DROP_NEAR_SOUTHWEST(-1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 19);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 19, result);
        }
    },

    TUNGSTEN_DROP_NEAR_WEST(-2, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 20);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 20, result);
        }
    },

    TUNGSTEN_DROP_NEAR_NORTHWEST(-1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 21);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 21, result);
        }
    },

    TUNGSTEN_DROP_NEAR_NORTH(+0, 0, -2, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 22);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 22, result);
        }
    },

    TUNGSTEN_DROP_NEAR_NORTHEAST(+1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 23);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 23, result);
        }
    },

    TUNGSTEN_DROP_FAR_EAST(+4, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 24);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 24, result);
        }
    },

    TUNGSTEN_DROP_FAR_SOUTHEAST(+3, 0, +3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 25);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 25, result);
        }
    },

    TUNGSTEN_DROP_FAR_SOUTH(+0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 26);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 26, result);
        }
    },

    TUNGSTEN_DROP_FAR_SOUTHWEST(-3, 0, +3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 27);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 27, result);
        }
    },

    TUNGSTEN_DROP_FAR_WEST(-4, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 28);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 28, result);
        }
    },

    TUNGSTEN_DROP_FAR_NORTHWEST(-3, 0, -3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 29);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 29, result);
        }
    },

    TUNGSTEN_DROP_FAR_NORTH(+0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 30);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 30, result);
        }
    },

    TUNGSTEN_DROP_FAR_NORTHEAST(+3, 0, -3, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 31);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 31, result);
        }
    },

    // next door: a step up onto something the grid moves won't stand on (bars, the top of a ladder, a head), see TungstenJumps.bucket
    TUNGSTEN_STEP_EAST(+1, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 32);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 32, result);
        }
    },

    TUNGSTEN_STEP_SOUTHEAST(+1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 33);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 33, result);
        }
    },

    TUNGSTEN_STEP_SOUTH(+0, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 34);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 34, result);
        }
    },

    TUNGSTEN_STEP_SOUTHWEST(-1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 35);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 35, result);
        }
    },

    TUNGSTEN_STEP_WEST(-1, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 36);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 36, result);
        }
    },

    TUNGSTEN_STEP_NORTHWEST(-1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 37);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 37, result);
        }
    },

    TUNGSTEN_STEP_NORTH(+0, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 38);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 38, result);
        }
    },

    TUNGSTEN_STEP_NORTHEAST(+1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 39);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 39, result);
        }
    },

    TUNGSTEN_STEP_DROP_EAST(+1, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 40);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 40, result);
        }
    },

    TUNGSTEN_STEP_DROP_SOUTHEAST(+1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 41);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 41, result);
        }
    },

    TUNGSTEN_STEP_DROP_SOUTH(+0, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 42);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 42, result);
        }
    },

    TUNGSTEN_STEP_DROP_SOUTHWEST(-1, 0, +1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 43);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 43, result);
        }
    },

    TUNGSTEN_STEP_DROP_WEST(-1, 0, +0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 44);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 44, result);
        }
    },

    TUNGSTEN_STEP_DROP_NORTHWEST(-1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 45);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 45, result);
        }
    },

    TUNGSTEN_STEP_DROP_NORTH(+0, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 46);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 46, result);
        }
    },

    TUNGSTEN_STEP_DROP_NORTHEAST(+1, 0, -1, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementTungsten.cost(context, src, 47);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementTungsten.cost(context, x, y, z, 47, result);
        }
    };

    public final boolean dynamicXZ;
    public final boolean dynamicY;
    // one of the Tungsten jumps (the only moves off a rim node, see TungstenJumps.rimNode)
    public final boolean tungsten = name().startsWith("TUNGSTEN");

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    public abstract Movement apply0(CalculationContext context, BetterBlockPos src);

    public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("Movements with dynamic offset must override `apply`");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(context, x, y, z);
    }

    public double cost(CalculationContext context, int x, int y, int z) {
        throw new UnsupportedOperationException("Movements must override `cost` or `apply`");
    }
}
