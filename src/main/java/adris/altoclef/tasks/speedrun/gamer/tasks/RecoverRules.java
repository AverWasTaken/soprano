package adris.altoclef.tasks.speedrun.gamer.tasks;

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
