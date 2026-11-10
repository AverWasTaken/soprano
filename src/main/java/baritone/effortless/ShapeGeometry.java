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
import baritone.effortless.ShapeSpec.Fill;
import net.minecraft.core.BlockPos;

import java.util.List;

// clicks in, cells out. plain arithmetic on block coords, no world, so every shape has a test that counts its cells
public final class ShapeGeometry {

    // the box a shape may even think about before it gets counted. a 256^3 box is 16M bits, 2 MB, fine for a moment
    public static final long MAX_BOX_VOLUME = 256L * 256 * 256;

    private ShapeGeometry() {
    }

    public static final class TooBig extends IllegalArgumentException {
        public TooBig(String message) {
            super(message);
        }
    }

    // points are the clicks so far, at least one. a shape that wants more than it got uses what it has (the preview
    // between clicks: the second point is wherever you look)
    public static AreaMask build(ShapeSpec spec, List<BlockPos> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("no points");
        }
        BlockPos a = points.get(0);
        BlockPos b = points.size() > 1 ? points.get(1) : a;
        BlockPos c = points.size() > 2 ? points.get(2) : null;
        return switch (spec.kind()) {
            case SINGLE -> box(a, a, spec, true);
            case LINE -> line(a, b, spec.axis() == Axis.FREE ? spec.withAxis(dominant(a, b, true)) : spec);
            case DIAGONAL_LINE -> line(a, b, spec.withAxis(Axis.FREE));
            case FLOOR -> floor(a, b, spec);
            // the third click is the height, the second only the base. with two clicks the second is both
            case WALL -> wall(a, heightFrom(b, c), spec.axis() == Axis.FREE || spec.axis() == Axis.Y ? spec.withAxis(dominant(a, b, false)) : spec);
            case DIAGONAL_WALL -> wall(a, heightFrom(b, c), spec.withAxis(Axis.FREE));
            case CUBOID -> box(a, c == null ? b : new BlockPos(b.getX(), c.getY(), b.getZ()), spec, false);
            case CIRCLE -> circle(a, b, spec);
            case CYLINDER -> cylinder(a, b, c, spec);
            case SPHERE -> sphere(a, b, spec);
            case STAIRS -> stairs(a, b, spec);
            case PYRAMID -> layered(a, b, spec, false);
            case ROOF -> layered(a, b, spec, true);
        };
    }

    // the axis b is furthest from a along. with vertical off it's X or Z only, a wall stands on the ground
    static Axis dominant(BlockPos a, BlockPos b, boolean vertical) {
        int dx = Math.abs(b.getX() - a.getX());
        int dy = vertical ? Math.abs(b.getY() - a.getY()) : -1;
        int dz = Math.abs(b.getZ() - a.getZ());
        if (dy > dx && dy > dz) {
            return Axis.Y;
        }
        return dx >= dz ? Axis.X : Axis.Z;
    }

    private static BlockPos heightFrom(BlockPos b, BlockPos c) {
        return c == null ? b : new BlockPos(b.getX(), c.getY(), b.getZ());
    }

    private static AreaMask.Builder builder(int x0, int y0, int z0, int x1, int y1, int z1) {
        long volume = AreaMask.Builder.volume(x0, y0, z0, x1, y1, z1);
        if (volume > MAX_BOX_VOLUME) {
            throw new TooBig("that shape's box is " + volume + " blocks, past " + MAX_BOX_VOLUME);
        }
        return new AreaMask.Builder(x0, y0, z0, x1, y1, z1);
    }

    // ----- boxes

    private static AreaMask box(BlockPos a, BlockPos b, ShapeSpec spec, boolean single) {
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        AreaMask.Builder out = builder(x0, y0, z0, x1, y1, z1);
        int t = spec.thickness();
        Fill fill = single ? Fill.FILLED : spec.fill();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    boolean nearX = x - x0 < t || x1 - x < t;
                    boolean nearY = y - y0 < t || y1 - y < t;
                    boolean nearZ = z - z0 < t || z1 - z < t;
                    boolean in = switch (fill) {
                        case FILLED -> true;
                        case HOLLOW -> nearX || nearY || nearZ;
                        case WALLS -> nearX || nearZ;
                        // an edge is where two faces meet
                        case FRAME -> (nearX ? 1 : 0) + (nearY ? 1 : 0) + (nearZ ? 1 : 0) >= 2;
                    };
                    if (in) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    // ----- lines

    // a locked axis keeps the first click's other two coords, so the line can't wander off it whatever you look at
    static BlockPos lockTo(BlockPos a, BlockPos b, Axis axis) {
        return switch (axis) {
            case X -> new BlockPos(b.getX(), a.getY(), a.getZ());
            case Y -> new BlockPos(a.getX(), b.getY(), a.getZ());
            case Z -> new BlockPos(a.getX(), a.getY(), b.getZ());
            case FREE -> b;
        };
    }

    private static AreaMask line(BlockPos a, BlockPos b, ShapeSpec spec) {
        Axis axis = spec.axis();
        BlockPos end = lockTo(a, b, axis);
        int t = spec.thickness();
        int lo = -(t - 1) / 2;
        int hi = t / 2;
        // thickness grows across the line, never along it, so a 3 thick X line is 3x3 in Y and Z
        int ox0 = axis == Axis.X ? 0 : lo, ox1 = axis == Axis.X ? 0 : hi;
        int oy0 = axis == Axis.Y ? 0 : lo, oy1 = axis == Axis.Y ? 0 : hi;
        int oz0 = axis == Axis.Z ? 0 : lo, oz1 = axis == Axis.Z ? 0 : hi;
        AreaMask.Builder out = builder(Math.min(a.getX(), end.getX()) + ox0, Math.min(a.getY(), end.getY()) + oy0,
                Math.min(a.getZ(), end.getZ()) + oz0, Math.max(a.getX(), end.getX()) + ox1,
                Math.max(a.getY(), end.getY()) + oy1, Math.max(a.getZ(), end.getZ()) + oz1);
        int dx = end.getX() - a.getX();
        int dy = end.getY() - a.getY();
        int dz = end.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int x = a.getX() + (int) Math.round(dx * f);
            int y = a.getY() + (int) Math.round(dy * f);
            int z = a.getZ() + (int) Math.round(dz * f);
            for (int ox = ox0; ox <= ox1; ox++) {
                for (int oy = oy0; oy <= oy1; oy++) {
                    for (int oz = oz0; oz <= oz1; oz++) {
                        out.add(x + ox, y + oy, z + oz);
                    }
                }
            }
        }
        return out.build();
    }

    // ----- planes

    private static AreaMask floor(BlockPos a, BlockPos b, ShapeSpec spec) {
        // flat at the first click, whatever height the second one is at
        BlockPos flat = new BlockPos(b.getX(), a.getY(), b.getZ());
        ShapeSpec s = spec.fill() == Fill.FILLED ? spec : spec.withFill(Fill.WALLS);
        return box(a, flat, s, false);
    }

    private static AreaMask wall(BlockPos a, BlockPos b, ShapeSpec spec) {
        Axis axis = spec.axis() == Axis.Y ? Axis.FREE : spec.axis();
        int y0 = Math.min(a.getY(), b.getY());
        int y1 = Math.max(a.getY(), b.getY());
        if (axis != Axis.FREE) {
            // straight along X or Z: a thin box, thickness out towards where the second click was
            BlockPos far = axis == Axis.X ? new BlockPos(b.getX(), b.getY(), a.getZ()) : new BlockPos(a.getX(), b.getY(), b.getZ());
            int t = spec.thickness() - 1;
            int side = axis == Axis.X ? Integer.signum(b.getZ() - a.getZ()) : Integer.signum(b.getX() - a.getX());
            if (side == 0) {
                side = 1;
            }
            BlockPos near = a;
            if (axis == Axis.X) {
                far = new BlockPos(far.getX(), far.getY(), a.getZ() + side * t);
            } else {
                far = new BlockPos(a.getX() + side * t, far.getY(), far.getZ());
            }
            return wallFrame(box(near, far, spec.withFill(Fill.FILLED), false), axis, spec);
        }
        // free: the base runs from click to click across the ground, diagonal if need be
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int t = spec.thickness();
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        AreaMask.Builder out = builder(Math.min(a.getX(), b.getX()) - t, y0, Math.min(a.getZ(), b.getZ()) - t,
                Math.max(a.getX(), b.getX()) + t, y1, Math.max(a.getZ(), b.getZ()) + t);
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int x = a.getX() + (int) Math.round(dx * f);
            int z = a.getZ() + (int) Math.round(dz * f);
            for (int o = 0; o < t; o++) {
                for (int y = y0; y <= y1; y++) {
                    boolean edge = i == 0 || i == steps || y == y0 || y == y1;
                    if (spec.fill() == Fill.FRAME && !edge) {
                        continue;
                    }
                    if (alongX) {
                        out.add(x, y, z + o);
                    } else {
                        out.add(x + o, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    // a straight wall with FRAME keeps only its border, looking at it face on
    private static AreaMask wallFrame(AreaMask wall, Axis axis, ShapeSpec spec) {
        if (spec.fill() != Fill.FRAME || wall.isEmpty()) {
            return wall;
        }
        AreaMask.Builder out = new AreaMask.Builder(wall.minX, wall.minY, wall.minZ, wall.maxX(), wall.maxY(), wall.maxZ());
        wall.forEach((x, y, z) -> {
            boolean endAlong = axis == Axis.X ? (x == wall.minX || x == wall.maxX()) : (z == wall.minZ || z == wall.maxZ());
            if (endAlong || y == wall.minY || y == wall.maxY()) {
                out.add(x, y, z);
            }
        });
        return out.build();
    }

    // ----- round things

    // the two coords that span the plane a circle lies in, for a given axis (the circle's normal)
    private static int u(BlockPos p, Axis axis) {
        return axis == Axis.X ? p.getY() : p.getX();
    }

    private static int v(BlockPos p, Axis axis) {
        return axis == Axis.Z ? p.getY() : p.getZ();
    }

    private static int w(BlockPos p, Axis axis) {
        return switch (axis) {
            case X -> p.getX();
            case Z -> p.getZ();
            default -> p.getY();
        };
    }

    private static BlockPos fromUVW(int u, int v, int w, Axis axis) {
        return switch (axis) {
            case X -> new BlockPos(w, u, v);
            case Z -> new BlockPos(u, v, w);
            default -> new BlockPos(u, w, v);
        };
    }

    // a centre and a point on the rim. +0.5 so a radius 2 circle reaches 2 blocks out along the axes, not 1.5
    static double radius(int du, int dv) {
        return Math.sqrt((double) du * du + (double) dv * dv);
    }

    private static Axis roundAxis(ShapeSpec spec) {
        return spec.axis() == Axis.FREE ? Axis.Y : spec.axis();
    }

    private static AreaMask circle(BlockPos c, BlockPos rim, ShapeSpec spec) {
        Axis axis = roundAxis(spec);
        return disc(c, rim, w(c, axis), w(c, axis), spec, axis, false);
    }

    private static AreaMask cylinder(BlockPos c, BlockPos rim, BlockPos top, ShapeSpec spec) {
        Axis axis = roundAxis(spec);
        int w0 = w(c, axis);
        int w1 = top != null ? w(top, axis) : w(rim, axis);
        return disc(c, rim, Math.min(w0, w1), Math.max(w0, w1), spec, axis, true);
    }

    // a stack of the same circle from w0 to w1 along the axis. a flat circle is a stack of one
    private static AreaMask disc(BlockPos c, BlockPos rim, int w0, int w1, ShapeSpec spec, Axis axis, boolean caps) {
        double r = radius(u(rim, axis) - u(c, axis), v(rim, axis) - v(c, axis)) + 0.5;
        int ri = (int) Math.ceil(r);
        int cu = u(c, axis);
        int cv = v(c, axis);
        BlockPos lo = fromUVW(cu - ri, cv - ri, w0, axis);
        BlockPos hi = fromUVW(cu + ri, cv + ri, w1, axis);
        AreaMask.Builder out = builder(lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ());
        int t = spec.thickness();
        for (int w = w0; w <= w1; w++) {
            boolean cap = caps && (w - w0 < t || w1 - w < t);
            for (int du = -ri; du <= ri; du++) {
                for (int dv = -ri; dv <= ri; dv++) {
                    double d = radius(du, dv);
                    if (d > r) {
                        continue;
                    }
                    boolean rimCell = d > r - t;
                    boolean in = switch (spec.fill()) {
                        case FILLED -> true;
                        // a cylinder's hollow keeps its caps, WALLS is the tube alone. a flat circle has no caps
                        case HOLLOW -> rimCell || cap;
                        case WALLS, FRAME -> rimCell;
                    };
                    if (in) {
                        BlockPos p = fromUVW(cu + du, cv + dv, w, axis);
                        out.add(p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        }
        return out.build();
    }

    private static AreaMask sphere(BlockPos c, BlockPos rim, ShapeSpec spec) {
        int dx = rim.getX() - c.getX();
        int dy = rim.getY() - c.getY();
        int dz = rim.getZ() - c.getZ();
        double r = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) + 0.5;
        int ri = (int) Math.ceil(r);
        int yLo = spec.half() ? 0 : -ri;
        AreaMask.Builder out = builder(c.getX() - ri, c.getY() + yLo, c.getZ() - ri, c.getX() + ri, c.getY() + ri, c.getZ() + ri);
        int t = spec.thickness();
        boolean hollow = spec.fill() != Fill.FILLED;
        for (int x = -ri; x <= ri; x++) {
            for (int y = yLo; y <= ri; y++) {
                for (int z = -ri; z <= ri; z++) {
                    double d = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
                    if (d > r || (hollow && d <= r - t)) {
                        continue;
                    }
                    out.add(c.getX() + x, c.getY() + y, c.getZ() + z);
                }
            }
        }
        return out.build();
    }

    // ----- stairs

    // up (or down) from the first click to the second, one step per block of run along whichever ground axis the
    // clicks are further apart on. the other ground axis is the width. FILLED fills under each step to the low end
    private static AreaMask stairs(BlockPos a, BlockPos b, ShapeSpec spec) {
        boolean runX = Math.abs(b.getX() - a.getX()) >= Math.abs(b.getZ() - a.getZ());
        int run0 = runX ? a.getX() : a.getZ();
        int run1 = runX ? b.getX() : b.getZ();
        int wid0 = Math.min(runX ? a.getZ() : a.getX(), runX ? b.getZ() : b.getX());
        int wid1 = Math.max(runX ? a.getZ() : a.getX(), runX ? b.getZ() : b.getX());
        int n = Math.abs(run1 - run0);
        int sign = Integer.signum(run1 - run0);
        int dy = b.getY() - a.getY();
        int yLow = Math.min(a.getY(), b.getY());
        AreaMask.Builder out = builder(Math.min(a.getX(), b.getX()), yLow, Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        int prevY = a.getY();
        for (int i = 0; i <= n; i++) {
            int y = a.getY() + (n == 0 ? dy : (int) Math.round((double) dy * i / n));
            int r = run0 + sign * i;
            // steep stairs climb more than one block per step, the gap between steps gets filled so it stays walkable
            int from;
            int to;
            if (spec.fill() == Fill.FILLED) {
                from = yLow;
                to = y;
            } else if (y > prevY) {
                from = prevY + 1;
                to = y;
            } else if (y < prevY) {
                from = y;
                to = prevY - 1;
            } else {
                from = y;
                to = y;
            }
            for (int yy = from; yy <= to; yy++) {
                for (int wv = wid0; wv <= wid1; wv++) {
                    if (runX) {
                        out.add(r, yy, wv);
                    } else {
                        out.add(wv, yy, r);
                    }
                }
            }
            prevY = y;
        }
        return out.build();
    }

    // ----- pyramids and roofs

    // layer k sits k blocks up and is k smaller on each side (a roof only shrinks across its short side, so it ends
    // in a ridge). HOLLOW keeps a cell only when nothing of the next layer sits on top of it: the outside of the slope.
    // a roof keeps its gable ends in that case too, or it would be open at both ends
    private static AreaMask layered(BlockPos a, BlockPos b, ShapeSpec spec, boolean roof) {
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        int base = a.getY();
        boolean ridgeAlongX = (x1 - x0) >= (z1 - z0);
        int layers = roof
                ? (ridgeAlongX ? (z1 - z0) / 2 : (x1 - x0) / 2) + 1
                : Math.min(x1 - x0, z1 - z0) / 2 + 1;
        AreaMask.Builder out = builder(x0, base, z0, x1, base + layers - 1, z1);
        boolean hollow = spec.fill() != Fill.FILLED;
        for (int k = 0; k < layers; k++) {
            int lx0 = x0, lx1 = x1, lz0 = z0, lz1 = z1;
            if (!roof || !ridgeAlongX) {
                lx0 += k;
                lx1 -= k;
            }
            if (!roof || ridgeAlongX) {
                lz0 += k;
                lz1 -= k;
            }
            int nx0 = lx0, nx1 = lx1, nz0 = lz0, nz1 = lz1;
            if (!roof || !ridgeAlongX) {
                nx0++;
                nx1--;
            }
            if (!roof || ridgeAlongX) {
                nz0++;
                nz1--;
            }
            boolean top = k == layers - 1;
            for (int x = lx0; x <= lx1; x++) {
                for (int z = lz0; z <= lz1; z++) {
                    if (hollow && !top) {
                        boolean covered = x >= nx0 && x <= nx1 && z >= nz0 && z <= nz1;
                        boolean gable = roof && (ridgeAlongX ? (x == x0 || x == x1) : (z == z0 || z == z1));
                        if (covered && !gable) {
                            continue;
                        }
                    }
                    out.add(x, base + k, z);
                }
            }
        }
        return out.build();
    }
}
