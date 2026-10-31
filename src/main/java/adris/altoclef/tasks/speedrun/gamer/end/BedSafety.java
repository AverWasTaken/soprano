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

    // the floor bed goes off a good 4.5 blocks from us with nothing in between, ~27 raw. canClick's numbers were for
    // standing under the pillar top, so the click also asks this with the real distance: full iron still eats ~24 of it
    public static boolean survives(double health, double absorption, double rawSelfDamage, int difficulty, double armor, double toughness) {
        return health + absorption >= afterArmor(scaled(rawSelfDamage, difficulty), armor, toughness) + MARGIN;
    }

    // bed damage scales with difficulty for us (bad_respawn_point is "always"). 0 peaceful .. 3 hard, like Difficulty.getId
    public static double scaled(double raw, int difficulty) {
        return switch (difficulty) {
            case 0 -> 0;
            case 1 -> Math.min(raw / 2 + 1, raw);
            case 3 -> raw * 1.5;
            default -> raw;
        };
    }

    // CombatRules.getDamageAfterAbsorb without the enchants: protection only ever makes this smaller, so it is the safe guess
    public static double afterArmor(double damage, double armor, double toughness) {
        double f = 2 + toughness / 4;
        double kept = Math.max(armor * 0.2, Math.min(armor - damage / f, 20));
        return damage * (1 - kept / 25);
    }
}
