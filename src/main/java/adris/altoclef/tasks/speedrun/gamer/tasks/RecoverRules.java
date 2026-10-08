package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.HashMap;
import java.util.Map;

// the pure half of "is it a good idea to walk back to the pile". the last two deaths were plain zombies, and the bot walked
// straight back into the same zombies to get its stuff and died again. no world in here, RecoverItemsTask feeds it numbers
public final class RecoverRules {

    // a hostile this close to the pile is between us and our stuff
    public static final double THREAT_RADIUS = 16;
    // and not eight floors up or down, that one has a lot of rock to get through first
    public static final double THREAT_HEIGHT = 8;
    // how long we are willing to stand off and wait for the crowd to wander off before the stuff is not worth it
    public static final double WAIT_SECONDS = 30;
    // and how far from the threats we wait
    public static final double SAFE_DISTANCE = 24;

    private RecoverRules() {
    }

    public enum Verdict {
        // nothing at the pile, walk
        GO,
        // somebody is there, stay back and see if they leave
        WAIT,
        // they are not leaving. the items were not worth a second death
        GIVE_UP
    }

    // offsets from the death spot
    public static boolean threatens(double dx, double dy, double dz) {
        return dx * dx + dz * dz <= THREAT_RADIUS * THREAT_RADIUS && Math.abs(dy) <= THREAT_HEIGHT;
    }

    // lava eats item entities, the void takes them and the walk back is a dare. not worth it
    public static boolean hopeless(boolean inLava, int y, int minY) {
        return inLava || y < minY;
    }

    // how long one drop may eat before it is written off. a drop on a ledge the pickup task keeps failing to reach stays
    // visible, and the tracker's own unreachable list forgets its failures whenever we get a block closer, so this task
    // would stare at it until the whole 90 s budget was gone
    public static final double CHASE_SECONDS = 20;

    // seconds spent chasing each drop (by entity id), counted whatever order the closest one flips in
    public static final class Chase {
        private final Map<Integer, Double> spent = new HashMap<>();
        private double lastNow = Double.NaN;

        // we are going for this drop right now. true once it has had its CHASE_SECONDS
        public boolean update(int dropId, double nowSeconds) {
            // a gap (the standoff, a long walk) is not chasing, only count what a tick or two could have been
            double dt = Double.isNaN(lastNow) ? 0 : Math.min(Math.max(0, nowSeconds - lastNow), 1.0);
            lastNow = nowSeconds;
            return spent.merge(dropId, dt, Double::sum) > CHASE_SECONDS;
        }

        // not chasing anything this tick, the next update is a fresh start for the clock
        public void idle() {
            lastNow = Double.NaN;
        }

        public void reset() {
            spent.clear();
            lastNow = Double.NaN;
        }
    }

    // remembers since when the pile has been crowded. a quiet moment resets the clock, the 30 seconds are 30 in a row
    public static final class Gate {
        private double crowdedSince = Double.NaN;

        public Verdict update(double nowSeconds, int threats) {
            if (threats <= 0) {
                crowdedSince = Double.NaN;
                return Verdict.GO;
            }
            if (Double.isNaN(crowdedSince)) {
                crowdedSince = nowSeconds;
            }
            return nowSeconds - crowdedSince >= WAIT_SECONDS ? Verdict.GIVE_UP : Verdict.WAIT;
        }

        public void reset() {
            crowdedSince = Double.NaN;
        }
    }
}
