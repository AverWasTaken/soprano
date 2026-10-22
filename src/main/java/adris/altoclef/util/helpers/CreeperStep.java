package adris.altoclef.util.helpers;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// the lit creeper preempt. not a commitment: the fight and run clocks keep going, and the wheel goes straight back the moment
// the fuse is over. an unlit creeper is scenery, same as every other mob (entry 73), this only fires for one that is
// actually hissing at us. the one thing CombatCommit does hear: the same creeper coming back for a second go (REPEAT_WINDOW)
//
// two ways to step. the bait: back off to just past the blast and stand there. a swelling creeper stands still, and between 6
// and 7 the blast does nothing to us while the fuse keeps going, so it pops and that is the end of it. the defuse: walk out
// past 7 so it stops swelling. the defuse never ends anything, it chases, gets back inside 3 and lights again, and the step
// and the task took turns at the wheel about once a second. so the defuse is only for the ones we cannot let pop
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

    // the bait. the blast is power 3 from the creeper's feet, so nothing past 6 feet to feet gets hurt, and it stops swelling
    // past 7. stop the sprint at 6.2, letting go of the keys still slides us about a third of a block, so we land around 6.5
    public static final double BAIT_STOP = 6.2;
    // standing still and it got closer than this (it relit and walked in): back out again. a bit under BAIT_STOP so a slide
    // back and forth on the line is not a thing
    public static final double BAIT_RESUME = 6.0;
    // when the straight line out is blocked the path goes for a cell in this ring. GoalRunAwayFromEntities is "at least", and
    // at least 6.3 is happy to end at 9, which is a defuse with extra steps
    public static final double BAIT_RING_MIN = 6.3;
    public static final double BAIT_RING_MAX = 6.9;
    // the fuse is 30 ticks, so the bait is over well before this. the rest covers one deflate and relight inside the same step
    public static final long BAIT_CAP = 80;
    // a creeper we stepped from that lights up beside us again this soon did not go away: the bait did not take, or it was one
    // we had to defuse. that is the run's job now (CombatCommit, REPEAT_CREEPER), not another lap of the step
    public static final long REPEAT_WINDOW = 600;

    // the hand sprint at the start of a defuse. a standing start covers about 2 blocks in 10 ticks and 7 in 30, and the fuse is 30,
    // so it keeps going straight away until we are at SAFE or the fuse is spent. (waiting on the path for the rest of it is how
    // a creeper lit at 3 still got us from 2 blocks)
    public static final long RAW_MAX_TICKS = 30;

    public static boolean rawGoing(long ticks, double distance) {
        return ticks < RAW_MAX_TICKS && distance < SAFE;
    }

    public enum Event { NONE, START, CORNERED, END_GONE, END_UNLIT, END_CAP }

    // one creeper as the world sees it this tick. lit = swelling up or flint-and-steeled, pathNear = the path walks into it
    // fusing = the fuse still has charge in it, lit or deflating (a creeper that lost its line to us goes unlit with the swell
    // still up and relights the moment we walk back in)
    // charged = powered, twice the blast, so 6.5 is nowhere near past it. nearStuff = something of ours (a portal frame, a bed,
    // a chest, a furnace, a table) close enough to it that letting it pop leaves a hole where that used to be. both mean defuse
    public record Seen(int id, boolean lit, double distance, boolean pathNear, boolean fusing, boolean charged, boolean nearStuff) {
        public Seen(int id, boolean lit, double distance, boolean pathNear, boolean fusing) {
            this(id, lit, distance, pathNear, fusing, false, false);
        }

        public Seen(int id, boolean lit, double distance, boolean pathNear) {
            this(id, lit, distance, pathNear, lit);
        }

        // only worth the bait: a plain creeper with nothing of ours around it
        public boolean baitable() {
            return !charged && !nearStuff;
        }
    }

    private int _id = -1;
    private long _since;
    // when the creeper we hold last went lit, -1 while it is unlit. the cap only blacklists one that sat lit the whole time
    private long _litSince = -1;
    private double _startDistance;
    private boolean _cornered;
    private boolean _log;
    // this step lets it pop instead of walking it out of range
    private boolean _bait;
    // this step's creeper is back for a second go and the commitment should run from it (true on the tick it starts only)
    private boolean _escalate;
    private final Set<Integer> _logged = new LinkedHashSet<>();
    private final Set<Integer> _capped = new LinkedHashSet<>();
    // id -> the tick its last step ended
    private final Map<Integer, Long> _stepped = new LinkedHashMap<>();

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

    public boolean baiting() {
        return _id >= 0 && _bait;
    }

    // the step that just started is a creeper coming back: hand CombatCommit a run (true on that one tick only)
    public boolean escalate() {
        return _escalate;
    }

    public void reset() {
        _id = -1;
        _cornered = false;
        _log = false;
        _bait = false;
        _escalate = false;
        _logged.clear();
        _capped.clear();
        _stepped.clear();
    }

    public Event step(long now, List<Seen> creepers, boolean shieldReady) {
        return step(now, creepers, shieldReady, CombatCommit.Mode.NONE, false);
    }

    // once per game tick. creepers is every creeper close enough to matter, shieldReady = a shield we could put up now.
    // mode = what the commitment held last tick, commitOn = it is switched on (so a run handed to it really starts)
    public Event step(long now, List<Seen> creepers, boolean shieldReady, CombatCommit.Mode mode, boolean commitOn) {
        return step(now, creepers, shieldReady, mode, commitOn, false);
    }

    // recovering: the heal-up hold is on (CombatCommit.recovering). nothing is committed then, but a hurt bot standing still
    // for a pop is a bot eating a creeper at 10 hp, so it gets the defuse like a run or a fight would
    public Event step(long now, List<Seen> creepers, boolean shieldReady, CombatCommit.Mode mode, boolean commitOn, boolean recovering) {
        return step(now, creepers, shieldReady, mode, commitOn, recovering, false);
    }

    // noHeading: CreeperEscape has nowhere to sprint (a one wide shaft, a creeper on our head). waiting on a path for a second
    // to find that out is most of the fuse, so a lit one inside TRIGGER with no way out is cornered the tick it shows up. the
    // chain asks CreeperEscape, this stays pure
    public Event step(long now, List<Seen> creepers, boolean shieldReady, CombatCommit.Mode mode, boolean commitOn, boolean recovering,
                      boolean noHeading) {
        _log = false;
        _escalate = false;
        boolean idle = mode == CombatCommit.Mode.NONE;
        boolean mayBait = idle && !recovering;
        if (_id >= 0) {
            // standing still for a pop is for a bot with nothing better to do. a run or a fight that showed up mid bait (a
            // crowd, low hp, something chewing on us) gets the defuse instead, which at least keeps moving
            if (_bait && !mayBait) _bait = false;
            Seen held = find(creepers, _id);
            if (held != null && !held.lit()) _litSince = -1;
            else if (held != null && _litSince < 0) _litSince = now;
            long cap = _bait ? BAIT_CAP : HOLD_CAP;
            Event end = Event.NONE;
            if (held == null) end = Event.END_GONE;
            else if (fuseOut(held, mode)) end = Event.END_UNLIT;
            else if (now < _since || now - _since >= cap) end = Event.END_CAP;
            if (end == Event.NONE) {
                if (!_cornered && shieldReady && noHeading && held.lit() && held.distance() <= TRIGGER) {
                    _cornered = true;
                    return Event.CORNERED;
                }
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
            if (end == Event.END_CAP && _litSince >= 0 && now - _litSince >= cap) remember(_capped, _id);
            // (re-put so the oldest end is the one that falls out first)
            _stepped.remove(_id);
            _stepped.put(_id, now);
            if (_stepped.size() > MEMORY) _stepped.remove(_stepped.keySet().iterator().next());
            _id = -1;
            _cornered = false;
            return end;
        }
        Seen pick = null;
        for (Seen s : creepers) {
            if (!triggers(s) || _capped.contains(s.id())) continue;
            // a run is already taking us away from everything, creepers included. stopping it to step is the step and the run
            // trading the wheel, so only when the run's own path is about to walk into a lit one
            if (mode == CombatCommit.Mode.RUN && !s.pathNear()) continue;
            if (pick == null || s.distance() < pick.distance()) pick = s;
        }
        if (pick == null) return Event.NONE;
        // a second go is the run's, when there is a run to hand it to. that step is the defuse: the bait already did not take
        // (or was never on the table), and the run is about to carry us off anyway, so this is just out of the blast until
        // it does. with nobody to hand it to (the commitment is off) it is the bait again, the defuse is the loop
        _escalate = cameBack(pick.id(), now) && idle && commitOn;
        // nowhere to go beats every plan that walks: no bait ring (a path up the shaft at the thing), no defuse
        boolean boxed = noHeading && shieldReady && pick.lit() && pick.distance() <= TRIGGER;
        _bait = mayBait && !_escalate && pick.baitable() && !boxed;
        _id = pick.id();
        _since = now;
        _litSince = now;
        _startDistance = pick.distance();
        _cornered = boxed;
        _log = remember(_logged, pick.id());
        return boxed ? Event.CORNERED : Event.START;
    }

    // the bait holds through a deflate and a relight at any distance, there is nothing to walk back into: it pops or it drains.
    // the defuse is over when it is unlit AND drained, or we are well away from it. unlit with charge left and still close is a
    // deflating fuse, and the user task walking back in relights it: that was the wheel flipping back and forth. the cap is
    // the backstop for one that never drains
    //
    // a step inside a run is stricter: the run's path leads back past the creeper, so letting go at "unlit and over 5" had the
    // creeper relight and the step start again about every second, with the arrows landing in between. there it holds until the
    // swell is fully drained and we are at SAFE
    private boolean fuseOut(Seen held, CombatCommit.Mode mode) {
        if (held.lit()) return false;
        if (_bait) return !held.fusing();
        if (mode == CombatCommit.Mode.RUN) return !held.fusing() && held.distance() >= SAFE;
        return !(held.fusing() && held.distance() <= HOLD_RADIUS);
    }

    private boolean cameBack(int id, long now) {
        Long at = _stepped.get(id);
        return at != null && now >= at && now - at <= REPEAT_WINDOW;
    }

    // standing still for the bait. once stopped it takes a bit more to get going again, see BAIT_RESUME
    public static boolean baitStands(double distance, boolean standing) {
        return distance >= (standing ? BAIT_RESUME : BAIT_STOP);
    }

    // where the bait path may end: middle of the cell to the creeper's feet
    public static boolean inBaitRing(double distance) {
        return distance >= BAIT_RING_MIN && distance <= BAIT_RING_MAX;
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
