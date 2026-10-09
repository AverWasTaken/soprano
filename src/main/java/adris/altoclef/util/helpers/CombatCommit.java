package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the pure half of "do something about it, and then actually finish", for every dimension: mobs are ignored until one
// really needs dealing with, and then we commit. "really" is doing a lot of work there: the task walks on past crowds,
// shooters and lit creepers, only a mob chewing on us in contact or actual danger gets the wheel. a fight holds until
// its target is dead or gone, a run holds until we are far away and nothing is following. no world in here, just numbers, so every transition can be tested without minecraft.
// what differs between the overworld, the nether and the end is who counts as a foe and what kind of foe it is (FoeRules),
// the machine itself does not know where it is
//
// the thing it replaced is a stack of small latches (a fight latch, a charge latch, a run latch, a park timer, a hold)
// that each handed the wheel back when they expired. one commitment has one way in and one way out, and nothing in
// between lets go
public final class CombatCommit {

    // a mob that hit us is only a fight while it is this close. a skeleton plinking from ten blocks is answered by walking,
    // a moving target is a bad target
    public static final double CONTACT = CombatRules.CONTACT_RANGE;
    // the warden's sonic boom reaches about this far, and a boom is a run whatever the hp
    public static final double BOOM_RANGE = 15;
    public static final float FLEE_HP = CombatRules.FLEE_HEALTH;
    // heavy hitters are run from at this hp instead (see CombatRules.HEAVY_FLEE_HEALTH for why it is its own number)
    public static final float HEAVY_FLEE_HP = CombatRules.HEAVY_FLEE_HEALTH;
    // at or below FLEE_HP anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = CombatRules.LOW_HP_RANGE;
    // "it hit us" is remembered this long. a skeleton that landed an arrow ten seconds ago and is still standing there
    // is the same skeleton
    public static final long HIT_MEMORY = 200;
    // a hit this recent is news (the cooldown only listens to news)
    public static final long FRESH = 5;

    // a fight target this far away and quiet for LOST_TICKS is somebody else's problem now. we are not chasing a zombie
    // across the map, the task has places to be
    public static final double LOST_RANGE = 6;
    public static final long LOST_TICKS = 60;
    // ten seconds of not getting any closer: it is on a pillar, across water, or just not coming
    public static final long STALL_TICKS = 200;
    public static final double PROGRESS = 0.5;
    // the target ran away this much from the best we had: that is the new baseline, not progress
    public static final double RUNAWAY_SLACK = 3;

    // a run is over 24 blocks out (flat) with nothing angry within 12 for two seconds, or at 25 s. it used to be 50/16/45,
    // which was a lot of jogging for one zombie
    public static final double RUN_DISTANCE = 24;
    public static final double RUN_CLEAR = 12;
    public static final long RUN_CLEAR_TICKS = 40;
    public static final long RUN_CAP = 500;

    // cornered: a run that went nowhere for CORNER_TICKS with something on top of us (CORNER_RANGE), or for twice that with
    // something angry anywhere within CORNER_WATCH (a shooter we can't get away from). the window only runs while something
    // angry is within CORNER_WATCH, so standing still to eat does not count as being stuck
    public static final double CORNER_WATCH = LOW_HP_RANGE;
    public static final double CORNER_RANGE = CombatRules.CONTACT_RANGE;
    public static final long CORNER_TICKS = 60;
    public static final long CORNER_FAR_TICKS = 120;
    public static final double CORNER_PROGRESS = 1.5;

    // a run that has gone nowhere for this long with nothing angry within CORNER_WATCH is not a run, it is a bot standing at
    // a lava lake with a pathfinder that keeps saying no. over, instead of spinning until the cap. with something near the
    // cornered rule above already has an answer (fight it)
    public static final long RUN_STUCK_TICKS = 100;

    // after any commitment ends, only a fresh hit starts the next one
    public static final long COOLDOWN = 100;

    // never hit us
    public static final long NEVER = Long.MAX_VALUE / 4;

    public enum Mode {
        NONE, FIGHT, RUN
    }

    // why a commitment started, so the chain can say so when it does
    public enum Why {
        NONE, HIT, LOW_HP, HEAVY, CORNERED
    }

    public enum Event {
        NONE, FIGHT_START, RUN_START,
        // the target died and somebody else was already on us, so no gap
        FIGHT_NEXT,
        FIGHT_DEAD, FIGHT_LOST, FIGHT_STALLED,
        FIGHT_TO_RUN,
        RUN_CLEAR, RUN_CAP, RUN_STUCK,
        RUN_TO_FIGHT
    }

