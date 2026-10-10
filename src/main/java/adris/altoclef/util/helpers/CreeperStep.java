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
    // where the step walks to. the blast reaches about 6 (power 3, twice that is the falloff radius), past it is a scratch,
    // and a creeper that gets more than 7 away stops swelling, so 8 clears both with a block to spare for the search's
    // idea of where the creeper is being a tick old
    public static final double SAFE = 8;
    // the path walking into a lit one: the next few nodes coming within this of it count like being within TRIGGER
    public static final double PATH_NEAR = 3;
    public static final int PATH_LOOKAHEAD = 6;
    // only lit creepers within this are asked about the path at all. past 7 it un-swells by itself anyway
    public static final double PATH_RANGE = 8;
    // the fuse is 1.5 s, so 2.5 s of standing about is plenty. one that is still lit by then gets left alone
    public static final long HOLD_CAP = 50;
    // an unlit creeper with charge still in its fuse keeps the step held while it is within this
    public static final double HOLD_RADIUS = 5;
    // no ground gained after this long while still inside TRIGGER is boxed in. a shield takes over then. a second, because
    // the cancel waits for a safe moment and the search takes a few ticks too, and the fuse is 30 so the shield still makes it.
    // it only looks at where we are, so the raw sprint at the start of the step counts as ground gained like any other
    public static final long CORNER_TICKS = 20;
    public static final double CORNER_GAIN = 0.75;
    // ids we remember (logged once, capped once). creeper ids are per world, a small bag is plenty
    private static final int MEMORY = 64;

    public enum Event { NONE, START, CORNERED, END_GONE, END_UNLIT, END_CAP }

    // one creeper as the world sees it this tick. lit = swelling up or flint-and-steeled, pathNear = the path walks into it
    // fusing = the fuse still has charge in it, lit or deflating (a creeper that lost its line to us goes unlit with the swell
    // still up and relights the moment we walk back in)
    public record Seen(int id, boolean lit, double distance, boolean pathNear, boolean fusing) {
        public Seen(int id, boolean lit, double distance, boolean pathNear) {
            this(id, lit, distance, pathNear, lit);
        }
    }

    private int _id = -1;
    private long _since;
    // when the creeper we hold last went lit, -1 while it is unlit. the cap only blacklists one that sat lit the whole time
    private long _litSince = -1;
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
            if (held != null && !held.lit()) _litSince = -1;
            else if (held != null && _litSince < 0) _litSince = now;
            Event end = Event.NONE;
            if (held == null) end = Event.END_GONE;
            // the fuse is really out when it is unlit AND drained, or we are well away from it. unlit with charge left and still
            // close is a deflating fuse, and the user task walking back in relights it: that was the wheel flipping back and
            // forth. the cap below is the backstop for one that never drains
            else if (!held.lit() && !(held.fusing() && held.distance() <= HOLD_RADIUS)) end = Event.END_UNLIT;
            else if (now < _since || now - _since >= HOLD_CAP) end = Event.END_CAP;
            if (end == Event.NONE) {
                if (!_cornered && shieldReady && now - _since >= CORNER_TICKS && held.distance() <= TRIGGER
                        && held.distance() - _startDistance < CORNER_GAIN) {
                    _cornered = true;
                    return Event.CORNERED;
                }
                return Event.NONE;
            }
            // a capped one stays lit next to us somehow (charged and stuck?), it is not worth holding the wheel forever. a hold that
            // only ran long because the fuse lit, deflated and relit is not that one: it still has to light up to explode, so it
            // gets stepped from again if it relights (and one that flickers at the swell edge is the shield's problem, not a blacklist's)
            if (end == Event.END_CAP && _litSince >= 0 && now - _litSince >= HOLD_CAP) remember(_capped, _id);
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
        _litSince = now;
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
