package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// the pure half of "do we fight this, run from this, or just keep walking". no world, just numbers, so it can be tested
// without minecraft. MobDefenseChain feeds it real data once a tick.
//
// the two things it exists for: eight zombies used to be answered with a shield and a prayer (a shield counted for +20
// mobs, so a shielded bot never left any crowd), and walking past a zombie on the way somewhere used to end the task
// and start a fight. a speedrunner backs off, fights them one at a time, or just keeps going
public final class CombatPolicy {

    // a mob this close is a fight, whatever we were doing. also the "is anything on top of us" distance
    public static final double STRIKE_RANGE = 2.5;
    // the path has to stay this far from a mob for it to be somebody else's problem
    public static final double PATH_CLEARANCE = 2.5;
    // creepers ruin your day from further than most things, skeletons from further still
    public static final double CREEPER_NO_IGNORE = 7;
    public static final double RANGED_NO_IGNORE = 15;

    // count melee mobs this close for the "that is a crowd" call
    public static final double SWARM_RANGE = 6;
    // and this close for "is it one at a time or are they stacked on me"
    public static final double CONTACT_RANGE = 3;

    // no flapping between kiting and fighting faster than this
    public static final long MIN_DWELL = 20;
    // a retreat that makes less than this much ground in KITE_WINDOW ticks is going nowhere (a dead end, a door, a lava lake)
    public static final double KITE_PROGRESS = 3;
    public static final long KITE_WINDOW = 40;
    // and once it has gone nowhere we do not try again for this long, we tank it
    public static final long KITE_GIVE_UP = 200;
    // and a retreat that is still going after this long is not working either, they are keeping up
    public static final long KITE_MAX = 300;
    // after a retreat, the ones still coming get to come to us. chasing them back into the pile is how a bot dances
    public static final long HOLD_TICKS = 100;
    public static final double HOLD_CHASE = CONTACT_RANGE + 0.5;

    // the most mobs a shield and good gear can stand against. more than that and the answer is feet
    public static final int STAND_MAX = 4;

    // how fast we need to be moving for "travelling" to mean it, over the last TRAVEL_WINDOW ticks
    public static final double TRAVEL_MOVED = 2;
    public static final long TRAVEL_WINDOW = 20;
    // the path was seen under the user task this recently. past it we were probably busy fighting
    public static final long TRAVEL_FRESH = 10;

    private enum State {
        FIGHT, KITE
    }

    public enum Verdict {
        // nothing here is worth stopping for, keep doing what we were doing
        IGNORE,
        // one thing close enough to hit: turn, hit it, no shield
        FIGHT_ONE,
        // a crowd, back off and string it out. no shield, no swinging, just feet
        KITE,
        // stacked on us (or nowhere to run): shield up and trade
        STAND;
    }

    public record Point(double x, double y, double z) {
    }

    // offsets from the player. fast means it outruns a sprinting player (baby zombies, spiders, hoglins, brutes)
    public record Mob(int id, double dx, double dy, double dz, boolean ranged, boolean creeper, boolean fast, boolean seesUs) {
        public double distance() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public boolean melee() {
            return !ranged && !creeper;
        }
    }

    // path: the next stretch of the route, as offsets from the player. empty when we are not on one.
    // x and z are where the player is, only used to see whether a retreat is getting anywhere
    public record Scene(List<Mob> mobs, long ticksSinceHurt, boolean travelling, List<Point> path, double x, double z,
                        int swarmThreshold, long graceTicks) {
    }

    // hold: do not walk up to anything farther than HOLD_CHASE, let it come
    public record Decision(Verdict verdict, Set<Integer> ignored, int swarm, int near, boolean hold) {
        // the shield is for STAND and nothing else
        public boolean shield() {
            return verdict == Verdict.STAND;
        }

        public boolean kiting() {
            return verdict == Verdict.KITE;
        }
    }

    private State state = State.FIGHT;
    private long stateSince = Long.MIN_VALUE / 2;
    // left KITE because the crowd strung out, not because it went away: do not run again until two are on us
    private boolean needContact;
    private long kiteWindowStart;
    private double kiteWindowX;
    private double kiteWindowZ;
    private long blockedUntil = Long.MIN_VALUE / 2;
    private long holdUntil = Long.MIN_VALUE / 2;

