package adris.altoclef.util.helpers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// the lit creeper preempt. not a commitment: CombatCommit never hears about it, the fight and run clocks keep going, and
// the wheel goes straight back the moment the fuse is over. an unlit creeper is scenery, same as every other mob (entry 73),
// this only fires for one that is actually hissing at us
public final class CreeperStep {

    // a lit creeper this close gets stepped away from. the fuse starts at 3, so this fires on the tick it lights
    public static final double TRIGGER = 4;
    // where the step walks to. the blast reaches about 6 (power 3, twice that is the falloff radius), past it is a scratch
    public static final double SAFE = 6;
    // the path walking into a lit one: the next few nodes coming within this of it count like being within TRIGGER
    public static final double PATH_NEAR = 3;
    public static final int PATH_LOOKAHEAD = 6;
    // only lit creepers within this are asked about the path at all. past 7 it un-swells by itself anyway
    public static final double PATH_RANGE = 8;
    // the fuse is 1.5 s, so 2.5 s of standing about is plenty. one that is still lit by then gets left alone
    public static final long HOLD_CAP = 50;
    // no ground gained after this long while still inside TRIGGER is boxed in. a shield takes over then. a second, because
    // the cancel waits for a safe moment and the search takes a few ticks too, and the fuse is 30 so the shield still makes it
    public static final long CORNER_TICKS = 20;
    public static final double CORNER_GAIN = 0.75;
    // ids we remember (logged once, capped once). creeper ids are per world, a small bag is plenty
    private static final int MEMORY = 64;

    public enum Event { NONE, START, CORNERED, END_GONE, END_UNLIT, END_CAP }

    // one creeper as the world sees it this tick. lit = swelling up or flint-and-steeled, pathNear = the path walks into it
    public record Seen(int id, boolean lit, double distance, boolean pathNear) {
    }

    private int _id = -1;
    private long _since;
    private double _startDistance;
    private boolean _cornered;
    private boolean _log;
    private final Set<Integer> _logged = new LinkedHashSet<>();
    private final Set<Integer> _capped = new LinkedHashSet<>();

    public boolean active() {
        return _id >= 0;
    }

    public int creeperId() {
        return _id;
    }

    // boxed in and holding a shield: put it up instead of walking
    public boolean cornered() {
        return _id >= 0 && _cornered;
    }

    // the step that just started should print its line (once per creeper, a relit one does not get a second)
    public boolean shouldLog() {
        return _log;
    }

    public double startedAt() {
        return _startDistance;
    }

    public void reset() {
        _id = -1;
        _cornered = false;
        _log = false;
        _logged.clear();
        _capped.clear();
    }

    // once per game tick. creepers is every creeper close enough to matter, shieldReady = a shield we could put up now
    public Event step(long now, List<Seen> creepers, boolean shieldReady) {
        _log = false;
        if (_id >= 0) {
            Seen held = find(creepers, _id);
            Event end = Event.NONE;
            if (held == null) end = Event.END_GONE;
            else if (!held.lit()) end = Event.END_UNLIT;
            else if (now < _since || now - _since >= HOLD_CAP) end = Event.END_CAP;
            if (end == Event.NONE) {
                if (!_cornered && shieldReady && now - _since >= CORNER_TICKS && held.distance() <= TRIGGER
                        && held.distance() - _startDistance < CORNER_GAIN) {
                    _cornered = true;
                    return Event.CORNERED;
                }
                return Event.NONE;
            }
            // a capped one stays lit next to us somehow (charged and stuck?), it is not worth holding the wheel forever
            if (end == Event.END_CAP) remember(_capped, _id);
            _id = -1;
            _cornered = false;
            return end;
        }
        Seen pick = null;
        for (Seen s : creepers) {
            if (!triggers(s) || _capped.contains(s.id())) continue;
            if (pick == null || s.distance() < pick.distance()) pick = s;
        }
        if (pick == null) return Event.NONE;
        _id = pick.id();
        _since = now;
        _startDistance = pick.distance();
        _cornered = false;
        _log = remember(_logged, pick.id());
        return Event.START;
    }

    // lit and close, or lit and the path is about to walk into it. unlit is nothing at any distance
    public static boolean triggers(Seen s) {
        return s.lit() && (s.distance() <= TRIGGER || s.pathNear());
    }

    // nodes as {x, y, z} block corners. the node's middle is what the player walks through
    public static boolean pathPassesNear(double cx, double cy, double cz, List<int[]> nodes) {
        for (int[] n : nodes) {
            double dx = n[0] + 0.5 - cx, dy = n[1] - cy, dz = n[2] + 0.5 - cz;
            if (dx * dx + dy * dy + dz * dz <= PATH_NEAR * PATH_NEAR) return true;
        }
        return false;
    }

    private static Seen find(List<Seen> creepers, int id) {
        for (Seen s : creepers) if (s.id() == id) return s;
        return null;
    }

    // true when it was not in there yet
    private static boolean remember(Set<Integer> bag, int id) {
        if (!bag.add(id)) return false;
        if (bag.size() > MEMORY) {
            Integer oldest = bag.iterator().next();
            bag.remove(oldest);
        }
        return true;
    }
}
