package adris.altoclef.tasks.speedrun.gamer.end;

// can we afford to click the bed. a bed on the head costs ~26 unarmored at two blocks and ~10 in full iron (gamer-design 0.6),
// so a character at 8 hp after a breath tick dies to its own explosion. pure so the task, the strat and the tests agree
public final class BedSafety {
    private static final double UNARMORED_DAMAGE = 26;
    // 26 -> 10 over the 15 points of full iron, so 16/15 saved per armor point
    private static final double SAVED_FOR_FULL_IRON = 16;
    private static final double FULL_IRON_POINTS = 15;
    // diamond armor would say ~5, a bed through a gap in the blocks still hurts more than that
    private static final double MIN_DAMAGE = 6;
    // breath and a dragon swipe can land between the click and the explosion
    private static final double MARGIN = 4;
    // below this a swap to the sword is not safer either, the regen wait is better than a coin flip with melee
    public static final double CRITICAL_POOL = 6;

    private BedSafety() {
    }

    public static double expectedSelfDamage(int armorPoints) {
        // multiply first so full iron lands on exactly 10 and not 10.000000000000004
        return Math.max(MIN_DAMAGE, UNARMORED_DAMAGE - SAVED_FOR_FULL_IRON * Math.max(0, armorPoints) / FULL_IRON_POINTS);
    }

    // health + absorption we want before the click
    public static double safePool(int armorPoints) {
        return expectedSelfDamage(armorPoints) + MARGIN;
    }

    public static boolean canClick(double health, double absorption, int armorPoints) {
        return health + absorption >= safePool(armorPoints);
    }
}
