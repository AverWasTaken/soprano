package adris.altoclef.util.helpers;

// the sword's swing rules, pure so they can be tested without a player. a sword does not crit hop: it waits for the charge
// and swings, and the swing is a sweep (hits everything beside the target too) when we are charged, not a crit and not
// sprinting. approach sprints, so sprint gets locked off shortly before the charge is full
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

    // swing now: charged, and if we asked for sprint off it was already asked for on an earlier tick (the stop packet goes
    // out with the tick's movement, a same tick swing would still look like a sprint hit to the server)
    public static boolean swing(boolean attackReady, boolean lockAsked, boolean lockedEarlierTick) {
        return attackReady && (!lockAsked || lockedEarlierTick);
    }
}
