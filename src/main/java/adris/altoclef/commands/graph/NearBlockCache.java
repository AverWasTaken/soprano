package adris.altoclef.commands.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

// near_block's memory: a wait polls every tick, and a block doesn't appear on a schedule that needs 20 scans a second
final class NearBlockCache {

    static final int TICKS = 10;
    // only a few conditions are ever live at once, this is just so it can't grow without end
    private static final int MAX_ENTRIES = 32;

    private record Key(int level, String block, int radius) {
    }

    private record Answer(long tick, boolean near) {
    }

    private final Map<Key, Answer> answers = new HashMap<>();

    // level = a number for the world, so a portal trip doesn't get the old dimension's answer
    boolean ask(int level, String block, int radius, long now, BooleanSupplier scan) {
        Key key = new Key(level, block, radius);
        Answer known = answers.get(key);
        // now < tick: a new world's clock. the old answer means nothing
        if (known != null && now >= known.tick() && now - known.tick() < TICKS) {
            return known.near();
        }
        if (answers.size() >= MAX_ENTRIES) {
            answers.clear();
        }
        boolean near = scan.getAsBoolean();
        answers.put(key, new Answer(now, near));
        return near;
    }
}
