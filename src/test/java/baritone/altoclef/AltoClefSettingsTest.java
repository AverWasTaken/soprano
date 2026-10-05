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

package baritone.altoclef;

import net.minecraft.core.BlockPos;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class AltoClefSettingsTest {

    private final AltoClefSettings s = AltoClefSettings.getInstance();

    @After
    public void clean() {
        s.getBreakAvoiders().clear();
        s.getPlaceAvoiders().clear();
        s.getBlocksToAvoidBreaking().clear();
        s.getForceWalkOnPredicates().clear();
        s.getForceAvoidWalkThroughPredicates().clear();
        s.setInteractionPaused(false);
    }

    @Test
    public void idleSnapshotIsEmpty() {
        assertFalse(s.snapshot().hasPathingRules());
        assertFalse(s.shouldAvoidBreaking(1, 2, 3));
        assertFalse(s.shouldAvoidPlacingAt(1, 2, 3));
    }

    @Test
    public void mutatingTheReturnedListsRepublishes() {
        // what BotBehaviour does: clear then addAll on the list it got from the getter
        List<Predicate<BlockPos>> mine = new ArrayList<>();
        mine.add(pos -> pos.getY() == 5);
        s.getBreakAvoiders().clear();
        s.getBreakAvoiders().addAll(mine);
        assertTrue(s.shouldAvoidBreaking(0, 5, 0));
        assertFalse(s.shouldAvoidBreaking(0, 6, 0));
        s.getBreakAvoiders().clear();
        assertFalse(s.shouldAvoidBreaking(0, 5, 0));

        s.getPlaceAvoiders().add(pos -> pos.getX() == 9);
        assertTrue(s.shouldAvoidPlacingAt(new BlockPos(9, 0, 0)));
        s.getPlaceAvoiders().remove(0);
        assertFalse(s.shouldAvoidPlacingAt(new BlockPos(9, 0, 0)));
    }

    @Test
    public void exactPositionsAndSetMutation() {
        s.avoidBlockBreak(new BlockPos(1, 2, 3));
        assertTrue(s.shouldAvoidBreaking(1, 2, 3));
        assertFalse(s.shouldAvoidBreaking(1, 2, 4));
        s.getBlocksToAvoidBreaking().clear();
        assertFalse(s.shouldAvoidBreaking(1, 2, 3));
        s.getBlocksToAvoidBreaking().add(new BlockPos(4, 4, 4));
        assertTrue(s.shouldAvoidBreaking(new BlockPos(4, 4, 4)));
    }

    @Test
    public void snapshotIsImmutableOnceTaken() {
        s.getForceWalkOnPredicates().add(pos -> true);
        AltoClefSettings.Snapshot before = s.snapshot();
        s.getForceWalkOnPredicates().clear();
        assertNotNull(before.forceWalkOn); // the search that already took it keeps its answer
        assertNull(s.snapshot().forceWalkOn);
    }

    @Test
    public void tableKeyTracksBlockToggles() {
        int idle = s.blockToggleBits();
        s.canWalkOnEndPortal(true);
        assertNotEquals(idle, s.blockToggleBits());
        s.canWalkOnEndPortal(false);
        s.allowSwimThroughLava(true);
        assertNotEquals(idle, s.blockToggleBits());
        s.allowSwimThroughLava(false);
        assertEquals(idle, s.blockToggleBits());
    }
}
