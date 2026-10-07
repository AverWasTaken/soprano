package adris.altoclef.tasks.container;

// walk to the furnace we remember, or just put a new one down. pure so the numbers can be tested. the smelt task used to
// answer "never make a new one" whenever any furnace was known (see SmeltInFurnaceTask.getCostToMakeNew), which is how the
// bot once walked ~300 blocks to a furnace it placed an hour ago while holding the cobble for a fresh one
public final class FurnaceReuse {
    // a remembered furnace is worth walking to when horizontal distance plus this times the vertical gap is within REACH.
    // vertical is dear (digging or pillaring a block costs a lot more than walking one)
    public static final double VERTICAL_WEIGHT = 4;
    public static final double REACH = 24;
    // furnace is 8 cobble
    public static final int FURNACE_COBBLE = 8;

    private FurnaceReuse() {
    }

    public static double walkCost(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dz * dz) + VERTICAL_WEIGHT * Math.abs(dy);
    }

    public static boolean cheapToReach(double dx, double dy, double dz) {
        return walkCost(dx, dy, dz) <= REACH;
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
