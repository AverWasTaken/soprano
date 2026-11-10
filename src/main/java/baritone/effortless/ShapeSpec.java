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

// a shape and its options, everything the radial's option strip sets. immutable, the with* return a copy
public record ShapeSpec(ShapeKind kind, Axis axis, Fill fill, int thickness, boolean half) {

    // which way a line runs, which plane a circle lies in, which way a wall faces. FREE follows the clicks
    public enum Axis {
        X, Y, Z, FREE;

        public static Axis byId(String id) {
            try {
                return valueOf(id.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // what part of the shape is in. not every shape uses every one: a floor's HOLLOW is its outline, a circle's is a
    // ring, a cylinder's WALLS is a tube without caps, FRAME is a cuboid's (or a wall's) edges
    public enum Fill {
        FILLED, HOLLOW, WALLS, FRAME;

        public static Fill byId(String id) {
            switch (id.toLowerCase(Locale.ROOT)) {
                case "outline":
                case "ring":
                case "shell":
                    return HOLLOW;
                case "edges":
                    return FRAME;
                default:
                    try {
                        return valueOf(id.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
            }
        }
    }

    public static final int MAX_THICKNESS = 16;

    public ShapeSpec {
        if (kind == null) {
            throw new IllegalArgumentException("no shape");
        }
        if (axis == null) {
            axis = defaultAxis(kind);
        }
        if (fill == null) {
            fill = Fill.FILLED;
        }
        thickness = Math.max(1, Math.min(MAX_THICKNESS, thickness));
    }

    public static ShapeSpec of(ShapeKind kind) {
        return new ShapeSpec(kind, null, null, 1, false);
    }

    // circles and cylinders lie flat unless told otherwise, everything else follows the clicks
    public static Axis defaultAxis(ShapeKind kind) {
        return kind == ShapeKind.CIRCLE || kind == ShapeKind.CYLINDER ? Axis.Y : Axis.FREE;
    }

    public ShapeSpec withAxis(Axis a) {
        return new ShapeSpec(kind, a, fill, thickness, half);
    }

    public ShapeSpec withFill(Fill f) {
        return new ShapeSpec(kind, axis, f, thickness, half);
    }

    public ShapeSpec withThickness(int t) {
        return new ShapeSpec(kind, axis, fill, t, half);
    }

    public ShapeSpec withHalf(boolean h) {
        return new ShapeSpec(kind, axis, fill, thickness, h);
    }

    public ShapeSpec withKind(ShapeKind k) {
        return new ShapeSpec(k, axis, fill, thickness, half);
    }
}
