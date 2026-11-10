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

import baritone.effortless.ShapeSpec.Axis;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

// where the next corner goes when you look somewhere, after the first one is down. the first corner is the block you
// click on, the rest follow your look along the shape's own plane, so a floor's far corner can be thirty blocks off
// across a valley and a wall's top is wherever you look up to on the wall. plain arithmetic on the eye and the look
// direction, the tests feed it numbers
public final class ClickAim {

    // how far a corner can follow your look along a plane
    public static final double MAX_DISTANCE = 64;

    private ClickAim() {
    }

    // eye and dir are the camera, dir need not be normalised. points are the corners set so far (at least one).
    // empty when the shape has no plane for this corner, or you're looking away from it: the caller falls back to the
    // block under the crosshair
    public static Optional<BlockPos> next(ShapeSpec spec, List<BlockPos> points, double ex, double ey, double ez,
                                          double dx, double dy, double dz) {
        if (points.isEmpty()) {
            return Optional.empty();
        }
        BlockPos a = points.get(0);
        ShapeKind kind = spec.kind();
        if (points.size() == 2) {
            // the height click: straight up or down from the second corner, where your look crosses it
            return height(points.get(1), ex, ey, ez, dx, dy, dz);
        }
        return switch (kind) {
            case FLOOR, CUBOID, PYRAMID, ROOF, SPHERE, STAIRS -> flat(a, ex, ey, ez, dx, dy, dz);
            case CIRCLE, CYLINDER -> spec.axis() == Axis.Y || spec.axis() == Axis.FREE ? flat(a, ex, ey, ez, dx, dy, dz)
                    : upright(a, spec.axis() == Axis.X ? Axis.Z : Axis.X, ex, ey, ez, dx, dy, dz);
            // a wall's second corner is where it ends on the ground, the height is the third click
            case WALL, DIAGONAL_WALL -> flat(a, ex, ey, ez, dx, dy, dz);
            case LINE -> spec.axis() == Axis.FREE ? nearestAxis(a, ex, ey, ez, dx, dy, dz) : onAxis(a, spec.axis(), ex, ey, ez, dx, dy, dz);
            // a diagonal goes wherever the crosshair is
            case DIAGONAL_LINE, SINGLE -> Optional.empty();
        };
    }

    // across the first corner's layer
    static Optional<BlockPos> flat(BlockPos a, double ex, double ey, double ez, double dx, double dy, double dz) {
        double t = plane(ey, dy, a.getY() + 0.5);
        return at(t, ex, ey, ez, dx, dy, dz);
    }

    // on an upright plane through the first corner: the one along X (z fixed) or along Z (x fixed)
    static Optional<BlockPos> upright(BlockPos a, Axis along, double ex, double ey, double ez, double dx, double dy, double dz) {
        double t = along == Axis.X ? plane(ez, dz, a.getZ() + 0.5) : plane(ex, dx, a.getX() + 0.5);
        return at(t, ex, ey, ez, dx, dy, dz);
    }

    // a straight line on auto: of the three axis lines through the first corner, the one your look passes closest to.
    // look up the column and it's a Y line, along the ground and it's X or Z
    static Optional<BlockPos> nearestAxis(BlockPos a, double ex, double ey, double ez, double dx, double dy, double dz) {
        // standing on (or right beside) the first corner, every axis line runs through your eye and "closest" means
        // nothing: then it's the way you look the most, as far out as you look along it
        double ax = a.getX() + 0.5, ay = a.getY() + 0.5, az = a.getZ() + 0.5;
        double ox = ax - ex, oy = ay - ey, oz = az - ez;
        if (ox * ox + oy * oy + oz * oz < 2.5 * 2.5) {
            return Optional.of(alongLook(a, dx, dy, dz));
        }
        Optional<BlockPos> best = Optional.empty();
        double bestGap = Double.MAX_VALUE;
        for (Axis axis : new Axis[]{Axis.X, Axis.Y, Axis.Z}) {
            Optional<BlockPos> p = onAxis(a, axis, ex, ey, ez, dx, dy, dz);
            if (p.isEmpty()) {
                continue;
            }
            double gap = rayGap(p.get(), ex, ey, ez, dx, dy, dz);
            if (gap < bestGap) {
                bestGap = gap;
                best = p;
            }
        }
        return best;
    }

