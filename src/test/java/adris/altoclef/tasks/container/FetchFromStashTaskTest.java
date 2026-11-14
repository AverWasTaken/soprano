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

// which storage containers are worth opening for what's still short
public class FetchFromStashTaskTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void unopenedIsAlwaysWorthALook() {
        assertTrue(FetchFromStashTask.worthALook(false, null));
        assertTrue(FetchFromStashTask.worthALook(false, false));
    }

    @Test
    public void openedThisRunOnlyIfItHadSome() {
        assertTrue(FetchFromStashTask.worthALook(true, true));
        assertFalse(FetchFromStashTask.worthALook(true, false));
    }
}
