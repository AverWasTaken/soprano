package adris.altoclef.util.helpers;

import java.util.function.IntFunction;

// can we get at this log, and if not, is it worth going for it anyway. plain ints so the rules are testable without a world:
// the caller reads the column under the log and says what it found
//
// a log is "flagged" when the first solid block under it is further than a fall (WorldHelper.dangerousToBreakIfRightAbove).
// DestroyBlockTask then mines it from the side or from the shaft under it, and the shaft only goes so deep (GoalMineFromSide).
// canopy logs 6-8 above the ground are past that, the only way up to them is a tower of our own blocks
public final class TreeReach {
    // GoalMineFromSide.MAX_UNDER, repeated so this stays free of the construction package: feet this far under the log still swing
    public static final int MAX_UNDER = 5;
    // no floor in the column (lava, or the scan ran out before it found one)
    public static final int NO_FEET = Integer.MIN_VALUE;
    // how far down the column we look for somewhere to stand
    public static final int SCAN_DOWN = 24;
    // how many blocks of dirt the tower costs when we do not know the column, and the most we ever go and dig for
    private static final int DIRT_MIN = 4;
    private static final int DIRT_MAX = 10;
    private static final int DIRT_UNKNOWN = 8;

    private TreeReach() {
    }

    // what is in a cell of the column, as far as standing in it goes
    public enum Cell {
        // isAir: the only thing DestroyBlockTask.openBelow counts as open
        AIR,
        // something to stand on top of (stone, leaves, slabs...)
        SOLID,
        // not air and not in the way: grass, ferns, snow layers. we stand in it, and it is not open for the shaft count
        PASSABLE,
        WATER,
        LAVA
    }

    // feetY: where we would stand under the log, airCells: the run of plain air right under it (what openBelow counts)
    public record Column(int feetY, int airCells) {
    }

    // walks down from the log. cellAt gets the y of the cell. same count as DestroyBlockTask.openBelow, and the cell it stops
    // on is where the feet go: on top of a solid one, in a passable one (grass), in the top of a pond. lava is no place
    public static Column column(int logY, IntFunction<Cell> cellAt) {
        int air = 0;
        for (int y = logY - 1; y >= logY - 1 - SCAN_DOWN; y--) {
            Cell cell = cellAt.apply(y);
            if (cell == Cell.AIR) {
                air++;
                continue;
            }
            switch (cell) {
                case SOLID:
                    return new Column(y + 1, air);
                case LAVA:
                    return new Column(NO_FEET, air);
                default:
                    return new Column(y, air);
            }
        }
        return new Column(NO_FEET, air);
    }

    // GoalMineFromSide's own clamp on how far down the shaft goes
    public static int openBelow(int airCells) {
        return Math.max(2, Math.min(airCells, MAX_UNDER));
    }

    // can the ground alone get us a swing at it. the shaft: feet on the floor with the log at most openBelow over them (and
    // two or more, one is the log in our face and that is the side case). sideStandable is "some neighbouring column has a
    // floor within a step of the log's height", the sides of GoalMineFromSide
    public static boolean fromGround(int logY, Column column, boolean sideStandable) {
        if (sideStandable) {
            return true;
        }
        if (column.feetY() == NO_FEET) {
            return false;
        }
        int under = logY - column.feetY();
        // within a step of the log's own height is the sides' business, there is nothing to tower
        return under <= 1 || under <= openBelow(column.airCells());
    }

    // cells between the floor we would stand on and the log, for the size of the tower
    public static int gap(int logY, Column column) {
        return column.feetY() == NO_FEET ? Integer.MAX_VALUE : Math.max(0, logY - column.feetY());
    }

    // leave this log out of the pick. only logs that are flagged and cannot be had from the ground are ever in question (an
    // intact trunk opens up as it is cut from the bottom), and a tower or a trip for dirt makes them fair again
    public static boolean skip(boolean flagged, boolean fromGround, boolean hasThrowaway, boolean dirtPossible) {
        return flagged && !fromGround && !hasThrowaway && !dirtPossible;
    }

    // blocks of dirt for the tower: one per cell between the floor and the log, a couple spare for a miss
    public static int dirtWanted(int gap) {
        if (gap == Integer.MAX_VALUE) {
            return DIRT_UNKNOWN;
        }
        return Math.max(DIRT_MIN, Math.min(DIRT_MAX, gap + 1));
    }

    // how many more to dig. one block in the bag does not make a tower: the planner only asks if there is any, and then
    // runs out half way up
    public static int dirtShortfall(int gap, int held) {
        return Math.max(0, dirtWanted(gap) - held);
    }
}
