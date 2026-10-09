package adris.altoclef.tasks.container;

import adris.altoclef.util.helpers.WalkCost;

// walk to the furnace we remember, or just put a new one down. pure so the numbers can be tested. the smelt task used to
// answer "never make a new one" whenever any furnace was known (see SmeltInFurnaceTask.getCostToMakeNew), which is how the
// bot once walked ~300 blocks to a furnace it placed an hour ago while holding the cobble for a fresh one. the line is
// WalkCost.STATION_NEAR now, a straight line with the height counted, for ours and for a village's alike. it used to be four
// different walk costs (20, a stretched 60, an infinite one for furnaces, 75 for smokers) and they disagreed
public final class FurnaceReuse {
    // furnace is 8 cobble
    public static final int FURNACE_COBBLE = 8;

    private FurnaceReuse() {
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

    // true = put a new one down. false = go to the remembered one. no remembered furnace is always a new one, that was
    // already how it worked. `holdsOurStuff` (we put ore in it, the screen showed it): a furnace we are in the middle of using never
    // loses to a new one, however far. otherwise anything within STATION_NEAR is used (ours or not), and past it a new one wins
    // when it is cheap to have. the gamer's registry does not let a new one go down while ours is within NEAR or in the bag,
    // this is the answer for a furnace it does not know about
    public static boolean makeNew(boolean haveRemembered, boolean canMakeCheaply, double dx, double dy, double dz, boolean holdsOurStuff) {
        if (!haveRemembered) {
            return true;
        }
        if (holdsOurStuff) {
            return false;
        }
        return canMakeCheaply && !WalkCost.nearStation(dx, dy, dz);
    }
}
