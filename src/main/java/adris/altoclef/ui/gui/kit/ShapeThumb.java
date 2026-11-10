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

package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// a little isometric picture of a shape's cells for the effortless queue, so a row shows what it will build at a
// glance. software rasterised into a PixelImage once per shape, the canvas just blits it. only cells you could see from
// the corner the camera sits on get drawn, a solid 30x30x30 is the 2700 on its skin and not 27000
public final class ShapeThumb {

    private ShapeThumb() {
    }

    // cells are x,y,z triples from 0, colors one rgb per cell
    public static PixelImage render(int[] cells, int[] colors, int sizeX, int sizeY, int sizeZ, int w, int h) {
        PixelImage img = new PixelImage(w, h);
        int n = cells.length / 3;
        if (n == 0) {
            return img;
        }
        Set<Long> filled = new HashSet<>(n * 2);
        for (int i = 0; i < n; i++) {
            filled.add(key(cells[3 * i], cells[3 * i + 1], cells[3 * i + 2]));
        }
        // the camera looks from +x, +y, +z: a cell covered on all three of those sides can't be seen
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int x = cells[3 * i], y = cells[3 * i + 1], z = cells[3 * i + 2];
            if (!(filled.contains(key(x + 1, y, z)) && filled.contains(key(x, y + 1, z)) && filled.contains(key(x, y, z + 1)))) {
                seen.add(i);
            }
        }
        // back to front: further along x+z first, lower first
        seen.sort((a, b) -> {
            int da = cells[3 * a] + cells[3 * a + 2];
            int db = cells[3 * b] + cells[3 * b + 2];
            return da != db ? Integer.compare(da, db) : Integer.compare(cells[3 * a + 1], cells[3 * b + 1]);
        });
        // u = (x - z) * s, v = ((x + z) / 2 - y) * s, with a cube one s wide and one s tall
        float spanU = sizeX + sizeZ;
        float spanV = (sizeX + sizeZ) / 2f + sizeY;
        float s = Math.max(1f, Math.min((w - 2) / Math.max(1f, spanU), (h - 2) / Math.max(1f, spanV)));
        float ou = (w - spanU * s) / 2f + sizeZ * s;
        float ov = (h - spanV * s) / 2f + sizeY * s;
        int[] px = img.pixels();
        for (int i : seen) {
            int x = cells[3 * i], y = cells[3 * i + 1], z = cells[3 * i + 2];
            int rgb = colors.length > i ? colors[i] : 0xFFFFFF;
            float cu = ou + (x - z) * s;
            float cv = ov + ((x + z) / 2f - y) * s;
            cube(px, w, h, cu, cv, s, rgb);
        }
        img.bump();
        return img;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    // one cube with its top at (cu, cv) - the far corner of the top face. lit top, mid left, dark right
    private static void cube(int[] px, int w, int h, float cu, float cv, float s, int rgb) {
        float hs = s / 2f;
        // top face: a diamond
        poly(px, w, h, 0xFF000000 | rgb, new float[]{cu, cu + s, cu, cu - s}, new float[]{cv - hs, cv, cv + hs, cv});
        // left face
        poly(px, w, h, 0xFF000000 | shade(rgb, 0.78f), new float[]{cu - s, cu, cu, cu - s}, new float[]{cv, cv + hs, cv + hs + s, cv + s});
        // right face
        poly(px, w, h, 0xFF000000 | shade(rgb, 0.58f), new float[]{cu, cu + s, cu + s, cu}, new float[]{cv + hs, cv, cv + s, cv + hs + s});
    }

    static int shade(int rgb, float f) {
        int r = Math.round(((rgb >> 16) & 0xFF) * f);
        int g = Math.round(((rgb >> 8) & 0xFF) * f);
        int b = Math.round((rgb & 0xFF) * f);
        return r << 16 | g << 8 | b;
    }

    // scanline fill of a convex quad, pixel centres inside it
    private static void poly(int[] px, int w, int h, int argb, float[] xs, float[] ys) {
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float y : ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int y0 = Math.max(0, (int) Math.floor(minY));
        int y1 = Math.min(h - 1, (int) Math.ceil(maxY));
        for (int py = y0; py <= y1; py++) {
            float cy = py + 0.5f;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                int j = (i + 1) % xs.length;
                float ya = ys[i], yb = ys[j];
                if ((cy >= ya && cy < yb) || (cy >= yb && cy < ya)) {
                    float t = (cy - ya) / (yb - ya);
                    float x = xs[i] + t * (xs[j] - xs[i]);
                    lo = Math.min(lo, x);
                    hi = Math.max(hi, x);
                }
            }
            if (lo > hi) {
                continue;
            }
            int x0 = Math.max(0, (int) Math.ceil(lo - 0.5f));
            int x1 = Math.min(w - 1, (int) Math.floor(hi - 0.5f));
            for (int x = x0; x <= x1; x++) {
                px[py * w + x] = argb;
            }
        }
    }
}
