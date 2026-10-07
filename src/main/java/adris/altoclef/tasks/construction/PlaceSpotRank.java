package adris.altoclef.tasks.construction;

// which spot a table or furnace goes on. the old score charged 10 for no floor under it and nothing else, so with a
// ledge of air next door it picked the air, and baritone then built a cobble staircase to reach it (and the gather mined
// the staircase, and so on). now air needs a throwaway above the reserve to be a candidate at all, and the spots next to
// our feet on our level win. pure so it tests without a world
final class PlaceSpotRank {
    // no floor under the spot
    static final double UNSUPPORTED = 10;
    // per block of height away from our feet: a table up a cliff is a climb
    static final double LEVEL = 3;

    private PlaceSpotRank() {
    }

    // what the scan used to charge, kept as the fallback when nothing supported is in range
    static double oldScore(double distSq, boolean solid, boolean hasBelow, boolean inside) {
        return distSq + (solid ? 4 : 0) + (hasBelow ? 0 : UNSUPPORTED) + (inside ? 3 : 0);
    }

    // lower is better, infinity is a no. a spot with no floor is only a candidate if a spendable throwaway (above what
    // a recipe is saving) can build it one
    static double score(double distSq, boolean solid, boolean hasBelow, boolean inside, int dyFromFeet, boolean canScaffold) {
        if (!hasBelow && !canScaffold) {
            return Double.POSITIVE_INFINITY;
        }
        return oldScore(distSq, solid, hasBelow, inside) + LEVEL * Math.abs(dyFromFeet);
    }
}
