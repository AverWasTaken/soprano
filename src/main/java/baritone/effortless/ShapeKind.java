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

// what the radial offers. clicks is how many points the shape takes, a third one (where there is one) only when the
// height should come from its own click instead of the second corner
public enum ShapeKind {

    SINGLE("Single block", 1, false),
    LINE("Line", 2, false),
    FLOOR("Floor", 2, false),
    WALL("Wall", 2, false),
    CUBOID("Cuboid", 2, true),
    CIRCLE("Circle", 2, false),
    CYLINDER("Cylinder", 2, true),
    SPHERE("Sphere", 2, false),
    STAIRS("Staircase", 2, false),
    PYRAMID("Pyramid", 2, false),
    ROOF("Roof", 2, false);

    public final String title;
    public final int clicks;
    // with effortlessThreeClickHeight on, this one takes a third click for its height
    public final boolean heightClick;

    ShapeKind(String title, int clicks, boolean heightClick) {
        this.title = title;
        this.clicks = clicks;
        this.heightClick = heightClick;
    }

    public int clicks(boolean threeClickHeight) {
        return heightClick && threeClickHeight ? clicks + 1 : clicks;
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
        // a couple of the names people will type anyway
        switch (want) {
            case "block":
            case "one":
                return SINGLE;
            case "box":
            case "cube":
                return CUBOID;
            case "disc":
            case "ring":
                return CIRCLE;
            case "ball":
            case "dome":
                return SPHERE;
            case "stair":
            case "slope":
                return STAIRS;
            default:
                return null;
        }
    }
}
