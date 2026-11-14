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

package adris.altoclef.tasks.container;

import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import baritone.effortless.StorageArea;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StorageRouteTaskTest {
    @BeforeClass public static void boot() { ItemFixture.boot(); }
    private StorageArea area(int x,StorageArea.Role role) { return new StorageArea(new BlockPos(x,64,0),new BlockPos(x+2,65,2),role); }
    @Test public void routesFollowPriorityAndSkipTheWrongRole() {
        StorageArea high=area(20,StorageArea.Role.OUTPUT).merge(area(40,StorageArea.Role.OUTPUT));
        StorageArea input=area(0,StorageArea.Role.INPUT), low=area(60,StorageArea.Role.BOTH);
        StorageRouteTask out=new StorageRouteTask(List.of(high,input,low),Dimension.OVERWORLD,Map.of(),true);
        assertEquals(List.of(20,40,60),out.ranges().stream().map(p->p.start.getX()).toList());
        StorageRouteTask in=new StorageRouteTask(List.of(high,input,low),Dimension.OVERWORLD,Map.of(Items.STONE,8),false);
        assertEquals(List.of(0,60),in.ranges().stream().map(p->p.start.getX()).toList());
    }
    @Test public void aStorageNeverReceivesItemsOutsideItsRules() {
        StorageArea only=area(0,StorageArea.Role.BOTH).edited("Stone",StorageArea.Role.BOTH,StorageArea.Match.ONLY,"stone",BlockPos.ZERO,BlockPos.ZERO);
        ItemTarget[] result=StorageRouteTask.filtered(only,new ItemTarget[]{new ItemTarget(Items.STONE,8),new ItemTarget(Items.DIAMOND,3)});
        assertEquals(1,result.length); assertArrayEquals(new net.minecraft.world.item.Item[]{Items.STONE},result[0].getMatches());
        assertEquals(8,result[0].getTargetCount());
    }
}
