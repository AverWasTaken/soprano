package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// the pure half of "can this mob actually get to us". no world, no entities, just numbers and a Terrain, so it can be
// tested without booting minecraft. MobReachability feeds it real data.
public final class MobReachRules {

    // a mob is 0.6 wide and walks up 0.6 high steps. anything lower than that is a speed bump, not a wall
    public static final double STEP_HEIGHT = 0.6;

    // "more than 3 blocks up or down while basically on top of us". past that a zombie is not getting there by walking
    public static final double VERTICAL_GAP = 3;
    public static final double VERTICAL_GAP_HORIZONTAL_RANGE = 4;

    // no closer by a block over two seconds means it is stuck on something
    public static final double STALL_PROGRESS = 1;
    public static final long STALL_TICKS = 40;

    private MobReachRules() {
    }

    public enum Verdict {
        REACHABLE, ENCLOSED, VERTICAL_GAP, STALLED;

        public boolean reachable() {
            return this == REACHABLE;
        }
    }

    public interface Terrain {
        // collision tall enough that a mob at this height has to go around (taller than a step). doors, trapdoors, fence
        // gates and fluids say false here, zombies break doors and swim
        boolean wall(int x, int y, int z);

        // any collision at all, same exclusions. what a mob's head and body bonk into
        boolean solid(int x, int y, int z);
    }

    // standing at feet block (x, y, z): ceiling over the head and all four sides closed. a mob needs 2 blocks of clearance
    // so a side counts as closed if either the feet cell or the head cell is blocked, a 1 high gap lets nobody through
    public static boolean isEnclosed(Terrain t, int x, int y, int z) {
        if (!t.solid(x, y + 2, z)) return false;
        return sideClosed(t, x + 1, y, z) && sideClosed(t, x - 1, y, z)
                && sideClosed(t, x, y, z + 1) && sideClosed(t, x, y, z - 1);
    }

    private static boolean sideClosed(Terrain t, int x, int y, int z) {
        return t.wall(x, y, z) || t.solid(x, y + 1, z);
    }

    // directly above or below us, further than a mob can climb or safely drop. spiders skip this one, they climb
    public static boolean isVerticallyOut(double dx, double dy, double dz) {
        if (Math.abs(dy) <= VERTICAL_GAP) return false;
        return dx * dx + dz * dz <= VERTICAL_GAP_HORIZONTAL_RANGE * VERTICAL_GAP_HORIZONTAL_RANGE;
    }

    // "is it worth dropping the task for". separate from "is it angry" and "can it get to us": a zombie can be both and
    // still be ten blocks down a cave, where it can come up to us or not and we do not need to go and find out.
    // the leash is the bigger zone a fight is allowed to wander in once it started, so it does not flap at the edge
    public static final double LEASH_RANGE = 12;
    public static final double LEASH_HEIGHT = 5;
    // things walking at us count from here, things shooting at us count from here (skeletons go off at ~15, we are nicer)
    public static final double CLOSING_RANGE = 12;
    public static final double RANGED_RANGE = 12;
    // shooters get a longer leash than everything else. it used to be RANGED_RANGE-ish too, so a skeleton at 12.3 got
    // dropped and picked up again every other tick. six blocks of slack is the whole fix
    public static final double RANGED_LEASH_RANGE = RANGED_RANGE + 6;
    public static final double RANGED_LEASH_HEIGHT = 6;
    // dropped by this much in this many ticks is "coming at us"
    public static final double CLOSING_PROGRESS = 1.5;
    public static final long CLOSING_TICKS = 30;

    // the leash grows with the engage zone, otherwise turning engage range up past 12 would silently do nothing
    public static double leashRange(double engageRange) {
        return Math.max(LEASH_RANGE, engageRange + 4);
    }

    public static double leashHeight(double engageHeight) {
        return Math.max(LEASH_HEIGHT, engageHeight + 2);
    }

    public static double leashRange(double engageRange, boolean ranged) {
        return ranged ? Math.max(RANGED_LEASH_RANGE, engageRange + 6) : leashRange(engageRange);
    }

    public static double leashHeight(double engageHeight, boolean ranged) {
        return ranged ? Math.max(RANGED_LEASH_HEIGHT, engageHeight + 2) : leashHeight(engageHeight);
    }

    // close and on our level
    public static boolean isNear(double dx, double dy, double dz, double range, double height) {
        return Math.abs(dy) <= height && dx * dx + dz * dz <= range * range;
    }

    // still close enough to keep a fight going. the same box as isNear, just bigger
    public static boolean inLeash(double dx, double dy, double dz, double range, double height) {
        return inLeash(dx, dy, dz, range, height, false);
    }

