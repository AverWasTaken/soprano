package adris.altoclef.util.helpers;

// a lit creeper above us in a one wide shaft with no way sideways. a block in the cell over our head breaks its line to us:
// it un-swells, the blast sees us at about nothing through a block, and it can't drop down on top of us. pure, the chain does
// the actual placing
public final class CreeperCap {

    // the fuse is 30 ticks and a raised shield needs about 5 of them to block, so the cap gets this long to land before the
    // shield has to take over. the equip is one tick, the click another, the rest is slack for a refused first try
    public static final long CAP_TICKS = 12;
    // above us means this far over in y (feet to feet) and no more than this off in x/z. wider than that is a creeper beside
    // the shaft and not in it
    public static final double MIN_ABOVE = 1;
    public static final double MAX_OFF = 1.5;

    private CreeperCap() {
    }

    // noHeading = CreeperEscape found nowhere to sprint. dy = creeper feet minus ours, horizontal = how far off to the side
    public static boolean creeperAbove(boolean noHeading, double dy, double horizontal) {
        return noHeading && dy > MIN_ABOVE && horizontal < MAX_OFF;
    }

    // still inside the cap's share of the fuse. since = ticks since the step started (negative = the clock went backwards)
    public static boolean inWindow(long since) {
        return since >= 0 && since < CAP_TICKS;
    }
}
