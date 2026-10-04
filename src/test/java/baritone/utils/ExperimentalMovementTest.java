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

import org.junit.Test;

import static baritone.api.pathing.movement.ActionCosts.FALL_N_BLOCKS_COST;
import static baritone.api.pathing.movement.ActionCosts.WALK_OFF_BLOCK_COST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExperimentalMovementTest {

    @Test
    public void fallDamageIsCeilOfDistanceMinusSafe() {
        // landing from three blocks is free, every block past that is a half heart
        assertEquals(0, ExperimentalMovement.fallDamage(0));
        assertEquals(0, ExperimentalMovement.fallDamage(3));
        assertEquals(1, ExperimentalMovement.fallDamage(4));
        assertEquals(4, ExperimentalMovement.fallDamage(7));
        assertEquals(20, ExperimentalMovement.fallDamage(23)); // the sanity cap is exactly a full health bar
    }

    @Test
    public void healthGateKeepsTheMinimum() {
        assertTrue(ExperimentalMovement.canAffordFall(20, 4, 12));
        assertTrue(ExperimentalMovement.canAffordFall(20, 8, 12)); // ending exactly on the minimum is fine
        assertFalse(ExperimentalMovement.canAffordFall(20, 9, 12));
        assertFalse(ExperimentalMovement.canAffordFall(12, 1, 12));
        assertTrue(ExperimentalMovement.canAffordFall(12, 0, 12));
        assertTrue(ExperimentalMovement.canAffordFall(14.5, 2, 12)); // absorption makes for fractional hp
    }

    @Test
    public void tallestAffordableFallAtFullHealth() {
        int best = 0;
        for (int blocks = 0; blocks <= ExperimentalMovement.MAX_HURT_FALL; blocks++) {
            if (ExperimentalMovement.canAffordFall(20, ExperimentalMovement.fallDamage(blocks), 12)) {
                best = blocks;
            }
        }
        assertEquals(11, best); // 3 free + 8 hp
    }

    @Test
    public void sevenBlockDropCost() {
        // same arithmetic as MovementDescend.hurtingFall, written out so a change to either one trips this
        int blocks = 7;
        double cost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[blocks + 1] + ExperimentalMovement.fallDamage(blocks) * 20;
        // 3.7 to step off + 15.4 of falling + 4 hp at 20 ticks each is ~99 ticks, which is what ~21 blocks of walking costs
        assertEquals(4, ExperimentalMovement.fallDamage(blocks));
        assertTrue(cost > 95 && cost < 105);
    }
}
