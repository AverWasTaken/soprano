package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// who we poked and who poked us, by entity id, for NeutralMobs.MEMORY_TICKS. the client never learns what a remote mob is
// aiming at (Mob.target is server side), so "we hit it or it hit us lately" is the honest version of "it is angry at us".
// pure on purpose, game time comes in as a number. the tracker thread and the render thread both touch it
public final class Provocations {
    private static final int PRUNE_AT = 128;

    private final Map<Integer, Long> stamps = new HashMap<>();

    public synchronized void mark(int id, long now) {
        stamps.put(id, now);
        if (stamps.size() > PRUNE_AT) {
            Iterator<Long> it = stamps.values().iterator();
            while (it.hasNext()) {
                long age = now - it.next();
                // negative age is a clock that went backwards (new world), nobody is owed a grudge from there
                if (age < 0 || age > NeutralMobs.MEMORY_TICKS) it.remove();
            }
        }
    }

    public synchronized boolean recent(int id, long now) {
        return recent(id, now, NeutralMobs.MEMORY_TICKS);
    }

    // same book, shorter memory: the pass-by grace only cares about the last few seconds
    public synchronized boolean recent(int id, long now, long window) {
        Long at = stamps.get(id);
        if (at == null) return false;
        long age = now - at;
        return age >= 0 && age <= window;
    }

    public synchronized void clear() {
        stamps.clear();
    }
}
