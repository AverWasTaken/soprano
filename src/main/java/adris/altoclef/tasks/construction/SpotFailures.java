package adris.altoclef.tasks.construction;

import java.util.HashMap;
import java.util.Map;

// how many times a placing spot let us down. the progress checker in PlaceBlockNearbyTask resets whenever baritone is
// pathing, so a spot that sent us walking in circles never failed and was retried until the heat death of the bot.
// two strikes and it is out, pathing or not. pure (plain ints) so the rule tests without a world
final class SpotFailures {
    static final int MAX_FAILS = 2;

    private final Map<Long, Integer> fails = new HashMap<>();

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // true when this strike was the one that put the spot out
    boolean fail(int x, int y, int z) {
        return fails.merge(key(x, y, z), 1, Integer::sum) >= MAX_FAILS;
    }

    boolean isBad(int x, int y, int z) {
        return fails.getOrDefault(key(x, y, z), 0) >= MAX_FAILS;
    }

    int count(int x, int y, int z) {
        return fails.getOrDefault(key(x, y, z), 0);
    }

    void clear() {
        fails.clear();
    }
}
