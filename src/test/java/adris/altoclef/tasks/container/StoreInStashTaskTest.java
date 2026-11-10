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

import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

// which stash containers are worth the walk. full only counts when we saw it full ourselves this run
public class StoreInStashTaskTest {

    // the task's container list names blocks, so the registries have to be up before the class loads
    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void neverOpenedIsWorthALook() {
        assertTrue(StoreInStashTask.hasRoom(false, null));
        assertTrue(StoreInStashTask.hasRoom(true, null));
    }

    @Test
    public void rememberedFullFromAnEarlierVisitGetsAnotherLook() {
        // a hopper may have emptied it since
        assertTrue(StoreInStashTask.hasRoom(false, true));
    }

    @Test
    public void seenFullThisRunIsSkipped() {
        assertFalse(StoreInStashTask.hasRoom(true, true));
    }

    @Test
    public void roomLastTimeIsWorthIt() {
        assertTrue(StoreInStashTask.hasRoom(true, false));
        assertTrue(StoreInStashTask.hasRoom(false, false));
    }
}
