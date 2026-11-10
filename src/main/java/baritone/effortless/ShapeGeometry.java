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

import baritone.effortless.ShapeSpec.Fill;
import net.minecraft.core.BlockPos;

import java.util.List;

// clicks in, cells out, the way Effortless Structure works them out: a box's corners, a circle that fits between the
// first two clicks (or around the first), a pyramid that shrinks a whole number of blocks a layer. plain arithmetic on
// block coords, no world, so every shape has a test that counts its cells. with fewer clicks than the shape takes it's
// what that stage shows: the face a cube will be pulled out of, the circle a sphere starts from
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

    private interface Inside {
        boolean at(int x, int y, int z);
    }

    private interface Cell {
        void accept(int x, int y, int z);
    }

    // the plane two corners lie in, by the coordinate they share (Effortless Structure's trace shape)
    enum Plane {
        SINGLE, LINE, X, Y, Z, BOX
    }

    static Plane plane(BlockPos a, BlockPos b) {
        boolean sx = a.getX() == b.getX(), sy = a.getY() == b.getY(), sz = a.getZ() == b.getZ();
        if (sx && sy && sz) {
            return Plane.SINGLE;
        }
        if ((sx && sz) || (sy && sz) || (sx && sy)) {
            return Plane.LINE;
        }
        if (sx) {
            return Plane.X;
        }
        if (sy) {
            return Plane.Y;
        }
        return sz ? Plane.Z : Plane.BOX;
    }

    public static AreaMask build(ShapeSpec spec, List<BlockPos> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("no points");
        }
        BlockPos a = points.get(0);
        if (points.size() == 1 || spec.kind() == ShapeKind.SINGLE) {
            return box(a, a, Fill.FILLED);
        }
        BlockPos b = points.get(1);
        boolean hollow = spec.fill() != Fill.FILLED;
        if (points.size() == 2) {
            return switch (spec.kind()) {
                case LINE, DIAGONAL_LINE, DIAGONAL_WALL -> line(a, b);
                case WALL, FLOOR, PYRAMID, SLOPE_FLOOR -> rect(a, b, spec.kind() != ShapeKind.SLOPE_FLOOR && hollow);
                case CUBE -> rect(a, b, spec.fill() == Fill.SKELETON);
                case CIRCLE, CYLINDER, SPHERE -> circle(a, b, spec.centered(), hollow);
                case DOME, CONE -> circle(a, b, spec.centered(), false);
                default -> box(a, a, Fill.FILLED);
            };
        }
        BlockPos c = points.get(2);
        return switch (spec.kind()) {
            case CUBE -> box(a, c, spec.fill());
            case CYLINDER -> cylinder(a, b, c, spec.centered(), hollow);
            case SPHERE -> sphere(a, b, c, spec.centered(), hollow);
            case DOME -> dome(a, b, c, spec.centered(), hollow);
            case DIAGONAL_WALL -> diagonalWall(a, b, c);
            case SLOPE_FLOOR -> slopeFloor(a, b, c, spec.longEdge());
            case PYRAMID -> pyramid(a, b, c, hollow);
            case CONE -> cone(a, b, c, spec.centered());
            // a two click shape handed a stray third point
            default -> build(spec, points.subList(0, 2));
        };
    }

    private static AreaMask.Builder builder(int x0, int y0, int z0, int x1, int y1, int z1) {
        long volume = AreaMask.Builder.volume(x0, y0, z0, x1, y1, z1);
        if (volume > MAX_BOX_VOLUME) {
            throw new TooBig("that shape's box is " + volume + " blocks, past " + MAX_BOX_VOLUME);
        }
        return new AreaMask.Builder(x0, y0, z0, x1, y1, z1);
    }

    private static AreaMask.Builder builder(int[] k) {
        return builder(k[0], k[1], k[2], k[3], k[4], k[5]);
    }

    private static int[] bounds(BlockPos... ps) {
        int[] k = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BlockPos p : ps) {
            k[0] = Math.min(k[0], p.getX());
            k[1] = Math.min(k[1], p.getY());
            k[2] = Math.min(k[2], p.getZ());
            k[3] = Math.max(k[3], p.getX());
            k[4] = Math.max(k[4], p.getY());
            k[5] = Math.max(k[5], p.getZ());
        }
        return k;
    }

    // ----- boxes, rectangles and lines

    private static AreaMask box(BlockPos a, BlockPos b, Fill fill) {
        int[] k = bounds(a, b);
        AreaMask.Builder out = builder(k);
        for (int x = k[0]; x <= k[3]; x++) {
            for (int y = k[1]; y <= k[4]; y++) {
                for (int z = k[2]; z <= k[5]; z++) {
                    int faces = (x == k[0] || x == k[3] ? 1 : 0) + (y == k[1] || y == k[4] ? 1 : 0) + (z == k[2] || z == k[5] ? 1 : 0);
                    boolean in = switch (fill) {
                        case FILLED -> true;
                        case HOLLOW -> faces >= 1;
                        // an edge is where two faces meet
                        case SKELETON -> faces >= 2;
                    };
                    if (in) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    // a rectangle between two corners on a plane, or its outline. corners that only line up make a line
    private static AreaMask rect(BlockPos a, BlockPos b, boolean outline) {
        Plane p = plane(a, b);
        int[] k = bounds(a, b);
        AreaMask.Builder out = builder(k);
        for (int x = k[0]; x <= k[3]; x++) {
            for (int y = k[1]; y <= k[4]; y++) {
                for (int z = k[2]; z <= k[5]; z++) {
                    boolean edge = switch (p) {
                        case X -> y == k[1] || y == k[4] || z == k[2] || z == k[5];
                        case Z -> x == k[0] || x == k[3] || y == k[1] || y == k[4];
                        case Y -> x == k[0] || x == k[3] || z == k[2] || z == k[5];
                        default -> true;
                    };
                    if (!outline || edge) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    private static AreaMask line(BlockPos a, BlockPos b) {
        AreaMask.Builder out = builder(bounds(a, b));
        walk(a, b, out::add);
        return out.build();
    }

    // every cell from a to b, one step at a time along the axis they're furthest apart on, the others rounded
    private static void walk(BlockPos a, BlockPos b, Cell cell) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            cell.accept(a.getX() + (int) Math.round(dx * f), a.getY() + (int) Math.round(dy * f), a.getZ() + (int) Math.round(dz * f));
        }
    }

    // ----- diagonal walls and slopes

    // the diagonal from the first click to the second, copied up (or down) to the third's height
    private static AreaMask diagonalWall(BlockPos a, BlockPos b, BlockPos c) {
        int y0 = Math.min(a.getY(), c.getY()), y1 = Math.max(a.getY(), c.getY());
        int[] k = bounds(a, b);
        AreaMask.Builder out = builder(k[0], y0, k[2], k[3], y1, k[5]);
        walk(a, b, (x, y, z) -> {
            for (int yy = y0; yy <= y1; yy++) {
                out.add(x, yy, z);
            }
        });
        return out.build();
    }

    // the rectangle on the ground between the first two clicks, one edge left on the ground and the opposite one raised
    // to the third click's height. which pair of edges: the short ones (the slope climbs the long way) or the long
    private static AreaMask slopeFloor(BlockPos a, BlockPos b, BlockPos c, boolean longEdge) {
        int xLen = Math.abs(b.getX() - a.getX());
        int zLen = Math.abs(b.getZ() - a.getZ());
        boolean alongX = longEdge ? zLen > xLen : zLen <= xLen;
        int y0 = Math.min(a.getY(), c.getY()), y1 = Math.max(a.getY(), c.getY());
        int[] k = bounds(a, b);
        AreaMask.Builder out = builder(k[0], y0, k[2], k[3], y1, k[5]);
        if (alongX) {
            BlockPos end = new BlockPos(b.getX(), c.getY(), a.getZ());
            walk(a, end, (x, y, z) -> {
                for (int zz = k[2]; zz <= k[5]; zz++) {
                    out.add(x, y, zz);
                }
            });
        } else {
            BlockPos end = new BlockPos(a.getX(), c.getY(), b.getZ());
            walk(a, end, (x, y, z) -> {
                for (int xx = k[0]; xx <= k[3]; xx++) {
                    out.add(xx, y, z);
                }
            });
        }
        return out.build();
    }

    // ----- round things

    // Effortless Structure's ellipse test: inside when the distance over the radius (plus half a block, so the outer
    // cells count) is under one. hollow keeps a cell when a step outwards along either axis leaves the ellipse
    static boolean inEllipse(double cu, double cv, double ru, double rv, int u, int v, boolean fill) {
        ru += 0.5;
        rv += 0.5;
        double un = Math.abs(u - cu) / ru;
        double vn = Math.abs(v - cv) / rv;
        if (un * un + vn * vn >= 1) {
            return false;
        }
        if (fill) {
            return true;
        }
        double un1 = (Math.abs(u - cu) + 1) / ru;
        double vn1 = (Math.abs(v - cv) + 1) / rv;
        return !(un1 * un1 + vn * vn <= 1 && un * un + vn1 * vn1 <= 1);
    }

    static boolean inEllipsoid(double cx, double cy, double cz, double rx, double ry, double rz, int x, int y, int z, boolean fill) {
        rx += 0.5;
        ry += 0.5;
        rz += 0.5;
        double xn = Math.abs(x - cx) / rx, yn = Math.abs(y - cy) / ry, zn = Math.abs(z - cz) / rz;
        if (xn * xn + yn * yn + zn * zn >= 1) {
            return false;
        }
        if (fill) {
            return true;
        }
        double xn1 = (Math.abs(x - cx) + 1) / rx, yn1 = (Math.abs(y - cy) + 1) / ry, zn1 = (Math.abs(z - cz) + 1) / rz;
        return !(xn1 * xn1 + yn * yn + zn * zn <= 1 && xn * xn + yn1 * yn1 + zn * zn <= 1 && xn * xn + yn * yn + zn1 * zn1 <= 1);
    }

    // the ellipse's centre and the far corner of its box, per axis. from the middle the first click is the centre and
    // the second the rim, mirrored to the other side; from a corner it fits between the two
    private static double centre(int a, int b, boolean centered) {
        return centered ? a : a + (b - a) / 2.0;
    }

    private static int near(int a, int b, boolean centered) {
        return centered ? 2 * a - b : a;
    }

    // a disc (or ring) on the plane the first two clicks share. clicks that only line up make a line
    private static AreaMask circle(BlockPos a, BlockPos b, boolean centered, boolean hollow) {
        Plane p = plane(a, b);
        if (p == Plane.SINGLE || p == Plane.LINE || p == Plane.BOX) {
            return line(a, b);
        }
        BlockPos from = new BlockPos(near(a.getX(), b.getX(), centered), near(a.getY(), b.getY(), centered), near(a.getZ(), b.getZ(), centered));
        int[] k = bounds(from, b);
        AreaMask.Builder out = builder(k);
        double cx = centre(a.getX(), b.getX(), centered), cy = centre(a.getY(), b.getY(), centered), cz = centre(a.getZ(), b.getZ(), centered);
        double rx = Math.abs(b.getX() - cx), ry = Math.abs(b.getY() - cy), rz = Math.abs(b.getZ() - cz);
        for (int x = k[0]; x <= k[3]; x++) {
            for (int y = k[1]; y <= k[4]; y++) {
                for (int z = k[2]; z <= k[5]; z++) {
                    boolean in = switch (p) {
                        case X -> inEllipse(cy, cz, ry, rz, y, z, !hollow);
                        case Y -> inEllipse(cx, cz, rx, rz, x, z, !hollow);
                        default -> inEllipse(cy, cx, ry, rx, y, x, !hollow);
                    };
                    if (in) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    // the circle from the first two clicks, stacked out of its plane to the third click
    private static AreaMask cylinder(BlockPos a, BlockPos b, BlockPos c, boolean centered, boolean hollow) {
        Plane p = plane(a, b);
        AreaMask disc = circle(a, b, centered, hollow);
        if (p != Plane.X && p != Plane.Y && p != Plane.Z) {
            return disc;
        }
        int lo = switch (p) {
            case X -> Math.min(a.getX(), c.getX());
            case Y -> Math.min(a.getY(), c.getY());
            default -> Math.min(a.getZ(), c.getZ());
        };
        int hi = switch (p) {
            case X -> Math.max(a.getX(), c.getX());
            case Y -> Math.max(a.getY(), c.getY());
            default -> Math.max(a.getZ(), c.getZ());
        };
        AreaMask.Builder out = switch (p) {
            case X -> builder(lo, disc.minY, disc.minZ, hi, disc.minY + disc.sizeY - 1, disc.minZ + disc.sizeZ - 1);
            case Y -> builder(disc.minX, lo, disc.minZ, disc.minX + disc.sizeX - 1, hi, disc.minZ + disc.sizeZ - 1);
            default -> builder(disc.minX, disc.minY, lo, disc.minX + disc.sizeX - 1, disc.minY + disc.sizeY - 1, hi);
        };
        disc.forEach((x, y, z) -> {
            for (int l = lo; l <= hi; l++) {
                switch (p) {
                    case X -> out.add(l, y, z);
                    case Y -> out.add(x, l, z);
                    default -> out.add(x, y, l);
                }
            }
        });
        return out.build();
    }

    // the ball that fits the box the three clicks make. from the middle the first click's circle is mirrored round it;
    // out of the plane it reaches from the first click to the third
    private static AreaMask sphere(BlockPos a, BlockPos b, BlockPos c, boolean centered, boolean hollow) {
        BlockPos from = new BlockPos(near(a.getX(), b.getX(), centered), near(a.getY(), b.getY(), centered), near(a.getZ(), b.getZ(), centered));
        int[] k = bounds(from, b, c);
        double cx = (k[0] + k[3]) / 2.0, cy = (k[1] + k[4]) / 2.0, cz = (k[2] + k[5]) / 2.0;
        double rx = (k[3] - k[0]) / 2.0, ry = (k[4] - k[1]) / 2.0, rz = (k[5] - k[2]) / 2.0;
        AreaMask.Builder out = builder(k);
        for (int x = k[0]; x <= k[3]; x++) {
            for (int y = k[1]; y <= k[4]; y++) {
                for (int z = k[2]; z <= k[5]; z++) {
                    if (inEllipsoid(cx, cy, cz, rx, ry, rz, x, y, z, !hollow)) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    // the base circle on the ground, the top half of a ball over it as high as the third click (under it if the third
    // click is lower)
    private static AreaMask dome(BlockPos a, BlockPos b, BlockPos c, boolean centered, boolean hollow) {
        BlockPos from = new BlockPos(near(a.getX(), b.getX(), centered), a.getY(), near(a.getZ(), b.getZ(), centered));
        int[] k = bounds(from, b);
        double cx = (k[0] + k[3]) / 2.0, cz = (k[2] + k[5]) / 2.0;
        double rx = (k[3] - k[0]) / 2.0, rz = (k[5] - k[2]) / 2.0;
        int h = c.getY() - a.getY();
        double ry = Math.abs(h);
        int y0 = Math.min(a.getY(), c.getY()), y1 = Math.max(a.getY(), c.getY());
        AreaMask.Builder out = builder(k[0], y0, k[2], k[3], y1, k[5]);
        for (int x = k[0]; x <= k[3]; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = k[2]; z <= k[5]; z++) {
                    if (inEllipsoid(cx, a.getY(), cz, rx, ry, rz, x, y, z, !hollow)) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.build();
    }

    // ----- pyramids and cones

    // the base between the first two clicks, each layer towards the third click's height in by a whole number of blocks,
    // Effortless Structure's integer maths so the steps come out the same
    private static AreaMask pyramid(BlockPos a, BlockPos b, BlockPos c, boolean hollow) {
        return tapered(a, b, c, (cx, cz, rx, rz, x, z) -> Math.abs(x - cx) <= rx && Math.abs(z - cz) <= rz, hollow);
    }

    // from the middle the base circle is mirrored round the first click, like the circle it starts as
    private static AreaMask cone(BlockPos a, BlockPos b, BlockPos c, boolean centered) {
        BlockPos from = centered ? new BlockPos(2 * a.getX() - b.getX(), a.getY(), 2 * a.getZ() - b.getZ()) : a;
        return tapered(from, b, c, (cx, cz, rx, rz, x, z) -> inEllipse(cx, cz, rx, rz, x, z, true), false);
    }

    private interface Layer {
        boolean in(int cx, int cz, int rx, int rz, int x, int z);
    }

    private static AreaMask tapered(BlockPos a, BlockPos b, BlockPos c, Layer layer, boolean hollow) {
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        int sizeX = maxX - minX, sizeZ = maxZ - minZ;
        int cx = (maxX + minX) / 2, cz = (maxZ + minZ) / 2;
        int y1 = a.getY(), y3 = c.getY();
        int height = Math.abs(y3 - y1);
        int dir = y3 >= y1 ? 1 : -1;
        AreaMask.Builder all = builder(minX, Math.min(y1, y3), minZ, maxX, Math.max(y1, y3), maxZ);
        for (int s = 0; s <= height; s++) {
            int y = y1 + dir * s;
            int rx = height == 0 ? sizeX : sizeX * (height - s) / height / 2;
            int rz = height == 0 ? sizeZ : sizeZ * (height - s) / height / 2;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (layer.in(cx, cz, rx, rz, x, z)) {
                        all.add(x, y, z);
                    }
                }
            }
        }
        AreaMask m = all.build();
        if (!hollow) {
            return m;
        }
        // hollow: only the cells you'd see from outside, the skin
        AreaMask.Builder out = builder(m.minX, m.minY, m.minZ, m.minX + m.sizeX - 1, m.minY + m.sizeY - 1, m.minZ + m.sizeZ - 1);
        Inside in = m::contains;
        m.forEach((x, y, z) -> {
            if (!in.at(x + 1, y, z) || !in.at(x - 1, y, z) || !in.at(x, y + 1, z) || !in.at(x, y - 1, z)
                    || !in.at(x, y, z + 1) || !in.at(x, y, z - 1)) {
                out.add(x, y, z);
            }
        });
        return out.build();
    }
}
