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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// the exposed skin of any voxel shape. coplanar faces cancel their shared edges; corners and steps retain theirs
public final class SelectionSurface {
    public record Point(int x, int y, int z) { }
    public record Edge(Point a, Point b) { }
    public record Face(int x, int y, int z, int axis, int sign, Point a, Point b, Point c, Point d) { }
    public final List<Face> faces = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();

    public SelectionSurface(AreaMask mask) {
        Map<Edge, Integer> seams = new LinkedHashMap<>();
        mask.forEach((x, y, z) -> {
            for (int axis = 0; axis < 3; axis++) {
                for (int sign : new int[]{-1, 1}) {
                    int nx = x + (axis == 0 ? sign : 0);
                    int ny = y + (axis == 1 ? sign : 0);
                    int nz = z + (axis == 2 ? sign : 0);
                    if (mask.contains(nx, ny, nz)) continue;
                    int[] p = {x, y, z};
                    if (sign > 0) p[axis]++;
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    Point a = point(p);
                    p[u]++; Point b = point(p);
                    p[v]++; Point c = point(p);
                    p[u]--; Point d = point(p);
                    faces.add(new Face(x, y, z, axis, sign, a, b, c, d));
                    Point[] corners = {a, b, c, d};
                    for (int i = 0; i < 4; i++) {
                        Point q = corners[i], t = corners[(i + 1) % 4];
                        if (compare(q, t) > 0) { Point swap = q; q = t; t = swap; }
                        Edge edge = new Edge(q, t);
                        int normal = 1 << (axis * 2 + (sign > 0 ? 1 : 0));
                        seams.merge(edge, normal, (old, value) -> old ^ value);
                    }
                }
            }
        });
        seams.forEach((edge, normals) -> { if (normals != 0) edges.add(edge); });
    }

    private static Point point(int[] p) { return new Point(p[0], p[1], p[2]); }
    private static int compare(Point a, Point b) {
        int c = Integer.compare(a.x(), b.x());
        if (c == 0) c = Integer.compare(a.y(), b.y());
        return c == 0 ? Integer.compare(a.z(), b.z()) : c;
    }
}
