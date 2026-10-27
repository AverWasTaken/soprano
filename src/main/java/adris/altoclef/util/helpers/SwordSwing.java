package adris.altoclef.util.helpers;

// the sword's swing rules, pure so they can be tested without a player. a sword does not crit hop: it waits for the charge
// and swings, and the swing is a sweep (hits everything beside the target too) when we are charged, not a crit and not
// sprinting. approach sprints, so sprint gets locked off shortly before the charge is full. next to a calm neutral mob the
// sweep is the thing to dodge, so there the swing goes out early, at a charge too low to sweep
public final class SwordSwing {
    private SwordSwing() {
    }

    // sprint goes off this many ticks before the charge is full, so the server has seen the stop by the swing tick
    public static final double LOCK_LEAD_TICKS = 2;

    // keep sprint off for this tick: a sword in hand, the target in reach, the charge about to be full (or already is). a calm
    // neutral mob beside the target would eat the sweep, and a charged sprint hit is the only swing that can't sweep
    public static boolean lockSprint(boolean sword, boolean inReach, double ticksToFull, boolean calmNeutralNearTarget) {
        return sword && inReach && ticksToFull <= LOCK_LEAD_TICKS && !calmNeutralNearTarget;
    }

    // a sweep needs a charge over 0.9. with a calm neutral mob beside the target we swing at this much instead of waiting for
    // full: about 0.71x damage, but it can't sweep. a sword charges 0.08 a tick, so called every tick the first scale at or over 0.8
    // is still under 0.9. a swing that only gets its chance after the charge is already full (a late aim) sweeps like before
    public static final double CALM_SWING_SCALE = 0.8;

    // charged enough to swing. full charge normally, the early scale when a calm neutral mob stands beside the target. the
    // crit and sprint hit exceptions to the sweep are no use here, the bot stands still and swings
    public static boolean charged(double scale, boolean calmNeutralNearTarget) {
        return scale >= 1 || (calmNeutralNearTarget && scale >= CALM_SWING_SCALE);
    }

    // swing now: charged, and if we asked for sprint off it was already asked for on an earlier tick (the stop packet goes
    // out with the tick's movement, a same tick swing would still look like a sprint hit to the server)
    public static boolean swing(boolean attackReady, boolean lockAsked, boolean lockedEarlierTick) {
        return attackReady && (!lockAsked || lockedEarlierTick);
    }
}
