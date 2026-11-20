/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.effortless;

import java.util.Locale;

// a shape and its options, everything the radial's shape panel sets, named the way Effortless Structure names them.
// immutable, the with* return a copy. each option only means something for the shapes that show it
public record ShapeSpec(ShapeKind kind, Direction direction, Facing facing, Fill fill, boolean centered, boolean equal,
                        boolean longEdge) {

    // which way a line may run: any axis, along the ground only, or one axis
    public enum Direction {
        ALL, FLOOR, X, Y, Z;

        public static Direction byId(String id) {
            try {
                return valueOf(id.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // which planes a cube's, circle's, cylinder's or sphere's second corner may go on: any, the upright ones, the ground
    public enum Facing {
        BOTH, VERTICAL, HORIZONTAL;

        public static Facing byId(String id) {
            return switch (id.toLowerCase(Locale.ROOT)) {
                case "both", "any" -> BOTH;
                case "vertical", "upright" -> VERTICAL;
                case "horizontal", "flat" -> HORIZONTAL;
                default -> null;
            };
        }
    }

    // FILLED, HOLLOW (an outline, a ring, a shell), SKELETON (a cube's edges)
    public enum Fill {
        FILLED, HOLLOW, SKELETON;

        public static Fill byId(String id) {
            switch (id.toLowerCase(Locale.ROOT)) {
                case "outline":
                case "ring":
                case "shell":
                    return HOLLOW;
                case "edges":
                case "frame":
                    return SKELETON;
                default:
                    try {
                        return valueOf(id.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
            }
        }
    }

    public ShapeSpec {
        if (kind == null) {
            throw new IllegalArgumentException("no shape");
        }
        if (direction == null) {
            direction = Direction.ALL;
        }
        if (facing == null) {
            facing = Facing.BOTH;
        }
        if (fill == null) {
            fill = Fill.FILLED;
        }
    }

    // Effortless Structure's defaults: any direction, any plane, filled, from a corner, variable lengths, the short
    // edge raised
    public static ShapeSpec of(ShapeKind kind) {
        return new ShapeSpec(kind, Direction.ALL, Facing.BOTH, Fill.FILLED, false, false, false);
    }

    public ShapeSpec withDirection(Direction d) {
        return new ShapeSpec(kind, d, facing, fill, centered, equal, longEdge);
    }

    public ShapeSpec withFacing(Facing f) {
        return new ShapeSpec(kind, direction, f, fill, centered, equal, longEdge);
    }

    public ShapeSpec withFill(Fill f) {
        return new ShapeSpec(kind, direction, facing, f, centered, equal, longEdge);
    }

    public ShapeSpec withCentered(boolean c) {
        return new ShapeSpec(kind, direction, facing, fill, c, equal, longEdge);
    }

    public ShapeSpec withEqual(boolean e) {
        return new ShapeSpec(kind, direction, facing, fill, centered, e, longEdge);
    }

    public ShapeSpec withLongEdge(boolean l) {
        return new ShapeSpec(kind, direction, facing, fill, centered, equal, l);
    }

    // the options carry over, the shape just ignores the ones it doesn't have. skeleton is a cube's alone
    public ShapeSpec withKind(ShapeKind k) {
        Fill f = fill == Fill.SKELETON && k != ShapeKind.CUBE ? Fill.FILLED : fill;
        return new ShapeSpec(k, direction, facing, f, centered, equal, longEdge);
    }
}
