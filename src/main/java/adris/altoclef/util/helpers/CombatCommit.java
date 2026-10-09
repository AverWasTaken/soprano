package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the pure half of "do something about it, and then actually finish". overworld only: mobs are ignored until one really
// needs dealing with, and then we commit. a fight holds until its target is dead, a run holds until we are far away and
// nothing is following. no world in here, just numbers, so every transition can be tested without minecraft.
//
// the thing it replaces is a stack of small latches (a fight latch, a charge latch, a run latch, a park timer, a hold)
// that each handed the wheel back when they expired. one commitment has one way in and one way out, and nothing in
// between lets go
public final class CombatCommit {

    // a mob that hit us counts while it is this close, shooters from further out because that is where they stand
    public static final double MELEE_ENGAGE = 6;
    public static final double SHOOTER_ENGAGE = 15;
    // an angry melee mob this close is hitting us whatever the history says
    public static final double CONTACT = CombatPolicy.CONTACT_RANGE;
    public static final float FLEE_HP = CombatRules.FLEE_HEALTH;
    // at or below FLEE_HP anything angry this close is a reason to leave
    public static final double LOW_HP_RANGE = CombatPolicy.LOW_HP_RANGE;
    // how many angry mobs this close make a crowd (the count comes in with the tick, it is a setting)
    public static final double CROWD_RANGE = CombatPolicy.SWARM_RANGE;
    // "it hit us" is remembered this long. a skeleton that landed an arrow ten seconds ago and is still standing there
    // is the same skeleton
    public static final long HIT_MEMORY = 200;
    // a hit this recent is news (the cooldown only listens to news)
    public static final long FRESH = 5;

    // a fight target this far away and quiet for LOST_TICKS is somebody else's problem now
    public static final double LOST_RANGE = 24;
    public static final long LOST_TICKS = 60;
    // ten seconds of not getting any closer: it is on a pillar, across water, or just not coming
    public static final long STALL_TICKS = 200;
    public static final double PROGRESS = 0.5;
    // the target ran away this much from the best we had: that is the new baseline, not progress
    public static final double RUNAWAY_SLACK = 3;

    public static final double RUN_DISTANCE = 50;
    public static final double RUN_CLEAR = 16;
    public static final long RUN_CLEAR_TICKS = 40;
    public static final long RUN_CAP = 900;

    // cornered: a run that went nowhere for CORNER_TICKS with something on top of us (CORNER_RANGE), or for twice that with
    // something angry anywhere within CORNER_WATCH (a shooter we can't get away from). the window only runs while something
    // angry is within CORNER_WATCH, so standing still to eat does not count as being stuck
    public static final double CORNER_WATCH = LOW_HP_RANGE;
    public static final double CORNER_RANGE = CombatPolicy.CONTACT_RANGE;
    public static final long CORNER_TICKS = 60;
    public static final long CORNER_FAR_TICKS = 120;
    public static final double CORNER_PROGRESS = 1.5;

    // a fight we gave up on because we could not get to the mob, and the same mob hits us again this soon after: it is
    // still out of reach and still shooting, so the answer is distance (a run breaks line of sight), not a second try
    public static final long REHIT_RUN = 400;

    // after any commitment ends, only a fresh hit starts the next one
    public static final long COOLDOWN = 100;

    // never hit us
    public static final long NEVER = Long.MAX_VALUE / 4;

    public enum Mode {
        NONE, FIGHT, RUN
    }

    // why a commitment started, so the chain can say so when it does
    public enum Why {
        NONE, HIT, CONTACT, LOW_HP, UNARMED, CROWD, DANGER, CORNERED, UNREACHABLE
    }

    public enum Event {
        NONE, FIGHT_START, RUN_START,
        // the target died and somebody else was already on us, so no gap
        FIGHT_NEXT,
        FIGHT_DEAD, FIGHT_LOST, FIGHT_STALLED,
        FIGHT_TO_RUN,
        RUN_CLEAR, RUN_CAP,
        RUN_TO_FIGHT
    }

    // an angry hostile that can hurt us, as plain numbers. sinceHit: ticks since it hit us (NEVER for not yet)
    public record Foe(int id, double distance, boolean ranged, boolean creeper, long sinceHit) {
        public boolean melee() {
            return !ranged && !creeper;
        }

        public boolean hitUs() {
            return sinceHit <= HIT_MEMORY;
        }