    // how many blocks a line from your feet runs when you look along it: the reach, scaled by how squarely you look
    static final double ALONG_REACH = 8;

    static BlockPos alongLook(BlockPos a, double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double nx = dx / len, ny = dy / len, nz = dz / len;
        if (Math.abs(ny) >= Math.abs(nx) && Math.abs(ny) >= Math.abs(nz)) {
            return a.offset(0, (int) Math.round(ny * ALONG_REACH), 0);
        }
        if (Math.abs(nx) >= Math.abs(nz)) {
            return a.offset((int) Math.round(nx * ALONG_REACH), 0, 0);
        }
        return a.offset(0, 0, (int) Math.round(nz * ALONG_REACH));
    }

    // how far the cell's centre is from the look ray
    static double rayGap(BlockPos p, double ex, double ey, double ez, double dx, double dy, double dz) {
        double px = p.getX() + 0.5 - ex, py = p.getY() + 0.5 - ey, pz = p.getZ() + 0.5 - ez;
        double dd = dx * dx + dy * dy + dz * dz;
        double t = Math.max(0, (px * dx + py * dy + pz * dz) / dd);
        double qx = px - t * dx, qy = py - t * dy, qz = pz - t * dz;
        return Math.sqrt(qx * qx + qy * qy + qz * qz);
    }

    // a line locked to an axis: the point on the axis line nearest your look
    static Optional<BlockPos> onAxis(BlockPos a, Axis axis, double ex, double ey, double ez, double dx, double dy, double dz) {
        double ax = a.getX() + 0.5, ay = a.getY() + 0.5, az = a.getZ() + 0.5;
        double ux = axis == Axis.X ? 1 : 0, uy = axis == Axis.Y ? 1 : 0, uz = axis == Axis.Z ? 1 : 0;
        // closest points between the line a + s*u and the ray e + t*d
        double wx = ax - ex, wy = ay - ey, wz = az - ez;
        double b = ux * dx + uy * dy + uz * dz;
        double dd = dx * dx + dy * dy + dz * dz;
        double denom = dd - b * b;
        if (Math.abs(denom) < 1e-9) {
            return Optional.empty();
        }
        double d1 = ux * wx + uy * wy + uz * wz;
        double d2 = dx * wx + dy * wy + dz * wz;
        double s = (b * d2 - dd * d1) / denom;
        if (Math.abs(s) > MAX_DISTANCE) {
            return Optional.empty();
        }
        return Optional.of(BlockPos.containing(ax + s * ux, ay + s * uy, az + s * uz));
    }

    // straight up or down from b: where the look crosses the upright plane through b facing you
    static Optional<BlockPos> height(BlockPos b, double ex, double ey, double ez, double dx, double dy, double dz) {
        double t = Math.abs(dx) >= Math.abs(dz) ? plane(ex, dx, b.getX() + 0.5) : plane(ez, dz, b.getZ() + 0.5);
        if (Double.isNaN(t)) {
            return Optional.empty();
        }
        double y = ey + t * dy;
        if (Math.abs(y - b.getY()) > MAX_DISTANCE) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(b.getX(), (int) Math.floor(y), b.getZ()));
    }

    // t where e + t*d crosses the plane coordinate = c, NaN when it never does in front of you or too far out
    static double plane(double e, double d, double c) {
        if (Math.abs(d) < 1e-9) {
            return Double.NaN;
        }
        double t = (c - e) / d;
        return t > 0 ? t : Double.NaN;
    }

    private static Optional<BlockPos> at(double t, double ex, double ey, double ez, double dx, double dy, double dz) {
        if (Double.isNaN(t)) {
            return Optional.empty();
        }
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz) * t;
        if (len > MAX_DISTANCE) {
            return Optional.empty();
        }
        return Optional.of(BlockPos.containing(ex + t * dx, ey + t * dy, ez + t * dz));
    }
}
