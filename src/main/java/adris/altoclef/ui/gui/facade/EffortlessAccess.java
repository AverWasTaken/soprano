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

package adris.altoclef.ui.gui.facade;

import java.util.List;

// the effortless tab: the queue of shapes waiting to be built or broken. indices are the queue's own order
public interface EffortlessAccess {

    enum State {
        WAITING, RUNNING, DONE, FAILED
    }

    // cells are x,y,z triples relative to the corner of the entry, colours one per cell (the job that owns it), so the
    // thumbnail can be drawn without the game. key changes whenever the cells do, the tab caches the picture by it
    record Row(Object key, String name, String detail, String when, State state, String why, boolean merged, boolean shown,
               int sizeX, int sizeY, int sizeZ, int[] cells, int[] colors) {
    }

    List<Row> rows();

    boolean running();

    // "Running 2 of 5", "Stopped", "Empty". what the header says
    String status();

    void start();

    void stop();

    void move(int from, int to);

    // this one and the one below it become one job
    void mergeWithNext(int index);

    void unmerge(int index);

    void remove(int index);

    void toggleShown(int index);

    void clearDone();

    EffortlessAccess NONE = new EffortlessAccess() {
        @Override
        public List<Row> rows() {
            return List.of();
        }

        @Override
        public boolean running() {
            return false;
        }

        @Override
        public String status() {
            return "Empty";
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void move(int from, int to) {
        }

        @Override
        public void mergeWithNext(int index) {
        }

        @Override
        public void unmerge(int index) {
        }

        @Override
        public void remove(int index) {
        }

        @Override
        public void toggleShown(int index) {
        }

        @Override
        public void clearDone() {
        }
    };
}
