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

import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// where the next corner goes when you look somewhere, after the first one is down: Effortless Structure's tracing,
// ported as it is because it feels right in the hand. the first corner is the block you click. the second follows your
// look across a plane through it: the floor, a wall, or for "both" whichever of the three your look crosses nearest
// you. the third runs along one line out of that plane from the second corner. plain arithmetic on the eye and the look
// direction, the tests feed it numbers
public final class ClickAim {

    // how far a corner can follow your look. Effortless Structure doesn't cap it below its 1024
    public static final double MAX_DISTANCE = 256;

    // a look straight along an axis (or with a component of exactly 0) would divide by zero
    static final double LOOK_TOLERANCE = 0.01;

    enum Axis {
        X, Y, Z
    }

    private ClickAim() {
    }

    // eye and dir are the camera, dir need not be normalised. points are the corners set so far (at least one).
    // empty when this corner goes under the crosshair (the first one, a diagonal line's end) or the look can't reach a
    // plane: the caller falls back to the block under the crosshair
    public static Optional<BlockPos> next(ShapeSpec spec, List<BlockPos> points, double ex, double ey, double ez,
                                          double dx, double dy, double dz) {
        if (points.isEmpty()) {
            return Optional.empty();
        }
        Look look = Look.of(ex, ey, ez, dx, dy, dz);
        ShapeKind kind = spec.kind();
        BlockPos a = points.get(0);
        if (points.size() == 1) {
            return Optional.ofNullable(switch (kind.second) {
                case LINE -> traceLine(look, a, lineAxes(spec.direction()));
                case WALL -> uniform(a, traceWall(look, a), spec.equal());
                case FLOOR -> uniform(a, traceSquare(look, a, EnumSet.of(Axis.Y)), spec.equal());
                case SQUARE -> uniform(a, traceSquare(look, a, facingAxes(spec.facing())), spec.equal());
                case NONE, CROSSHAIR -> null;
            });
        }
        BlockPos b = points.get(1);
        if (points.size() == 2) {
            return Optional.ofNullable(switch (kind.third) {
                case UP -> traceLine(look, b, EnumSet.of(Axis.Y));
                case OUT_OF_PLANE -> traceLine(look, b, outOfPlane(a, b));
                case NONE -> null;
            });
        }
        return Optional.empty();
    }

    static Set<Axis> lineAxes(ShapeSpec.Direction d) {
        return switch (d) {
            case ALL -> EnumSet.allOf(Axis.class);
            case FLOOR -> EnumSet.of(Axis.X, Axis.Z);
            case X -> EnumSet.of(Axis.X);
            case Y -> EnumSet.of(Axis.Y);
            case Z -> EnumSet.of(Axis.Z);
        };
    }

    static Set<Axis> facingAxes(ShapeSpec.Facing f) {
        return switch (f) {
            case BOTH -> EnumSet.allOf(Axis.class);
            case VERTICAL -> EnumSet.of(Axis.X, Axis.Z);
            case HORIZONTAL -> EnumSet.of(Axis.Y);
        };
    }

    // the line the third corner runs along: the axis the first two corners share. corners that only made a line (or
    // the same block) leave more than one way out, the look picks
    static Set<Axis> outOfPlane(BlockPos a, BlockPos b) {
        EnumSet<Axis> out = EnumSet.noneOf(Axis.class);
        if (a.getX() == b.getX()) {
            out.add(Axis.X);
        }
        if (a.getY() == b.getY()) {
            out.add(Axis.Y);
        }
        if (a.getZ() == b.getZ()) {
            out.add(Axis.Z);
        }
        return out.isEmpty() ? EnumSet.allOf(Axis.class) : out;
    }

    // ----- the look

    // the eye, and the look direction nudged off exact zeros and ones and normalised, like Effortless Structure does
    record Look(double ex, double ey, double ez, double lx, double ly, double lz) {

        static Look of(double ex, double ey, double ez, double dx, double dy, double dz) {
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double x = nudge(dx / len), y = nudge(dy / len), z = nudge(dz / len);
            double l = Math.sqrt(x * x + y * y + z * z);
            return new Look(ex, ey, ez, x / l, y / l, z / l);
        }

        private static double nudge(double v) {
            if (Math.abs(v) < LOOK_TOLERANCE) {
                return LOOK_TOLERANCE;
            }
            if (Math.abs(v - 1) < LOOK_TOLERANCE) {
                return 1 - LOOK_TOLERANCE;
            }
            if (Math.abs(v + 1) < LOOK_TOLERANCE) {
                return LOOK_TOLERANCE - 1;
            }
            return v;
        }

        double eye(Axis a) {
            return a == Axis.X ? ex : a == Axis.Y ? ey : ez;
        }

        double dir(Axis a) {
            return a == Axis.X ? lx : a == Axis.Y ? ly : lz;
        }
    }

    // one plane through the start block, facing along the axis: where the look crosses it, and the bits the choices
    // are made on
    static final class Criteria {
        final Axis axis;
        final double[] centre;
        final double[] start;
        final double[] plane;
        final Look look;

