package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

// the pure half of "how much can this recipe actually make right now". generic over the item type so the tests
// don't need a booted minecraft, the real callers just pass Items
public final class CraftMath {

    private CraftMath() {
    }

    // how many times the recipe has to run to make `wanted` items. a recipe that makes 9 wheat and a wanted of
    // 99999999 is eleven million crafts, which is exactly the number the grid then has to be clamped against
    public static int craftsFor(int wanted, int outputCount) {
        if (wanted <= 0) return 0;
        return (int) Math.ceil((double) wanted / Math.max(1, outputCount));
    }

    // can a quick move take the output, or would it craft more than we wanted. one craft in the grid is one craft either way,
    // so wanting 2 planks out of a log's 4 is no reason to go through the cursor: the pickup left 4 planks in hand that nothing
    // put away until the cursor watchdog did, 3 s later, on every plank and stick of the opening crafts
    public static boolean quickMoveCraftsNoExtra(int wanted, int craftCount, int multiples) {
        return wanted >= craftCount || (multiples == 1 && wanted > 0);
    }

    // slots: one entry per non-empty recipe slot, holding every item that can go in that slot (planks of any wood,
    // one hay block, three wheat slots...). have: how many of an item we own, grid contents included. cap stops the
    // search early, 99999999 wanted means we never care about more than what the inventory can give anyway.
    // each slot is fed from ONE item type (different planks don't stack in a grid slot), slots with the fewest
    // choices pick first and everybody takes the biggest pile they are allowed to
    public static <T> int maxCrafts(List<? extends List<T>> slots, ToIntFunction<T> have, int cap) {
        if (cap <= 0 || slots.isEmpty()) return 0;
        List<List<T>> ordered = new ArrayList<>(slots.size());
        for (List<T> options : slots) {
            // a slot nothing can fill means the recipe can't run at all
            if (options == null || options.isEmpty()) return 0;
            ordered.add(options);
        }
        ordered.sort(Comparator.comparingInt(List::size));
        Map<T, Integer> owned = new HashMap<>();
        int lo = 0;
        int hi = cap;
        while (lo < hi) {
            // upper middle so the loop always makes progress
            int mid = (int) (((long) lo + hi + 1) / 2);
            if (feasible(ordered, have, owned, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static <T> boolean feasible(List<List<T>> slots, ToIntFunction<T> have, Map<T, Integer> owned, int crafts) {
        Map<T, Integer> left = new HashMap<>();
        for (List<T> options : slots) {
            T best = null;
            int bestLeft = -1;
            for (T option : options) {
                int remaining = left.computeIfAbsent(option, k -> owned.computeIfAbsent(k, have::applyAsInt));
                if (remaining > bestLeft) {
                    best = option;
                    bestLeft = remaining;
                }
            }
            if (best == null || bestLeft < crafts) return false;
            left.put(best, bestLeft - crafts);
        }
        return true;
    }
}
