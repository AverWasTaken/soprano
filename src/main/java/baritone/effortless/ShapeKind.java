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

// what the radial offers, the same set and the same clicks as Effortless Structure: the first click is the block you
// aim at, the second is a corner on a plane through it (a wall, a floor, or whichever you look at), the third, for the
// solid ones, how far it reaches out of that plane
public enum ShapeKind {

    SINGLE("Single", 1, Second.NONE, Third.NONE),
    // along one axis, the one your look runs closest to (or the one the direction option allows)
    LINE("Line", 2, Second.LINE, Third.NONE),
    WALL("Wall", 2, Second.WALL, Third.NONE),
    FLOOR("Floor", 2, Second.FLOOR, Third.NONE),
    // a face on a plane, then pulled out of it
    CUBE("Cube", 3, Second.SQUARE, Third.OUT_OF_PLANE),
    // corner to corner, wherever the crosshair is
    DIAGONAL_LINE("Diagonal line", 2, Second.CROSSHAIR, Third.NONE),
    // a diagonal on the ground, then raised
    DIAGONAL_WALL("Diagonal wall", 3, Second.FLOOR, Third.UP),
    // a rectangle on the ground, then one edge raised
    SLOPE_FLOOR("Slope floor", 3, Second.FLOOR, Third.UP),
    CIRCLE("Circle", 2, Second.SQUARE, Third.NONE),
    CYLINDER("Cylinder", 3, Second.SQUARE, Third.OUT_OF_PLANE),
    SPHERE("Sphere", 3, Second.SQUARE, Third.OUT_OF_PLANE),
    // the top half of a sphere: the base on the ground, then how high
    DOME("Dome", 3, Second.FLOOR, Third.UP),
    PYRAMID("Pyramid", 3, Second.FLOOR, Third.UP),
    CONE("Cone", 3, Second.FLOOR, Third.UP);

    // how the second corner follows your look
    public enum Second {
        NONE, LINE, WALL, FLOOR, SQUARE, CROSSHAIR
    }

    // how the third does
    public enum Third {
        NONE,
        // straight out of the plane the first two made, from the second corner
        OUT_OF_PLANE,
        // straight up or down from the second corner
        UP
    }

    public final String title;
    public final int clicks;
    public final Second second;
    public final Third third;

    ShapeKind(String title, int clicks, Second second, Third third) {
        this.title = title;
        this.clicks = clicks;
        this.second = second;
        this.third = third;
    }

    public int clicks(ShapeSpec spec) {
        return clicks;
    }

    // has the "start from the middle or a corner" option
    public boolean round() {
        return this == CIRCLE || this == CYLINDER || this == SPHERE || this == DOME || this == CONE;
    }

    // has the facing option (both, vertical, horizontal): the second corner goes on a plane you pick by looking
    public boolean faces() {
        return second == Second.SQUARE;
    }

    // has the variable / equal option: the second corner kept square to the first
    public boolean lengths() {
        return second == Second.WALL || second == Second.FLOOR || second == Second.SQUARE;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ShapeKind byId(String id) {
        String want = id.toLowerCase(Locale.ROOT);
        for (ShapeKind k : values()) {
            if (k.id().equals(want)) {
                return k;
            }
        }
        // the names people will type anyway, and the ones older queues were saved with
        switch (want) {
            case "block":
            case "one":
                return SINGLE;
            case "diagline":
            case "dline":
                return DIAGONAL_LINE;
            case "square":
            case "plane":
            case "rect":
                return FLOOR;
            case "diagwall":
            case "dwall":
            case "diagonal_square":
                return DIAGONAL_WALL;
            case "slope":
            case "ramp":
            case "stairs":
            case "stair":
                return SLOPE_FLOOR;
            case "box":
            case "cuboid":
                return CUBE;
            case "disc":
            case "ring":
                return CIRCLE;
            case "ball":
                return SPHERE;
            default:
                return null;
        }
    }
}
