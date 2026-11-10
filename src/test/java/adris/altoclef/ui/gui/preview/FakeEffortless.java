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

package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.EffortlessAccess;

import java.util.ArrayList;
import java.util.List;

// a house's worth of queue for the previews: a floor done, walls building, a roof and a porch merged, a dig waiting
public final class FakeEffortless implements EffortlessAccess {

    public final List<String> actions = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    public boolean running = true;

    public FakeEffortless() {
        rows.add(row("Floor 9×1×7", "Place · 63 blocks · Oak Planks · into empty", "added 12 min ago", State.DONE, null, false,
                box(9, 1, 7, false, false), 0xB8945F));
        rows.add(row("Walls 9×4×7", "Place · 112 blocks · 3 blocks mixed · into empty", "added 11 min ago", State.RUNNING, null, false,
                box(9, 4, 7, true, false), 0x8F8F8F));
        rows.add(row("Roof and porch", "2 shapes as one job · 96 blocks", "added 9 min ago", State.WAITING, null, true,
                roof(9, 7), 0x7A5230));
        rows.add(row("Cuboid 6×3×6", "Break · 108 blocks · only minecraft:stone", "added just now", State.WAITING, null, false,
                box(6, 3, 6, false, false), 0xE05555));
    }

    private static Row row(String name, String detail, String when, State state, String why, boolean merged, int[][] cells, int rgb) {
        int[] colors = new int[cells[0].length / 3];
        java.util.Arrays.fill(colors, rgb);
        return new Row(name, name, detail, when, state, why, merged, false, cells[1][0], cells[1][1], cells[1][2], cells[0], colors);
    }

    private static int[][] box(int sx, int sy, int sz, boolean walls, boolean hollow) {
        List<Integer> c = new ArrayList<>();
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    boolean edge = x == 0 || z == 0 || x == sx - 1 || z == sz - 1;
                    if (walls && !edge) {
                        continue;
                    }
                    c.add(x);
                    c.add(y);
                    c.add(z);
                }
            }
        }
        return new int[][]{c.stream().mapToInt(Integer::intValue).toArray(), {sx, sy, sz}};
    }

    private static int[][] roof(int sx, int sz) {
        List<Integer> c = new ArrayList<>();
        int layers = sz / 2 + 1;
        for (int k = 0; k < layers; k++) {
            for (int x = 0; x < sx; x++) {
                for (int z = k; z < sz - k; z++) {
                    c.add(x);
                    c.add(k);
                    c.add(z);
                }
            }
        }
        return new int[][]{c.stream().mapToInt(Integer::intValue).toArray(), {sx, layers, sz}};
    }

    @Override
    public List<Row> rows() {
        return rows;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public String status() {
        return running ? "Building 1 of 3" : "3 waiting";
    }

    @Override
    public void start() {
        running = true;
        actions.add("start");
    }

    @Override
    public void stop() {
        running = false;
        actions.add("stop");
    }

    @Override
    public void move(int from, int to) {
        rows.add(to, rows.remove(from));
        actions.add("move " + from + " " + to);
    }

    @Override
    public void mergeWithNext(int index) {
        actions.add("merge " + index);
    }

    @Override
    public void unmerge(int index) {
        actions.add("unmerge " + index);
    }

    @Override
    public void remove(int index) {
        rows.remove(index);
        actions.add("remove " + index);
    }

    @Override
    public void toggleShown(int index) {
        actions.add("show " + index);
    }

    @Override
    public void clearDone() {
        actions.add("clear");
    }
}
