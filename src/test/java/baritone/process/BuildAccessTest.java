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

package baritone.process;

import net.minecraft.core.BlockPos;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class BuildAccessTest {
    private static final BlockPos INSIDE = new BlockPos(0, 1, 0);
    private static final BlockPos EXIT = new BlockPos(10, 1, 0);
    private static final BlockPos DOOR = new BlockPos(5, 1, 0);

    private static BuildAccess corridor(boolean bypass) {
        Set<BlockPos> floor = new HashSet<>();
        for (int x = 0; x <= 10; x++) {
            floor.add(new BlockPos(x, 0, 0));
            if (bypass) floor.add(new BlockPos(x, 0, 2));
        }
        if (bypass) { floor.add(new BlockPos(0,0,1)); floor.add(new BlockPos(10,0,1)); }
        return new BuildAccess(INSIDE, EXIT, 12, new BuildAccess.Terrain() {
            public boolean open(BlockPos p) { return (p.getY() == 1 || p.getY() == 2) && floor.contains(new BlockPos(p.getX(),0,p.getZ())); }
            public boolean support(BlockPos p) { return floor.contains(p); }
            public boolean futureSolid(BlockPos p) { return p.equals(DOOR); }
        });
    }

    @Test public void closeDoorFromOutsideInsteadOfSealingPlayerInside() {
        BuildAccess plan = corridor(false);
        assertTrue(plan.known());
        assertFalse(plan.safe(INSIDE, DOOR));
        assertTrue(plan.safe(new BlockPos(7,1,0), DOOR));
        assertTrue(plan.stances(DOOR, 4).stream().allMatch(p -> p.getX() > 5));
        // The head half of a doorway is just as capable of sealing the walking route.
        assertFalse(plan.safe(INSIDE, DOOR.above()));
    }

    @Test public void alternateRouteMakesTheSameClosureSafe() {
        BuildAccess plan = corridor(true);
        assertTrue(plan.safe(INSIDE, DOOR));
        assertTrue(plan.preservesWork(DOOR, List.of(INSIDE), 3));
    }

    @Test public void deepRepairsMustHappenBeforeTheAccessDoorIsSealed() {
        BuildAccess plan = corridor(false);
        assertFalse(plan.preservesWork(DOOR, List.of(INSIDE, DOOR), 3));
        assertTrue(plan.preservesWork(DOOR, List.of(DOOR), 3));
        // Backfill below the walking surface adds support, without deleting any walking node.
        assertTrue(plan.safe(INSIDE, new BlockPos(5,0,0)));
    }

    @Test public void bridgeCleanupMustRetreatInsteadOfRemovingTheWayOut() {
        BuildAccess plan = corridor(false);
        BlockPos support = DOOR.below();
        assertFalse(plan.removable(INSIDE, support, List.of(), 3));
        assertTrue(plan.removable(new BlockPos(7,1,0), support, List.of(), 3));
        assertFalse(plan.removable(new BlockPos(7,1,0), support, List.of(INSIDE.below()), 3));
        assertTrue(corridor(true).removable(INSIDE, support, List.of(INSIDE.below()), 3));
    }

    @Test public void fillingStepHeadroomClosesTheRouteEvenWithoutOccupyingEitherStance() {
        Set<BlockPos> floor = Set.of(new BlockPos(0,0,0), new BlockPos(1,1,0));
        Set<BlockPos> air = Set.of(new BlockPos(0,1,0), new BlockPos(0,2,0), new BlockPos(0,3,0),
                new BlockPos(1,2,0), new BlockPos(1,3,0));
        BlockPos low = new BlockPos(0,1,0), high = new BlockPos(1,2,0);
        BuildAccess plan = new BuildAccess(low, high, 3, new BuildAccess.Terrain() {
            public boolean open(BlockPos p) { return air.contains(p); }
            public boolean support(BlockPos p) { return floor.contains(p); }
            public boolean futureSolid(BlockPos p) { return false; }
        });
        assertTrue(plan.known());
        assertFalse(plan.safe(low, low.above(2)));
        assertTrue(plan.safe(high, low.above(2)));
    }

    @Test public void noInventedExitWhenTerrainIsUnknown() {
        BuildAccess plan = new BuildAccess(INSIDE, null, 3, new BuildAccess.Terrain() {
            public boolean open(BlockPos p) { return false; }
            public boolean support(BlockPos p) { return false; }
            public boolean futureSolid(BlockPos p) { return false; }
        });
        assertFalse(plan.known());
        assertFalse(plan.safe(INSIDE, DOOR));
        assertTrue(plan.stances(DOOR, 4).isEmpty());
    }
}
