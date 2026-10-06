/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

// who has a fall: baritone's MovementFall (it planned it) or altoclef's MLGBucketFallChain (it grabs any fall that looks bad).
// pure so the chain's stand down is testable without a world, same idea as LadderClutch
final class FallCover {

    // how far outside the src/dest cells we still count as "in the fall". walking off an edge drifts about this much
    private static final double SIDE = 0.3;
    // knockback is a 0.4 shove, a walk off a ledge is 0.28 at a sprint. past this it wasn't our idea
    private static final double MAX_DRIFT = 0.45;

    private FallCover() {}

    // are we where a planned fall from src to dest would have us. the executor itself is happy with the valid positions
    // (src plus the dest column) but we're mid air going diagonal off an edge, so use the box around both cells instead.
    // anything knocked out of the box, or shoved sideways hard enough, is an unplanned fall and altoclef gets it back
    static boolean inColumn(int srcX, int srcY, int srcZ, int destX, int destY, int destZ, double px, double py, double pz, double mx, double mz) {
        if (Math.hypot(mx, mz) > MAX_DRIFT) {
            return false;
        }
        if (px < Math.min(srcX, destX) - SIDE || px > Math.max(srcX, destX) + 1 + SIDE) {
            return false;
        }
        if (pz < Math.min(srcZ, destZ) - SIDE || pz > Math.max(srcZ, destZ) + 1 + SIDE) {
            return false;
        }
        // well under the landing floor means the plan is long gone, a couple above the ledge is just a jump off it
        return py >= destY - 1 && py <= srcY + 2;
    }

    // does baritone do something about this fall itself, so altoclef's mlg should keep its hands off.
    // a safe fall and a clutch are baritone's. the bucket is too when it has the bucket on the hotbar to click (it needs
    // that anyway), otherwise alto's slot handler gets it. a hurting fall, or a clutch that gave up, has nobody saving
    // us, so anything alto has (hay, a bucket from the inventory) is welcome
    static boolean handles(MovementFall.FallMode mode, boolean gaveUp, boolean bucketOnHotbar, boolean altoDoesBuckets) {
        switch (mode) {
            case NONE:
                return !gaveUp;
            case CLUTCH:
                return true;
            case BUCKET:
                return bucketOnHotbar || !altoDoesBuckets;
            default:
                return false;
        }
    }

    // the water at the end of a fall is baritone's to pick up when it's the dest cell and there's an empty bucket on the
    // hotbar to do it with (MovementFall's isWater branch), so the chain's own pickup would be a second click on the same
    // water. anything else (water somewhere else, no hotbar bucket) is still the chain's
    static boolean pickupIsBaritones(boolean placedAtDest, boolean destIsWater, boolean emptyBucketOnHotbar) {
        return placedAtDest && destIsWater && emptyBucketOnHotbar;
    }
}
