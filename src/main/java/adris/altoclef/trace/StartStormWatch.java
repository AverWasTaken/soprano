package adris.altoclef.trace;

import java.util.HashMap;
import java.util.Map;

// a task class that keeps starting over is a loop the debug state never shows (every start is a fresh "START"). keeps
// the last 21 start times per class in a ring, so there is nothing to trim and nothing allocated after the first
// start of a class: 21 of them inside the window is a storm. pure, the clock is an argument
public final class StartStormWatch {

    public static final long WINDOW_MS = 60_000;
    // more than this many starts in the window is a storm
    public static final int LIMIT = 20;

    private static final class Entry {
        final long[] times = new long[LIMIT + 1];
        int count;
        int head;
        boolean fired;
    }

    private final Map<Object, Entry> entries = new HashMap<>();

    // true means "say so now": the 21st start inside the window, once per storm. it re-arms when the rate drops back
    // under the limit (the window no longer holds 21)
    public boolean start(Object key, long nowMs) {
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        int cap = e.times.length;
        if (e.count < cap) {
            e.times[(e.head + e.count) % cap] = nowMs;
            e.count++;
        } else {
            e.times[e.head] = nowMs;
            e.head = (e.head + 1) % cap;
        }
        if (e.count < cap) {
            return false;
        }
        if (nowMs - e.times[e.head] > WINDOW_MS) {
            e.fired = false;
            return false;
        }
        if (e.fired) {
            return false;
        }
        e.fired = true;
        return true;
    }
}