    // shooters hold on to the bigger box, see RANGED_LEASH_RANGE
    public static boolean inLeash(double dx, double dy, double dz, double range, double height, boolean ranged) {
        return isNear(dx, dy, dz, leashRange(range, ranged), leashHeight(height, ranged));
    }

    // should this angry mob pull us off whatever we were doing. always inside the leash, so a mob we would let go of
    // can never be one we just picked up (that is how you get a bot that dances at the boundary)
    public static boolean shouldEngage(double dx, double dy, double dz, double range, double height,
                                       boolean closing, boolean ranged, boolean seesUs) {
        if (!inLeash(dx, dy, dz, range, height, ranged)) return false;
        if (isNear(dx, dy, dz, range, height)) return true;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (closing && distance <= CLOSING_RANGE) return true;
        return ranged && seesUs && distance <= RANGED_RANGE;
    }

    // for the log, why a mob is not worth the trip. only meaningful when shouldEngage said no
    public static String ignoreReason(double dx, double dy, double dz, double range, double height) {
        if (dy < -height) return String.format("too far below to bother (dy %.0f)", dy);
        if (dy > height) return String.format("too far above to bother (dy +%.0f)", dy);
        return String.format("too far away to bother (%.0f blocks)", Math.sqrt(dx * dx + dz * dz));
    }

    // is this mob walking at us. keeps a few samples per mob, "closer by 1.5 at some point in the last 30 ticks" is the
    // answer. a mob that is merely standing there, or leaving, or shuffling about its own business is not
    public static final class ClosingTracker {
        // gaps in watching this long mean the history is meaningless (we were busy eating, the mob was out of range, ...)
        private static final long FORGET_AFTER = 20;
        private static final long SAMPLE_EVERY = 2;

        private record Sample(long tick, double distance) {
        }

        private static final class Entry {
            final ArrayDeque<Sample> samples = new ArrayDeque<>();
            long lastSeen;
        }

        private final Map<Integer, Entry> entries = new HashMap<>();

        // record a sample, returns true if the mob got at least CLOSING_PROGRESS closer within CLOSING_TICKS
        public boolean update(int id, double distance, long now) {
            Entry e = entries.get(id);
            if (e == null || now < e.lastSeen || now - e.lastSeen > FORGET_AFTER) {
                e = new Entry();
                entries.put(id, e);
            }
            e.lastSeen = now;
            while (!e.samples.isEmpty() && now - e.samples.peekFirst().tick > CLOSING_TICKS) {
                e.samples.pollFirst();
            }
            boolean closing = false;
            for (Sample s : e.samples) {
                if (s.distance - distance >= CLOSING_PROGRESS) {
                    closing = true;
                    break;
                }
            }
            if (e.samples.isEmpty() || now - e.samples.peekLast().tick >= SAMPLE_EVERY) {
                e.samples.addLast(new Sample(now, distance));
            }
            return closing;
        }

        public void prune(long now) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().lastSeen > FORGET_AFTER * 5) it.remove();
            }
        }

        public void clear() {
            entries.clear();
        }

        public int size() {
            return entries.size();
        }
    }

    // watches how close each mob gets over time. a mob that is trying to reach us and is still no closer two seconds later
    // is stuck on a fence or a moat or a bad idea, and we stop caring about it
    public static final class StallTracker {
        // gaps in watching this long mean the clock is meaningless (we were busy eating, the mob was out of range, ...)
        private static final long FORGET_AFTER = 20;

        private static final class Entry {
            double best;
            long since;
            long lastSeen;
        }

        private final Map<Integer, Entry> entries = new HashMap<>();

        // record a sample, returns true if this mob has been stuck for STALL_TICKS
        public boolean update(int id, double distance, long now) {
            Entry e = entries.get(id);
            if (e == null || now - e.lastSeen > FORGET_AFTER) {
                e = new Entry();
                e.best = distance;
                e.since = now;
                entries.put(id, e);
            }
            e.lastSeen = now;
            if (distance <= e.best - STALL_PROGRESS) {
                // got closer by enough, restart the clock from here
                e.best = distance;
                e.since = now;
            } else if (distance >= e.best + STALL_PROGRESS) {
                // walking away is not stuck, it is just leaving. start over from where it is
                e.best = distance;
                e.since = now;
            }
            return now - e.since >= STALL_TICKS;
        }

        // drop entries nobody asked about lately, so ids of dead mobs do not pile up forever
        public void prune(long now) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().lastSeen > FORGET_AFTER * 5) it.remove();
            }
        }

        public void clear() {
            entries.clear();
        }

        public int size() {
            return entries.size();
        }
    }
}