    public Decision decide(long now, Scene scene) {
        Set<Integer> ignored = new HashSet<>();
        List<Mob> active = new ArrayList<>(scene.mobs().size());
        for (Mob mob : scene.mobs()) {
            if (canIgnore(mob, scene)) {
                ignored.add(mob.id());
            } else {
                active.add(mob);
            }
        }
        if (active.isEmpty()) {
            // nothing left to fight. the next crowd is a new crowd
            reset();
            return new Decision(Verdict.IGNORE, ignored, 0, 0, false);
        }

        int swarm = 0;
        int near = 0;
        for (Mob mob : active) {
            double distance = mob.distance();
            if (mob.melee() && !mob.fast() && distance <= SWARM_RANGE) swarm++;
            // creepers are the creeper logic's problem, they do not count towards being stacked on
            if (!mob.creeper() && distance <= CONTACT_RANGE) near++;
        }
        boolean crowd = swarm >= scene.swarmThreshold();
        if (!crowd) needContact = false;
        boolean blocked = now < blockedUntil;

        if (state == State.KITE) {
            if (now - stateSince >= KITE_MAX) {
                // they are keeping up with us, so this is not shaking anybody
                blockedUntil = now + KITE_GIVE_UP;
                blocked = true;
                switchTo(State.FIGHT, now);
            } else if (now - kiteWindowStart >= KITE_WINDOW) {
                double moved = Math.hypot(scene.x() - kiteWindowX, scene.z() - kiteWindowZ);
                if (moved < KITE_PROGRESS) {
                    // going nowhere. whatever is in the way, the shield and the sword work anywhere
                    blockedUntil = now + KITE_GIVE_UP;
                    blocked = true;
                    switchTo(State.FIGHT, now);
                } else {
                    kiteWindowStart = now;
                    kiteWindowX = scene.x();
                    kiteWindowZ = scene.z();
                }
            }
            // done when the crowd has thinned out, or the front runner got to us while the rest are still on their way.
            // nobody in reach and a crowd still on our heels means the run is working, not that it is over
            if (state == State.KITE && now - stateSince >= MIN_DWELL && (near == 1 || !crowd)) {
                needContact = crowd;
                holdUntil = now + HOLD_TICKS;
                switchTo(State.FIGHT, now);
            }
        } else if (crowd && !blocked && (!needContact || near >= 2) && now - stateSince >= MIN_DWELL) {
            switchTo(State.KITE, now);
            kiteWindowStart = now;
            kiteWindowX = scene.x();
            kiteWindowZ = scene.z();
        }

        if (state == State.KITE) return new Decision(Verdict.KITE, ignored, swarm, near, false);
        boolean hold = now < holdUntil;
        // a crowd we could not get away from is a crowd we stand in front of
        if (near >= 2 || (crowd && blocked)) return new Decision(Verdict.STAND, ignored, swarm, near, false);
        return new Decision(Verdict.FIGHT_ONE, ignored, swarm, near, hold);
    }

    // nobody around at all: the cheap version of decide, for the ticks that are nearly all of them
    public Decision idle() {
        reset();
        return IDLE;
    }

    private void reset() {
        state = State.FIGHT;
        needContact = false;
        // the dwell is for flapping inside one fight, not between two
        stateSince = Long.MIN_VALUE / 2;
    }

    private static final Decision IDLE = new Decision(Verdict.IGNORE, Set.of(), 0, 0, false);

    private void switchTo(State next, long now) {
        state = next;
        stateSince = now;
    }

    // whether this mob is somebody else's business while we walk past. every one of these has to hold
    public static boolean canIgnore(Mob mob, Scene scene) {
        if (!scene.travelling()) return false;
        // a hit, from anything, means it is no longer a stroll
        if (scene.ticksSinceHurt() <= scene.graceTicks()) return false;
        // outruns us, so walking away from it is not an option
        if (mob.fast()) return false;
        double distance = mob.distance();
        if (distance <= STRIKE_RANGE) return false;
        if (mob.creeper() && distance <= CREEPER_NO_IGNORE) return false;
        if (mob.ranged() && mob.seesUs() && distance <= RANGED_NO_IGNORE) return false;
        // heading for it is not passing it
        for (Point p : scene.path()) {
            double dx = p.x() - mob.dx(), dz = p.z() - mob.dz();
            if (Math.abs(p.y() - mob.dy()) <= 3 && dx * dx + dz * dz <= PATH_CLEARANCE * PATH_CLEARANCE) return false;
        }
        return true;
    }

    // how many mobs we can tank. the shield used to add 20 here, which is how a shielded bot stood in the middle of
    // eight zombies. one extra now, and nothing stands against more than STAND_MAX whatever it is wearing.
    // damage is the sword's attack damage minus 3 (the tuning is from before the real numbers were used)
    public static int standCapacity(int armor, float damage, boolean hasShield) {
        int base = (int) Math.ceil((armor * 3.6 / 20.0) + (damage * 0.8)) + 1;
        if (hasShield) base++;
        return Math.min(base, STAND_MAX);
    }

    // is the user's path leading us somewhere. the latch: while the user task is the one pathing we keep what it was
    // about to walk, because the moment mob defense takes over the goal is gone and the question has no answer
    public static final class TravelTracker {
        private static final long SAMPLE_EVERY = 2;

        private record Sample(long tick, double x, double z) {
        }

        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private long lastPathing = Long.MIN_VALUE / 2;
        private List<Point> upcoming = List.of();

        // pathing is "the user task is current and baritone has a path"
        public void update(long now, double x, double z, boolean pathing) {
            if (!samples.isEmpty() && (now < samples.peekLast().tick || now - samples.peekLast().tick > TRAVEL_WINDOW)) {
                // time went backwards or we were not watching, the history is nonsense
                samples.clear();
            }
            while (!samples.isEmpty() && now - samples.peekFirst().tick > TRAVEL_WINDOW) {
                samples.pollFirst();
            }
            if (samples.isEmpty() || now - samples.peekLast().tick >= SAMPLE_EVERY) {
                samples.addLast(new Sample(now, x, z));
            }
            if (pathing) lastPathing = now;
        }

        // the next stretch of the route, absolute coordinates. only worth building when somebody is going to ask
        public void setUpcoming(List<Point> upcomingAbsolute) {
            upcoming = upcomingAbsolute;
        }

        public boolean travelling(long now, double x, double z) {
            if (now - lastPathing > TRAVEL_FRESH) return false;
            // a full window of history, or it could be the first step of anything
            if (samples.isEmpty() || now - samples.peekFirst().tick < TRAVEL_WINDOW - SAMPLE_EVERY * 2) return false;
            for (Sample s : samples) {
                if (Math.hypot(x - s.x, z - s.z) >= TRAVEL_MOVED) return true;
            }
            return false;
        }

        public List<Point> upcoming() {
            return upcoming;
        }

        // the user task changed, whatever route we were remembering is somebody else's
        public void clear() {
            samples.clear();
            lastPathing = Long.MIN_VALUE / 2;
            upcoming = List.of();
        }
    }
}
