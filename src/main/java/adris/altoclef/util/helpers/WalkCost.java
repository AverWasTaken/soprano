package adris.altoclef.util.helpers;

// a rough "how long is the walk" for deciding if going back to a placed station is worth it. a crafting table costs about
// one log, so a trip that takes longer than fetching a new one is a loss. pure so it can be tested without a game
public final class WalkCost {
    // a block of height costs this many blocks of flat walking. down a cave you are digging, jumping and then climbing
    // back out, and the old straight line sphere called a 30 block drop "24 blocks away" (that was the cave trip)
    public static final double VERTICAL_WEIGHT = 4.0;
    // the longest trip that still beats placing a fresh table (or a log's worth of wood). about 20 flat blocks
    public static final double STATION_BUDGET = 20.0;

    private WalkCost() {
    }

    // horizontal distance plus 4 per block of height, either direction. offsets are target minus us
    public static double estimate(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dz * dz) + VERTICAL_WEIGHT * Math.abs(dy);
    }

    public static boolean within(double dx, double dy, double dz, double budget) {
        return estimate(dx, dy, dz) <= budget;
    }
}