        public boolean fresh() {
            return sinceHit <= FRESH;
        }

        // far enough that the melee/shooter engage line has not been crossed
        public boolean inEngageRange() {
            return distance <= (ranged ? SHOOTER_ENGAGE : MELEE_ENGAGE);
        }
    }

    // everything one tick of the machine wants to know. target is the foe we are fighting looked up on its own (it can be
    // alive and outside the foe list), null when it is dead or gone. danger: something the old rules always ran from
    // (a warden, a vindicator) is close and we are hurt. paused: somebody else is driving this tick (a creeper step
    // away), so the fight's clocks do not run
    public record Tick(long now, float health, boolean armed, double x, double z, int crowdSize, List<Foe> foes, Foe target,
                       boolean danger, boolean paused) {
    }

    // what started a commitment: the mob (null for a hurt-and-surrounded run) and the reason
    public record Trigger(Why why, Foe foe) {
    }

    // only the overworld, and only with the setting on. the nether is a lava fortress and running 50 blocks in it is how
    // you die, the end is a dragon
    public static boolean applies(boolean setting, Dimension dimension) {
        return setting && dimension == Dimension.OVERWORLD;
    }

    // the rule for "does anything here really need dealing with". freshOnly is the cooldown: only news counts, except
    // for being hurt with something close, which is never old news.
    // 1. it hit us and is still close enough to be coming back (6 melee, 15 shooter)
    // 2. an angry melee mob is in contact
    // 3. we are hurt and something angry is within 8 (that one is a run, the foe is just who to name). a run only ends
    //    with nothing within 16 for two seconds, so a mob inside 8 right after one ended came back for us
    // nothing else engages, a skeleton that never hit us and a zombie ten blocks off are scenery
    public static Trigger trigger(float health, List<Foe> foes, boolean freshOnly) {
        Foe hit = null;
        Foe contact = null;
        Foe near = null;
        for (Foe foe : foes) {
            boolean hitCounts = freshOnly ? foe.fresh() : foe.hitUs();
            if (hitCounts && foe.inEngageRange() && (hit == null || foe.distance < hit.distance)) hit = foe;
            if (!freshOnly && foe.melee() && foe.distance <= CONTACT && (contact == null || foe.distance < contact.distance)) contact = foe;
            if (foe.distance <= LOW_HP_RANGE && (near == null || foe.distance < near.distance)) near = foe;
        }
        if (hit != null) return new Trigger(Why.HIT, hit);
        if (contact != null) return new Trigger(Why.CONTACT, contact);
        if (health <= FLEE_HP && near != null) return new Trigger(Why.LOW_HP, near);
        return null;
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

    // angry things on us right now, shooters and creepers included
    public static int crowd(List<Foe> foes) {
        int n = 0;
        for (Foe foe : foes) {
            if (foe.distance <= CROWD_RANGE) n++;
        }
        return n;
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
        if (t.danger()) return startRun(t, Why.DANGER, Event.RUN_START);
        boolean cooling = coolingDown(t.now());
        Trigger trigger = trigger(t.health(), candidates(t), cooling);
        // low hp is "anything angry within 8", ignored or not, cooldown or not: leaving is not a fight with that one mob
        if (trigger == null && t.health() <= FLEE_HP && anyWithin(t.foes(), LOW_HP_RANGE)) {
            trigger = new Trigger(Why.LOW_HP, null);
        }
        if (trigger == null) return Event.NONE;
        // a mob we gave up on that hit us again soon after: it is out of reach and shooting, break the line of sight
        if (trigger.foe() != null && trigger.why() != Why.LOW_HP && droppedRecently(trigger.foe(), t.now())) {
            return startRun(t, Why.UNREACHABLE, Event.RUN_START);
        }
        Why reason = runReason(t);
        if (reason != Why.NONE) return startRun(t, reason, Event.RUN_START);
        // (a hurt-only trigger has no mob and is always a run above, this is just so a null never gets here)
        if (trigger.foe() == null) return Event.NONE;
        return startFight(t, trigger.foe(), trigger.why(), Event.FIGHT_START, false);
    }

    // the run reasons in order of how bad they are. NONE means we are fit to fight
    private Why runReason(Tick t) {
        Why bail = bailReason(t);
        if (bail != Why.NONE) return bail;
        return t.armed() ? Why.NONE : Why.UNARMED;
    }

    // what makes a fight in progress (or one about to start) a run instead. an empty hand is only a reason to not start
    private static Why bailReason(Tick t) {
        if (t.danger()) return Why.DANGER;
        if (t.health() <= FLEE_HP) return Why.LOW_HP;
        if (crowd(t.foes()) >= t.crowdSize()) return Why.CROWD;
        return Why.NONE;
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

    // the foe was dropped for no progress and has hit us since (a candidate only gets here when it has), within REHIT_RUN of
    // the drop. asking forgets the drop: whatever happens next is a fresh start with that mob
    private boolean droppedRecently(Foe foe, long now) {
        Long at = ignored.remove(foe.id());
        return at != null && now - at <= REHIT_RUN;
    }

    private void forgetIgnored(Tick t) {
        if (ignored.isEmpty()) return;
        ignored.entrySet().removeIf(e -> t.now() - e.getValue() > 6000 || t.now() < e.getValue());
    }

    private static boolean anyWithin(List<Foe> foes, double range) {
        for (Foe foe : foes) {
            if (foe.distance() <= range) return true;
        }
        return false;
    }

    // ---- FIGHT

    private Event fight(Tick t) {
        Foe target = t.target();
        // the bail-outs, and the only ones: hurt, or too many of them. a wobble above 8 is a fight. boxed in there is
        // nowhere to bail to, so a cornered fight never asks
        Why bail = cornered ? Why.NONE : bailReason(t);
        if (bail != Why.NONE) {
            // the last thing we were hitting died and nobody is near: that is a won fight, not a reason to run 50 blocks
            if (target == null && !anyWithin(t.foes(), LOW_HP_RANGE)) return end(t, Event.FIGHT_DEAD);
            return startRun(t, bail, Event.FIGHT_TO_RUN);
        }
        if (target == null) return targetGone(t);
        if (t.paused()) {
            // somebody else is driving, the clocks wait
            progressAt++;
            if (farSince >= 0) farSince++;
            return Event.NONE;
        }
        // out of play: far away and not hitting us
        if (target.distance() > LOST_RANGE) {
            if (farSince < 0) farSince = t.now();
            if (t.now() - farSince >= LOST_TICKS && target.sinceHit() > LOST_TICKS) return end(t, Event.FIGHT_LOST);
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
        if (next != null) return startFight(t, next, cornered ? Why.CORNERED : (next.hitUs() ? Why.HIT : Why.CONTACT), Event.FIGHT_NEXT, cornered);
        return end(t, Event.FIGHT_DEAD);
    }

    private Foe nextTarget(Tick t) {
        Foe best = null;
        if (cornered) {
            // still boxed in: whatever is touching us
            for (Foe foe : t.foes()) {
                if (foe.id() != targetId && foe.distance() <= CORNER_RANGE && (best == null || foe.distance() < best.distance())) best = foe;
            }
            return best;
        }
        if (!t.armed()) return null;
        // (a mob we dropped that hit us again is a run, not a chain: idle decides that once this fight is over)
        List<Foe> rest = candidates(t).stream().filter(foe -> foe.id() != targetId && !ignored.containsKey(foe.id())).toList();
        Trigger trigger = trigger(t.health(), rest, false);
        // a hurt-only trigger has no mob to chase, and we already know hp is above the line in here
        return trigger == null ? null : trigger.foe();
    }

    private Event startFight(Tick t, Foe foe, Why reason, Event event, boolean boxedIn) {
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
            return Event.NONE;
        }
        if (t.paused()) {
            // somebody else is driving (a creeper step away), standing still is not being stuck
            if (cornerSince >= 0) cornerSince++;
            return Event.NONE;
        }
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
        // stalled a fight would be picked again every six seconds, with a run in between, for as long as we stood there)
        Foe closest = null;
        for (Foe foe : t.foes()) {
            if (foe.distance() > CORNER_WATCH) continue;
            if (foe.distance() > CORNER_RANGE && (foe.creeper() || ignored.containsKey(foe.id()))) continue;
            if (closest == null || foe.distance() < closest.distance()) closest = foe;
        }
        // nobody worth a fight: the run goes on, the cap or the mob settles it
        if (closest == null) return Event.NONE;
        return startFight(t, closest, Why.CORNERED, Event.RUN_TO_FIGHT, true);
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
        cooldownUntil = t.now() + COOLDOWN;
        return event;
    }
}
