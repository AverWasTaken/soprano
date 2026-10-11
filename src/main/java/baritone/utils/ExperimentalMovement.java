/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;

// fastMode is a preset, not a setting rewrite: the real settings never change, so switching it off hands the
// user their own choices back. everything that has an opinion about one of these reads it through here and nowhere else
// (the planner snapshots them into CalculationContext, the executor asks directly), so there's exactly one list to keep honest
public final class ExperimentalMovement {

    // vanilla safe fall distance, and a sanity bound so nobody plans a leap off the nether roof (23 is exactly 20 hp of it)
    public static final int SAFE_FALL = 3;
    public static final int MAX_HURT_FALL = 23;

    private ExperimentalMovement() {}

    public static boolean on() {
        return Baritone.settings().fastMode.value;
    }

    public static boolean allowParkour() {
        return on() || Baritone.settings().allowParkour.value;
    }

    public static boolean allowParkourAscend() {
        return on() || Baritone.settings().allowParkourAscend.value;
    }

    public static boolean allowNeos() {
        return on() || Baritone.settings().allowNeos.value;
    }

    public static boolean allowClimbJumps() {
        return on() || Baritone.settings().allowClimbJumps.value;
    }

    public static boolean allowMomentumJumps() {
        return on() || Baritone.settings().allowMomentumJumps.value;
    }

    public static boolean allowDiagonalAscend() {
        return on() || Baritone.settings().allowDiagonalAscend.value;
    }

    public static boolean allowDiagonalDescend() {
        return on() || Baritone.settings().allowDiagonalDescend.value;
    }

    public static boolean sprintJumping() {
        return on() || Baritone.settings().sprintJumping.value;
    }

    public static boolean headHitters() {
        return on() || Baritone.settings().headHitters.value;
    }

    public static boolean allowGroundShortcuts() {
        return on() || Baritone.settings().allowGroundShortcuts.value;
    }

    public static boolean preferFasterPathing() {
        return on() || Baritone.settings().preferFasterPathing.value;
    }

    // min so somebody who already set a cheaper penalty keeps it
    public static double blockPlacementPenalty() {
        double penalty = Baritone.settings().blockPlacementPenalty.value;
        return on() ? Math.min(penalty, Baritone.settings().experimentalBlockPlacementPenalty.value) : penalty;
    }

    public static double jumpBias() {
        return on() ? Baritone.settings().experimentalJumpBias.value : 1;
    }

    // straight out of LivingEntity.calculateFallDamage: ceil(fallDistance - safeFallDistance), before armor and feather
    // falling, which only ever make it smaller. so the number is a ceiling on what we'll lose and that's the right direction to be wrong in
    public static int fallDamage(int blocksFallen) {
        return Math.max(0, blocksFallen - SAFE_FALL);
    }

    // the gate every damage fall goes through, in the planner and again when the movement starts
    public static boolean canAffordFall(double health, double damage, double minHealth) {
        return health - damage >= minHealth;
    }

    // a ladder clutch is a fall that only doesn't hurt if the timing works out. below the same floor a miss is the whole fall
    // at once, usually a funeral, so it's not offered at all there. the water bucket is not a gamble and has no gate
    public static boolean canAffordClutch(double health, double minHealth) {
        return canAffordFall(health, 0, minHealth);
    }
}
