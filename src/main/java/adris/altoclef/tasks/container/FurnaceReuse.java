package adris.altoclef.tasks.container;

import adris.altoclef.util.helpers.WalkCost;

// walk to the furnace we remember, or just put a new one down. pure so the numbers can be tested. the smelt task used to
// answer "never make a new one" whenever any furnace was known (see SmeltInFurnaceTask.getCostToMakeNew), which is how the
// bot once walked ~300 blocks to a furnace it placed an hour ago while holding the cobble for a fresh one. the walk is
// priced by WalkCost, the same budget the table pickup uses
public final class FurnaceReuse {
    // furnace is 8 cobble
    public static final int FURNACE_COBBLE = 8;
    // one we put down ourselves earns three times the walk before a fresh one wins. the bot once stood nine blocks above its
    // own furnace (which had the raw iron in it), saw 40 "blocks" of walk against a budget of 20, and put a second one on top
    // of the crafting table. the far end is still there for the one we left in the last area
    public static final double OURS_BUDGET = WalkCost.STATION_BUDGET * 3;

    private FurnaceReuse() {
    }

    public static boolean cheapToReach(double dx, double dy, double dz) {
        return WalkCost.within(dx, dy, dz, WalkCost.STATION_BUDGET);
    }

    // can a new furnace be had for next to nothing: one in the bag, or the stone for it and a table to craft it on (a table
    // we can see, one in the bag, or the wood for one). without any of that the old behaviour stays: use what is there
    public static boolean canMakeCheaply(boolean carryingFurnace, int cobbleish, boolean tableAround) {
        return carryingFurnace || (cobbleish >= FURNACE_COBBLE && tableAround);
    }

    // a smoker is a furnace and four logs on a table. one in the bag is free, otherwise we need the furnace (in the bag or its
    // cobble), the logs and somewhere to craft it
    public static boolean canMakeSmokerCheaply(boolean carryingSmoker, boolean carryingFurnace, int cobbleish, int logs, boolean tableAround) {
        return carryingSmoker || (canMakeCheaply(carryingFurnace, cobbleish, tableAround) && logs >= SMOKER_LOGS);
    }

    public static final int SMOKER_LOGS = 4;

    // how far a smoker of ours may be and still steer the cook towards it. the same stretched walk as a furnace of ours, with a
    // quarter more once we are already counting it so the need name does not flip every step at the line (a flip restarts the
    // cook task)
    public static boolean smokerWorthWalking(boolean alreadyCounted, double walkCost) {
        return walkCost <= (alreadyCounted ? OURS_BUDGET * 1.25 : OURS_BUDGET);
    }

    // true = put a new one down. false = go to the remembered one. no remembered furnace is always a new one, that was
    // already how it worked
    public static boolean makeNew(boolean haveRemembered, boolean canMakeCheaply, double dx, double dy, double dz) {
        return makeNew(haveRemembered, canMakeCheaply, dx, dy, dz, false, false);
    }

    // the same, knowing whose furnace it is. `holdsOurStuff` (we put ore in it, the screen showed it) is a furnace we are in the
    // middle of using and never loses to a new one, however far. `ours` only stretches the walk, see OURS_BUDGET
    public static boolean makeNew(boolean haveRemembered, boolean canMakeCheaply, double dx, double dy, double dz, boolean ours,
                                  boolean holdsOurStuff) {
        if (!haveRemembered) {
            return true;
        }
        if (holdsOurStuff) {
            return false;
        }
        double budget = ours ? OURS_BUDGET : WalkCost.STATION_BUDGET;
        return canMakeCheaply && !WalkCost.within(dx, dy, dz, budget);
    }
}
