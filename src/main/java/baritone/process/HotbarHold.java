/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

// two blocks the builder wants used to take turns in the same temp slot, each fetch evicting the last one. a slot
// the builder just fetched into stays put for a bit, so the next fetch has to find somewhere else
final class HotbarHold {

    static final int HOLD_TICKS = 100;
    // InventoryBehavior's throwaway(select) swaps into 7 and handWaitingOnSwap watches it, anything we park there is
    // getting kicked out by the next movement that needs a block
    static final int THROWAWAY_SLOT = 7;

    private final long[] fetchedAt = new long[9];

    HotbarHold() {
        clear();
    }

    void fetched(int slot, long now) {
        if (slot >= 0 && slot < 9) {
            fetchedAt[slot] = now;
        }
    }

    boolean held(int slot, long now) {
        return slot >= 0 && slot < 9 && fetchedAt[slot] != Long.MIN_VALUE && now - fetchedAt[slot] < HOLD_TICKS;
    }

    void clear() {
        Arrays.fill(fetchedAt, Long.MIN_VALUE);
    }

    // the disallowed sets to try in order: everything, then let go of the hold, then let it have slot 7 too.
    // useful slots are never up for grabs, that's the whole point of them
    List<IntPredicate> tiers(IntPredicate useful, long now) {
        List<IntPredicate> tiers = new ArrayList<>();
        tiers.add(i -> useful.test(i) || held(i, now) || i == THROWAWAY_SLOT);
        tiers.add(i -> useful.test(i) || i == THROWAWAY_SLOT);
        tiers.add(useful);
        return tiers;
    }
}
