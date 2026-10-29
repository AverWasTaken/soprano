package adris.altoclef.util.helpers;

import net.minecraft.world.phys.AABB;

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
    // full: about half the damage (0.2 + 0.8 * scale squared), but it can't sweep. a sword charges 0.08 a tick and the scale reads 0.60, 0.68, 0.76, 0.84, so
    // the early window is four ticks long. at 0.8 it was one (0.84), and an aim that was late for that tick swung at 1.00 and
    // swept the piglin standing next to the blaze
    public static final double CALM_SWING_SCALE = 0.6;

    // the vanilla line: fullStrengthAttack is a scale over this
    public static final double SWEEP_SCALE = 0.9;

    // how long the sweep veto holds a full charge swing before it gives up and swings anyway, so a piglin that never leaves does
    // not freeze the fight
    public static final long SWEEP_VETO_TICKS = 20;

    // this swing would sweep: over the 0.9 line, on the ground, not a sprint hit (crits are off the table on the ground) and not
    // moving faster than 2.5x our walk speed (horizontalSpeedSq is the known movement's, speed the attribute, both as vanilla reads
    // them). the sword is the caller's business
    public static boolean wouldSweep(double scale, boolean onGround, boolean sprintHit, double horizontalSpeedSq, double speed) {
        double limit = speed * 2.5;
        return scale > SWEEP_SCALE && onGround && !sprintHit && horizontalSpeedSq < limit * limit;
    }

    // Player.doSweepAttack: everything alive in the target's box grown by (1, 0.25, 1) and within 3 of us (squared, under 9)
    public static boolean inSweepBox(AABB target, AABB victim, double distanceSqToUs) {
        return target.inflate(1.0, 0.25, 1.0).intersects(victim) && distanceSqToUs < 9.0;
    }

    // hold the swing: it would sweep a calm neutral mob, and we have not held it for too long yet
    public static boolean holdSweep(boolean sweepsCalmMob, long heldTicks) {
        return sweepsCalmMob && heldTicks <= SWEEP_VETO_TICKS;
    }

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
