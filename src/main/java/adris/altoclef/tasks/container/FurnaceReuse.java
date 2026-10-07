package adris.altoclef.tasks.container;

import adris.altoclef.util.helpers.WalkCost;

// walk to the furnace we remember, or just put a new one down. pure so the numbers can be tested. the smelt task used to
// answer "never make a new one" whenever any furnace was known (see SmeltInFurnaceTask.getCostToMakeNew), which is how the
// bot once walked ~300 blocks to a furnace it placed an hour ago while holding the cobble for a fresh one. the walk is
// priced by WalkCost, the same budget the table pickup uses
public final class FurnaceReuse {
    // furnace is 8 cobble
    public static final int FURNACE_COBBLE = 8;

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

    // true = put a new one down. false = go to the remembered one. no remembered furnace is always a new one, that was
    // already how it worked
    public static boolean makeNew(boolean haveRemembered, boolean canMakeCheaply, double dx, double dy, double dz) {
        if (!haveRemembered) {
            return true;
        }
        return canMakeCheaply && !cheapToReach(dx, dy, dz);
    }
}
