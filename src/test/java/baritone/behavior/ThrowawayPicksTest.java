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

package baritone.behavior;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.*;

public class ThrowawayPicksTest {

    private static final List<String> BAG = Arrays.asList("cobble", "dirt", "netherrack");

    @Test
    public void respectingProtectionDropsProtectedOnes() {
        // what backfill and everything else that isn't mid movement gets: altoclef's stash is off limits
        List<String> out = ThrowawayPicks.order(BAG, "cobble"::equals, false);
        assertEquals(Arrays.asList("dirt", "netherrack"), out);
    }

    @Test
    public void movementScopeKeepsProtectedButLast() {
        // cobble is protected: the path can use it, but only after the stuff nobody was saving
        List<String> out = ThrowawayPicks.order(BAG, "cobble"::equals, true);
        assertEquals(Arrays.asList("dirt", "netherrack", "cobble"), out);
    }

    @Test
    public void everythingProtectedStillPlacesInMovementScope() {
        // the actual bug: all protected meant "no throwaway", so the planner and the executor both gave up
        assertTrue(ThrowawayPicks.order(BAG, x -> true, false).isEmpty());
        assertEquals(BAG, ThrowawayPicks.order(BAG, x -> true, true));
    }

    @Test
    public void nothingProtectedIsUntouched() {
        assertEquals(BAG, ThrowawayPicks.order(BAG, x -> false, true));
        assertEquals(BAG, ThrowawayPicks.order(BAG, x -> false, false));
    }

    private static boolean[] empties(int... emptySlots) {
        boolean[] e = new boolean[9];
        for (int i : emptySlots) {
            e[i] = true;
        }
        return e;
    }

    @Test
    public void tempSlotNeverStompsSelectedThrowawayOrPending() {
        // every temp slot empty except we hold slot 3, the block is in 5, and a swap into 6 is queued
        for (int roll = 0; roll < 7; roll++) {
            int r = roll;
            OptionalInt slot = ThrowawayPicks.tempHotbarSlot(empties(1, 2, 3, 4, 5, 6, 7), i -> false, 3, 5, 6, n -> r % n);
            assertTrue(slot.isPresent());
            assertFalse(Set.of(3, 5, 6, 0, 8).contains(slot.getAsInt()));
        }
    }

    @Test
    public void tempSlotNeverPicksAnythingButOneToSeven() {
        Set<Integer> seen = new HashSet<>();
        for (int roll = 0; roll < 20; roll++) {
            int r = roll;
            seen.add(ThrowawayPicks.tempHotbarSlot(empties(0, 1, 2, 3, 4, 5, 6, 7, 8), i -> false, -1, -1, -1, n -> r % n).getAsInt());
        }
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7)), seen);
    }

    @Test
    public void tempSlotPrefersEmptyOnes() {
        // only 2 is empty, but 1 and 4 are the ones in hand and holding the block, so 2 it is regardless of the roll
        for (int roll = 0; roll < 6; roll++) {
            int r = roll;
            assertEquals(2, ThrowawayPicks.tempHotbarSlot(empties(2), i -> false, 1, 4, -1, n -> r % n).getAsInt());
        }
    }

    @Test
    public void tempSlotFallsBackToOccupiedWhenNothingIsEmpty() {
        // a full hotbar still has to find somewhere, as long as it isn't one of the protected slots
        for (int roll = 0; roll < 5; roll++) {
            int r = roll;
            int slot = ThrowawayPicks.tempHotbarSlot(empties(), i -> false, 1, 4, -1, n -> r % n).getAsInt();
            assertNotEquals(1, slot);
            assertNotEquals(4, slot);
        }
    }

    @Test
    public void tempSlotHonorsCallerDisallowAndGivesUpWhenBoxedIn() {
        assertEquals(OptionalInt.empty(), ThrowawayPicks.tempHotbarSlot(empties(1, 2, 3, 4, 5, 6, 7), i -> true, -1, -1, -1, n -> 0));
        // 7 slots, one in hand, one holding the block, five more the caller says no to -> nothing left
        assertEquals(OptionalInt.empty(), ThrowawayPicks.tempHotbarSlot(empties(), i -> i > 2, 1, 2, -1, n -> 0));
    }

    @Test
    public void failureReasonsComeOutInOrder() {
        assertEquals(ThrowawayPicks.Failure.PAUSED, ThrowawayPicks.classify(true, true, true, true, true));
        assertEquals(ThrowawayPicks.Failure.AVOIDED, ThrowawayPicks.classify(false, true, false, false, false));
        assertEquals(ThrowawayPicks.Failure.NONE_IN_INVENTORY, ThrowawayPicks.classify(false, false, false, false, false));
        assertEquals(ThrowawayPicks.Failure.ALL_PROTECTED, ThrowawayPicks.classify(false, false, true, false, false));
        assertEquals(ThrowawayPicks.Failure.NOT_ON_HOTBAR, ThrowawayPicks.classify(false, false, true, true, false));
        assertEquals(ThrowawayPicks.Failure.NO_MATCH, ThrowawayPicks.classify(false, false, true, true, true));
    }
}
