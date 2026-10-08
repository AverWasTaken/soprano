package adris.altoclef.util.helpers;

// a rough "how long is the walk" for deciding if going back to a placed station is worth it. a crafting table costs about
// one log, so a trip that takes longer than fetching a new one is a loss. pure so it can be tested without a game
public final class WalkCost {
    // a block of height costs this many blocks of flat walking. down a cave you are digging, jumping and then climbing
    // back out, and the old straight line sphere called a 30 block drop "24 blocks away" (that was the cave trip)
    public static final double VERTICAL_WEIGHT = 4.0;
    // the longest trip that still beats placing a fresh table (or a log's worth of wood). about 20 flat blocks
    public static final double STATION_BUDGET = 20.0;

    // a dropped item is worth this much walking when we could make it right now from what we hold. a tree decayed on the
    // surface, dropped sticks, and the bot climbed out of its hole for them with the planks for the same sticks in the bag
    public static final double DROP_BUDGET_CRAFTABLE = 8.0;
    // and when we cannot make it, still not "anywhere the tracker has ever seen one". about 32 flat blocks
    public static final double DROP_BUDGET = 32.0;

    private WalkCost() {
    }

    public static double dropBudget(boolean craftableNow) {
        return craftableNow ? DROP_BUDGET_CRAFTABLE : DROP_BUDGET;
    }

    // is a drop at this offset worth a walk. our own mining drops are a block or four away and always pass
    public static boolean dropWorthWalking(double dx, double dy, double dz, boolean craftableNow) {
        return within(dx, dy, dz, dropBudget(craftableNow));
    }

    // horizontal distance plus 4 per block of height, either direction. offsets are target minus us
    public static double estimate(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dz * dz) + VERTICAL_WEIGHT * Math.abs(dy);
    }

    // what CraftInTableTask charges for "make a new table" against walking to the nearest one. infinity means walk to the
    // old one. same budget with the table in hand or not: OwnTables.forgetFar lets go of a table past it, so a 40 block line
    // out here sent crafting off to a table pickup had already written off (and the planner never budgeted a new one)
    public static double newTableCost(boolean carryTable, boolean tableInBudget, boolean haveWood) {
        if (tableInBudget) {
            return Double.POSITIVE_INFINITY;
        }
        if (carryTable) {
            return 0;
        }
        return haveWood ? 10 : 100;
    }

    public static boolean within(double dx, double dy, double dz, double budget) {
        return estimate(dx, dy, dz) <= budget;
    }
}