        Criteria(Look look, BlockPos at, Axis axis) {
            this.look = look;
            this.axis = axis;
            this.centre = new double[]{at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5};
            // the start block's face on each axis that's towards the eye (or the way you look, stood inside it): you
            // see faces, the plane goes through the one you see
            this.start = new double[3];
            for (Axis a : Axis.values()) {
                start[a.ordinal()] = Math.round(snap(centre[a.ordinal()], look.eye(a), look.dir(a)));
            }
            int i = axis.ordinal();
            double t = (start[i] - look.eye(axis)) / look.dir(axis);
            this.plane = new double[]{look.ex() + t * look.lx(), look.ey() + t * look.ly(), look.ez() + t * look.lz()};
            plane[i] = start[i];
        }

        private static double snap(double start, double eye, double look) {
            if (eye >= start + 0.5) {
                return start + 0.5;
            }
            if (eye <= start - 0.5) {
                return start - 0.5;
            }
            if (look > 0) {
                return start + 0.5;
            }
            if (look < 0) {
                return start - 0.5;
            }
            return start;
        }

        double toEyeSqr() {
            double x = plane[0] - look.ex(), y = plane[1] - look.ey(), z = plane[2] - look.ez();
            return x * x + y * y + z * z;
        }

        // in front of you and in reach
        boolean inRange() {
            double x = plane[0] - look.ex(), y = plane[1] - look.ey(), z = plane[2] - look.ez();
            double d = toEyeSqr();
            return x * look.lx() + y * look.ly() + z * look.lz() > 0 && d > 0 && d < MAX_DISTANCE * MAX_DISTANCE;
        }

        // the block on the plane: the crossing put back on the start block's own layer
        BlockPos onPlane() {
            double[] p = plane.clone();
            p[axis.ordinal()] = centre[axis.ordinal()];
            return BlockPos.containing(p[0], p[1], p[2]);
        }

        // from the start, the crossing moved onto the axis line it's furthest along of the allowed ones
        double[] lineVec(Set<Axis> allowed) {
            Axis best = null;
            double longest = -1;
            for (Axis a : Axis.values()) {
                if (!allowed.contains(a)) {
                    continue;
                }
                double s = Math.abs(plane[a.ordinal()] - centre[a.ordinal()]);
                if (s > longest) {
                    longest = s;
                    best = a;
                }
            }
            double[] v = centre.clone();
            if (best != null) {
                v[best.ordinal()] = plane[best.ordinal()];
            }
            return v;
        }

        double toLineSqr(Set<Axis> allowed) {
            double[] l = lineVec(allowed);
            double x = plane[0] - l[0], y = plane[1] - l[1], z = plane[2] - l[2];
            return x * x + y * y + z * z;
        }

        // how squarely you look at a wall: the wall's run from the start weighted by the look, along the ground
        double wallAngle() {
            double wx = plane[0] - start[0], wz = plane[2] - start[2];
            return Math.abs(wx * look.lx()) + Math.abs(wz * look.lz());
        }
    }

    // a line from the start along one of the allowed axes: of the three planes through it, the crossing that's
    // nearest its own axis line wins, and the corner is that line's point
    static BlockPos traceLine(Look look, BlockPos at, Set<Axis> allowed) {
        Criteria best = null;
        double bestGap = Double.MAX_VALUE;
        for (Axis a : Axis.values()) {
            Criteria c = new Criteria(look, at, a);
            if (!c.inRange()) {
                continue;
            }
            double gap = c.toLineSqr(allowed);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        if (best == null) {
            return null;
        }
        double[] v = best.lineVec(allowed);
        return BlockPos.containing(v[0], v[1], v[2]);
    }

    // a corner on one of the allowed planes: the one your look crosses nearest your eye
    static BlockPos traceSquare(Look look, BlockPos at, Set<Axis> axes) {
        Criteria best = null;
        for (Axis a : axes) {
            Criteria c = new Criteria(look, at, a);
            if (c.inRange() && (best == null || c.toEyeSqr() < best.toEyeSqr())) {
                best = c;
            }
        }
        return best == null ? null : best.onPlane();
    }

    // a corner on an upright plane: of the two, the one you look at most squarely
    static BlockPos traceWall(Look look, BlockPos at) {
        Criteria best = null;
        for (Axis a : new Axis[]{Axis.X, Axis.Z}) {
            Criteria c = new Criteria(look, at, a);
            if (c.inRange() && (best == null || c.wallAngle() < best.wallAngle())) {
                best = c;
            }
        }
        return best == null ? null : best.onPlane();
    }

    // equal lengths: the two sides of the rectangle made as long as the longer one, each keeping its direction
    static BlockPos uniform(BlockPos a, BlockPos b, boolean equal) {
        if (b == null || !equal) {
            return b;
        }
        int dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ();
        int zeros = (dx == 0 ? 1 : 0) + (dy == 0 ? 1 : 0) + (dz == 0 ? 1 : 0);
        if (zeros != 1) {
            return b;
        }
        if (dx == 0) {
            int m = Math.max(Math.abs(dy), Math.abs(dz));
            return a.offset(0, sign(dy) * m, sign(dz) * m);
        }
        if (dy == 0) {
            int m = Math.max(Math.abs(dx), Math.abs(dz));
            return a.offset(sign(dx) * m, 0, sign(dz) * m);
        }
        int m = Math.max(Math.abs(dx), Math.abs(dy));
        return a.offset(sign(dx) * m, sign(dy) * m, 0);
    }

    private static int sign(int v) {
        return v < 0 ? -1 : 1;
    }
}
