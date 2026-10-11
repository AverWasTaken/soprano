package adris.altoclef.ui.gui.kit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

// callbacks into tabs and facades go through here. a throwing callback is logged once and the gui carries on, because
// a crashed screen mid game is a much worse bug than a button that did nothing
public final class Safe {

    private static final Set<String> seen = ConcurrentHashMap.newKeySet();

    private Safe() {
    }

    public static void run(String where, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            report(where, t);
        }
    }

    public static boolean test(String where, BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (Throwable t) {
            report(where, t);
            return false;
        }
    }

    public static void report(String where, Throwable t) {
        String key = where + ": " + t;
        // a message that changes every time would otherwise log every time and grow this forever
        if (seen.size() > 200) {
            seen.clear();
        }
        if (seen.add(key)) {
            System.err.println("[gui] " + key);
            StackTraceElement[] st = t.getStackTrace();
            if (st.length > 0) {
                System.err.println("[gui]   at " + st[0]);
            }
        }
    }
}
