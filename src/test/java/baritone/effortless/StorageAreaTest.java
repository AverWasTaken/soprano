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

package baritone.effortless;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StorageAreaTest {
    @BeforeClass public static void boot() { ItemFixture.boot(); }
    private StorageArea area(int x) { return new StorageArea(new BlockPos(x,64,0),new BlockPos(x+2,65,2),StorageArea.Role.BOTH); }
    @Test public void mergedAreasNeverIncludeTheGapBetweenThem() {
        StorageArea one=area(0), two=area(20);
        StorageArea merged=one.merge(two);
        assertEquals(one.id(),merged.id());
        assertEquals(2,merged.ranges(Dimension.OVERWORLD).size());
        assertTrue(merged.ranges(Dimension.OVERWORLD).stream().anyMatch(r->r.contains(new BlockPos(21,64,1),Dimension.OVERWORLD)));
        assertFalse(merged.ranges(Dimension.OVERWORLD).stream().anyMatch(r->r.contains(new BlockPos(10,64,1),Dimension.OVERWORLD)));
    }
    @Test public void itemRulesApplyToBlocksAndNonBlockItems() {
        StorageArea s=area(0).edited("Supplies",StorageArea.Role.OUTPUT,StorageArea.Match.ONLY,"stone, diamond",area(0).a(),area(0).b());
        assertTrue(s.accepts(Items.STONE)); assertTrue(s.accepts(Items.DIAMOND)); assertFalse(s.accepts(Items.DIRT));
        StorageArea except=s.edited("Supplies",s.role(),StorageArea.Match.EXCEPT,"stone",s.a(),s.b());
        assertFalse(except.accepts(Items.STONE)); assertTrue(except.accepts(Items.DIRT));
        assertEquals(List.of("minecraft:stone","minecraft:diamond"),s.items());
    }
    @Test public void invalidRulesCannotReplaceAWorkingStorage() {
        assertThrows(IllegalArgumentException.class,()->area(0).edited("Bad",StorageArea.Role.BOTH,StorageArea.Match.ONLY,"not_an_item",BlockPos.ZERO,BlockPos.ZERO));
        assertThrows(IllegalArgumentException.class,()->area(0).edited("Bad",StorageArea.Role.BOTH,StorageArea.Match.ONLY,"",BlockPos.ZERO,BlockPos.ZERO));
        assertThrows(IllegalArgumentException.class,()->area(0).merge(area(20).withRole(StorageArea.Role.INPUT)));
    }
    @Test public void namesRulesPriorityAndEveryMergedAreaSurviveReload() {
        JobQueue q=new JobQueue();
        StorageArea a=area(0).merge(area(20)).edited("Wood",StorageArea.Role.INPUT,StorageArea.Match.EXCEPT,"stone",area(0).a(),area(0).b());
        q.storage().add(a); q.storage().add(area(40));
        Collections.swap(q.storage(),0,1);
        JobQueue back=JobStore.fromJson(JobStore.toJson(q));
        assertEquals(q.storage(),back.storage());
        assertEquals("Wood",back.storage().get(1).name());
        assertEquals(2,back.storage().get(1).boxes().size());
        assertEquals(a.id(),back.storage().get(1).id());
    }
    @Test public void oldSavedStorageKeepsWorkingWithoutNewFields() {
        JobQueue back=JobStore.fromJson("{\"entries\":[],\"storage\":[{\"a\":[1,64,1],\"b\":[2,65,2],\"role\":\"BOTH\"}]}");
        assertEquals(1,back.storage().size()); assertTrue(back.storage().get(0).accepts(Items.DIRT));
        assertNotNull(back.storage().get(0).id());
    }
}
