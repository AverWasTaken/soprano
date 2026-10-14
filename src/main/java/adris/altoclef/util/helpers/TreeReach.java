package adris.altoclef.util.helpers;

// can we get at this log, and if not, is it worth going for it anyway. plain ints so the rules are testable without a world:
// the caller reads the column under the log and says what it found
//
// a log is "flagged" when the first solid block under it is further than a fall (WorldHelper.dangerousToBreakIfRightAbove).
// DestroyBlockTask then mines it from the side or from the shaft under it, and the shaft only goes so deep (GoalMineFromSide).
// canopy logs 6-8 above the ground are past that, the only way up to them is a tower of our own blocks
public final class TreeReach {
    // GoalMineFromSide.MAX_UNDER, repeated so this stays free of the construction package: feet this far under the log still swing
    public static final int MAX_UNDER = 5;
    // no floor in the column (water, lava, or the scan ran out before it found one)
    public static final int NO_FLOOR = Integer.MIN_VALUE;
    // how many blocks of dirt the tower costs when we do not know the column, and the most we ever go and dig for
    private static final int DIRT_MIN = 4;
    private static final int DIRT_MAX = 10;
    private static final int DIRT_UNKNOWN = 8;

    private TreeReach() {
    }

    // air cells between the log and the floor under it. 0 is a log sitting on the ground, no floor is as far as it gets
    public static int gap(int logY, int floorY) {
        return floorY == NO_FLOOR ? Integer.MAX_VALUE : Math.max(0, logY - floorY - 1);
    }

    // can the ground alone get us a swing at it. floorY is the top of the first solid block under the log's own column
    // (the shaft case: feet on that floor, log up to MAX_UNDER over our feet). sideStandable is "some neighbouring column has
    // a floor within a step of the log's height", the sides of GoalMineFromSide
    public static boolean fromGround(int logY, int floorY, boolean sideStandable) {
        return sideStandable || gap(logY, floorY) <= MAX_UNDER;
    }

    // leave this log out of the pick. only logs that are flagged and cannot be had from the ground are ever in question (an
    // intact trunk opens up as it is cut from the bottom), and a tower or a trip for dirt makes them fair again
    public static boolean skip(boolean flagged, boolean fromGround, boolean hasThrowaway, boolean dirtPossible) {
        return flagged && !fromGround && !hasThrowaway && !dirtPossible;
    }

    // the log needs a tower and we have nothing to build it with, but dirt is lying around: dig it before going up
    public static boolean needsDirtFirst(boolean flagged, boolean fromGround, boolean hasThrowaway, boolean dirtPossible) {
        return flagged && !fromGround && !hasThrowaway && dirtPossible;
    }

    // blocks of dirt for the tower: one per cell between the floor and the log, a couple spare for a miss
    public static int dirtWanted(int gap) {
        if (gap == Integer.MAX_VALUE) {
            return DIRT_UNKNOWN;
        }
        return Math.max(DIRT_MIN, Math.min(DIRT_MAX, gap + 1));
    }
}