    // what sort of foe it is, which is the only thing the machine needs to know about a species
    public enum Kind {
        NORMAL,
        // hovers where we cannot walk up to it (a blaze). it counts as a shooter (FoeRules.shoots), and like any shooter
        // it is only a fight once it is in contact
        FLYER,
        // lands 8 to 13 in one swing (wither skeleton, hoglin, brute, vindicator): a run at HEAVY_FLEE_HP instead of FLEE_HP
        HEAVY,
        // not a fight at any hp (warden, wither): whatever fires, it is a run
        UNTOUCHABLE
    }

    // an angry hostile that can hurt us, as plain numbers. sinceHit: ticks since it hit us (NEVER for not yet)
    public record Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit, Kind kind) {
        // a plain mob
        public Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit) {
            this(id, distance, ranged, creeper, sinceHit, Kind.NORMAL);
        }

        public boolean melee() {
            return !ranged && !creeper;
        }

        public boolean hitUs() {
            return sinceHit <= HIT_MEMORY;
        }

        public boolean fresh() {
            return sinceHit <= FRESH;
        }

        // this one is a run, not a fight, at this hp
        public boolean mustRun(float health) {
            return kind == Kind.UNTOUCHABLE || (kind == Kind.HEAVY && health <= HEAVY_FLEE_HP);
        }

        // something we only ever run from, close enough to matter. the warden: within 8, or booming us from out to 15. a
        // heavy hitter only once it has got to us (hit us from within 8, or in contact): one just standing around in a
        // bastion is walked past like anything else, otherwise the nether is one long jog
        public boolean danger(float health) {
            if (!mustRun(health)) return false;
            if (kind == Kind.UNTOUCHABLE) return distance <= LOW_HP_RANGE || (hitUs() && distance <= BOOM_RANGE);
            return distance <= CONTACT || (hitUs() && distance <= LOW_HP_RANGE);
        }
    }

    // everything one tick of the machine wants to know. target is the foe we are fighting looked up on its own (it can be
    // alive and outside the foe list), null when it is dead or gone
    public record Tick(long now, float health, double x, double z, List<Foe> foes, Foe target) {
    }

    // what started a commitment: the mob, and the reason (HEAVY and LOW_HP are runs, the foe is just who to name)
    public record Trigger(Why why, Foe foe) {
    }

    // the rule for "does anything here really need dealing with". almost always no: a crowd around us, a skeleton
    // shooting from range, a lit creeper and a zombie ten blocks off are all scenery and the task walks on.
    // freshOnly is the cooldown, and it only gates the fight: danger is never old news.
    // 1. something we never fight at this hp has got to us (a heavy hitter at 10 hp that hit us or is in contact, the
    //    warden within 8 or booming us): a run
    // 2. we are hurt and something angry is within 8: a run. a run only ends with nothing within 12 for two seconds, so
    //    a mob inside 8 right after one ended came back for us
    // 3. it hit us and it is in contact: a fight. hitting back is the only way that one stops
    public static Trigger trigger(float health, List<Foe> foes, boolean freshOnly) {
        Foe danger = null;
        Foe near = null;
        for (Foe foe : foes) {
            if (foe.danger(health) && (danger == null || foe.distance < danger.distance)) danger = foe;
            if (foe.distance <= LOW_HP_RANGE && (near == null || foe.distance < near.distance)) near = foe;
        }
        if (danger != null) return new Trigger(Why.HEAVY, danger);
        if (health <= FLEE_HP && near != null) return new Trigger(Why.LOW_HP, near);
        Foe hit = hitInContact(foes, freshOnly);
        return hit == null ? null : new Trigger(Why.HIT, hit);
    }

    // the closest thing that hit us and is close enough to swing at
    private static Foe hitInContact(List<Foe> foes, boolean freshOnly) {
        Foe hit = null;
        for (Foe foe : foes) {
            boolean hitCounts = freshOnly ? foe.fresh() : foe.hitUs();
            if (hitCounts && foe.distance <= CONTACT && (hit == null || foe.distance < hit.distance)) hit = foe;
        }
        return hit;
    }

    // fight / flee / eat for the food chain. a fight is never a mealtime. a run is one only once nothing angry is within
    // RUN_CLEAR, the same line the run itself calls clear: chewing is walking at a third of the speed, so a bite with
    // somebody following at 9 blocks is abandoned a second later when they close to 8, and then it starts again, and
    // all that does is slow the run down. the commitment stays through a bite, it does not even hand the wheel over.
    // with nobody committed it is calm unless a lit creeper is close or we are hurt with something near. nearest is the
    // distance to the closest angry thing, infinity for none
    public static CombatRules.Stance stance(Mode mode, float health, double nearest, boolean creeperClose, boolean gapple) {
        boolean hurtAndNear = health <= FLEE_HP && nearest <= LOW_HP_RANGE;
        boolean fighting = switch (mode) {
            case FIGHT -> true;
            case RUN -> nearest <= RUN_CLEAR;
            default -> creeperClose || hurtAndNear;
        };
        if (!fighting) return CombatRules.Stance.CALM;
        CombatRules.Stance stance = CombatRules.stance(true, health, nearest, gapple);
        // the quick bite at hp 4 is for nothing next to us. committed means something is close, so feet first, bite later
        if (stance == CombatRules.Stance.EAT && mode != Mode.NONE) return CombatRules.Stance.FLEE;
        return stance;
    }

    // ---- state

    private Mode mode = Mode.NONE;
    private Why why = Why.NONE;
    private int targetId = -1;
    private boolean cornered;
    private double originX;
    private double originZ;
    private long startTick;
    private long lastTick = Long.MIN_VALUE;
    private long cooldownUntil = Long.MIN_VALUE / 2;

    // fight bookkeeping
    private double bestDistance;
    private long progressAt;
    private long farSince = -1;

    // run bookkeeping
    private long clearSince = -1;
    private long cornerSince = -1;
    private double cornerX;
    private double cornerZ;
    private long stuckSince = -1;
    private double stuckX;
    private double stuckZ;

    // mobs we gave up on, and when. they get another go when they hit us again
    private final Map<Integer, Long> ignored = new HashMap<>();

    public Mode mode() {
        return mode;
    }

    public Why why() {
        return why;
    }

    public int targetId() {
        return targetId;
    }

    public boolean cornered() {
        return cornered;
    }

    public double originX() {
        return originX;
    }

    public double originZ() {
        return originZ;
    }

    public long startTick() {
        return startTick;
    }

    public boolean coolingDown(long now) {
        return now < cooldownUntil;
    }

    public boolean isIgnored(int id) {
        return ignored.containsKey(id);
    }

    public void reset() {
        mode = Mode.NONE;
        why = Why.NONE;
        targetId = -1;
        cornered = false;
        lastTick = Long.MIN_VALUE;
        cooldownUntil = Long.MIN_VALUE / 2;
        farSince = -1;
        clearSince = -1;
        cornerSince = -1;
        stuckSince = -1;
        ignored.clear();
    }

    public Event step(Tick t) {
        // a clock that went backwards is a new world, nobody is owed a grudge from there
        if (lastTick != Long.MIN_VALUE && t.now() < lastTick) reset();
        lastTick = t.now();
        switch (mode) {
            case FIGHT:
                return fight(t);
            case RUN:
                return run(t);
            default:
                return idle(t);
        }
    }

    // ---- NONE

    private Event idle(Tick t) {
        forgetIgnored(t);
        boolean cooling = coolingDown(t.now());
        // danger is "anything that bad within 8", ignored or not, cooldown or not: leaving is not a fight with that one mob
        Why run = runReason(t);
        if (run != Why.NONE) return startRun(t, run, Event.RUN_START);
        // the fight is only for one we have not given up on, or one that has hit us since. empty-handed is fine, a punch
        // is still a punch and a zombie on us is not going anywhere
        Foe hit = hitInContact(candidates(t), cooling);
        if (hit == null) return Event.NONE;
        return startFight(t, hit, Why.HIT, Event.FIGHT_START, false);
    }

    // what makes a fight in progress (or one about to start) a run instead, NONE for fit to fight. the same danger rules
    // as trigger(), plus the fight target itself (it can be outside the foe list and still be the thing to leave)
    private static Why runReason(Tick t) {
        Foe target = t.target();
        // (the same "has it got to us" rule as for anything else, the fight target just gets asked too)
        if (target != null && target.danger(t.health())) return Why.HEAVY;
        Trigger trigger = trigger(t.health(), t.foes(), false);
        if (trigger != null && trigger.why() != Why.HIT) return trigger.why();
        if (target != null && t.health() <= FLEE_HP && target.distance() <= LOW_HP_RANGE) return Why.LOW_HP;
        return Why.NONE;
    }

    private static boolean anyDanger(Tick t) {
        if (t.target() != null && t.target().danger(t.health())) return true;
        for (Foe foe : t.foes()) {
            if (foe.danger(t.health())) return true;
        }
        return false;
    }

    // the foes minus the ones we gave up on (unless they have hit us since)
    private List<Foe> candidates(Tick t) {
        if (ignored.isEmpty()) return t.foes();
        return t.foes().stream().filter(foe -> !stillIgnored(foe, t.now())).toList();
    }

    private boolean stillIgnored(Foe foe, long now) {
        Long at = ignored.get(foe.id());
        if (at == null) return false;
        // the last hit is at or before the moment we gave up on it: no news
        return foe.sinceHit() >= now - at;
    }

    private void forgetIgnored(Tick t) {
        if (ignored.isEmpty()) return;
        ignored.entrySet().removeIf(e -> t.now() - e.getValue() > 6000 || t.now() < e.getValue());
    }

    // ---- FIGHT

    private Event fight(Tick t) {
        Foe target = t.target();
        // the bail-outs, and the only ones: the danger rules. a wobble above 8 is a fight, and so is hp 8 with nothing
        // within 8 (the last thing we were hitting died, that is a won fight, not a reason to run 24 blocks). boxed in
        // there is nowhere to bail to, so a cornered fight never asks
        Why bail = cornered ? Why.NONE : runReason(t);
        if (bail != Why.NONE) return startRun(t, bail, Event.FIGHT_TO_RUN);
        if (target == null) return targetGone(t);
        // out of play: wandered off past LOST_RANGE and not hitting us. if it wants another go, that is a new fight
        if (target.distance() > LOST_RANGE) {
            if (farSince < 0) farSince = t.now();
            if (t.now() - farSince >= LOST_TICKS && target.sinceHit() > LOST_TICKS) {
                // boxed in, the second tier picks within 8, so a lost one would be picked again every few seconds. same
                // deal as a stall: written off until it hits us again
                if (cornered) ignored.put(target.id(), t.now());
                return end(t, Event.FIGHT_LOST);
            }
        } else {
            farSince = -1;
        }
        // getting anywhere? contact is the best kind of progress, closing in counts, running off just moves the baseline
        double d = target.distance();
        if (d <= CONTACT) {
            bestDistance = d;
            progressAt = t.now();
        } else if (d < bestDistance - PROGRESS) {
            bestDistance = d;
            progressAt = t.now();
        } else if (d > bestDistance + RUNAWAY_SLACK) {
            bestDistance = d;
        }
        if (t.now() - progressAt >= STALL_TICKS) {
            ignored.put(target.id(), t.now());
            return end(t, Event.FIGHT_STALLED);
        }
        return Event.NONE;
    }

    // the target died or left the world. if somebody else is already on us, carry straight on (a gap here is a tick of
    // the user task walking with a zombie in our face), otherwise it is over
    private Event targetGone(Tick t) {
        Foe next = nextTarget(t);
        if (next != null) return startFight(t, next, cornered ? Why.CORNERED : Why.HIT, Event.FIGHT_NEXT, cornered);
        // a boxed in fight never asked the bail rules, so the warden standing on us when the last thing dies is still ours to
        // leave: a gap and a five second cooldown next to it is not a plan
        if (cornered && anyDanger(t)) return startRun(t, Why.HEAVY, Event.FIGHT_TO_RUN);
        return end(t, Event.FIGHT_DEAD);
    }

    private Foe nextTarget(Tick t) {
        Foe best = null;
        if (cornered) {
            // still boxed in: whatever is touching us (bar the one thing that is never a fight)
            for (Foe foe : t.foes()) {
                if (foe.id() != targetId && foe.kind() != Kind.UNTOUCHABLE && foe.distance() <= CORNER_RANGE
                        && (best == null || foe.distance() < best.distance())) best = foe;
            }
            return best;
        }
        // the next thing chewing on us, same rule as starting one. (not one we gave up on, idle sorts that out once this fight
        // is over, and not one we only run from, runReason already had its say)
        List<Foe> rest = candidates(t).stream()
                .filter(foe -> foe.id() != targetId && !ignored.containsKey(foe.id()) && !foe.mustRun(t.health()))
                .toList();
        return hitInContact(rest, false);
    }

    private Event startFight(Tick t, Foe foe, Why reason, Event event, boolean boxedIn) {
        // a new fight with a mob we once gave up on is a clean slate with it (it hit us since, or we are boxed in with it)
        ignored.remove(foe.id());
        mode = Mode.FIGHT;
        why = reason;
        targetId = foe.id();
        cornered = boxedIn;
        startTick = t.now();
        bestDistance = foe.distance();
        progressAt = t.now();
        farSince = -1;
        return event;
    }

    // ---- RUN

    private Event startRun(Tick t, Why reason, Event event) {
        mode = Mode.RUN;
        why = reason;
        targetId = -1;
        cornered = false;
        originX = t.x();
        originZ = t.z();
        startTick = t.now();
        clearSince = -1;
        cornerSince = -1;
        stuckSince = -1;
        return event;
    }

    private Event run(Tick t) {
        double fromOrigin = Math.hypot(t.x() - originX, t.z() - originZ);
        double nearest = Double.POSITIVE_INFINITY;
        for (Foe foe : t.foes()) {
            nearest = Math.min(nearest, foe.distance());
        }
        // clear means nothing angry within RUN_CLEAR. the clock is on whether or not we are far enough yet, so the moment
        // we are far enough the answer is already in
        if (nearest <= RUN_CLEAR) {
            clearSince = -1;
        } else if (clearSince < 0) {
            clearSince = t.now();
        }
        if (t.now() - startTick >= RUN_CAP) return end(t, Event.RUN_CAP);
        if (fromOrigin >= RUN_DISTANCE && clearSince >= 0 && t.now() - clearSince >= RUN_CLEAR_TICKS) return end(t, Event.RUN_CLEAR);

        if (nearest > CORNER_WATCH) {
            cornerSince = -1;
            return stuck(t);
        }
        // something is close, the corner window below is the one that counts and this one starts over when it leaves
        stuckSince = -1;
        if (cornerSince < 0 || Math.hypot(t.x() - cornerX, t.z() - cornerZ) >= CORNER_PROGRESS) {
            cornerSince = t.now();
            cornerX = t.x();
            cornerZ = t.z();
            return Event.NONE;
        }
        // on the spot for three seconds with something on top of us, or six with something anywhere within the watch range
        long stuck = t.now() - cornerSince;
        if (stuck < CORNER_TICKS || (nearest > CORNER_RANGE && stuck < CORNER_FAR_TICKS)) return Event.NONE;
        // nowhere to go and it is close: armed or not, hurt or not, this is a fight. on top of us anything will do. further out
        // only something we could walk up to and hit: no creeper, and nothing we already gave up on (a skeleton across water that
        // stalled a fight would be picked again every six seconds, with a run in between, for as long as we stood there).
        // the warden is the exception to "anything will do": never a fight in any state, boxed in or not
        Foe closest = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > CORNER_WATCH || foe.kind() == Kind.UNTOUCHABLE) continue;
            if (foe.distance() > CORNER_RANGE && (foe.creeper() || ignored.containsKey(foe.id()))) continue;
            if (closest == null || foe.distance() < closest.distance()) closest = foe;
        }
        // nobody worth a fight: the run goes on, the cap or the mob settles it
        if (closest == null) return Event.NONE;
        return startFight(t, closest, Why.CORNERED, Event.RUN_TO_FIGHT, true);
    }

    // nothing angry within CORNER_WATCH and no ground gained for RUN_STUCK_TICKS: the pathfinder has nowhere to take us (a lava
    // lake, the edge of the loaded world, an island)
    private Event stuck(Tick t) {
        if (stuckSince < 0 || Math.hypot(t.x() - stuckX, t.z() - stuckZ) >= CORNER_PROGRESS) {
            stuckSince = t.now();
            stuckX = t.x();
            stuckZ = t.z();
            return Event.NONE;
        }
        return t.now() - stuckSince >= RUN_STUCK_TICKS ? end(t, Event.RUN_STUCK) : Event.NONE;
    }

    // ---- the end of anything

    private Event end(Tick t, Event event) {
        mode = Mode.NONE;
        why = Why.NONE;
        targetId = -1;
        cornered = false;
        farSince = -1;
        clearSince = -1;
        cornerSince = -1;
        stuckSince = -1;
        cooldownUntil = t.now() + COOLDOWN;
        return event;
    }
}
