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

package baritone.api;

import baritone.api.utils.SettingsUtil;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SettingsRenameTest {

    @BeforeClass
    public static void bootstrap() {
        // the default item lists in Settings need the registries
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void oldNamesFindTheNewSettings() {
        Settings settings = new Settings();
        assertSame(settings.fastMode, settings.findByLowerName("experimentalmovement"));
        assertSame(settings.fastModeMinHealth, settings.findByLowerName("experimentalminhealth"));
    }

    @Test
    public void newNamesAndStrangersStillWork() {
        Settings settings = new Settings();
        assertSame(settings.fastMode, settings.findByLowerName("fastmode"));
        assertSame(settings.fastModeMinHealth, settings.findByLowerName("fastmodeminhealth"));
        assertSame(settings.allowParkour, settings.findByLowerName("allowparkour"));
        assertNull(settings.findByLowerName("fast"));
        assertNull(settings.findByLowerName("nonsense"));
    }

    @Test
    public void oldNamesStayOutOfTheListings() {
        // tab complete and #set list walk these two, so nothing here means nothing advertised
        Settings settings = new Settings();
        assertFalse(settings.byLowerName.containsKey("experimentalmovement"));
        assertFalse(settings.byLowerName.containsKey("experimentalminhealth"));
        for (Settings.Setting<?> setting : settings.allSettings) {
            String name = setting.getName().toLowerCase();
            assertFalse(name, name.equals("experimentalmovement") || name.equals("experimentalminhealth"));
        }
    }

    @Test
    public void settingsFileLineWithTheOldNameTurnsFastModeOn() {
        // readAndApply feeds each line's lowercased key into parseAndApply, which is the part under test
        Settings settings = new Settings();
        assertFalse(settings.fastMode.value);
        SettingsUtil.parseAndApply(settings, "experimentalmovement", "true");
        SettingsUtil.parseAndApply(settings, "experimentalminhealth", "8.0");
        assertTrue(settings.fastMode.value);
        assertEquals(8.0, settings.fastModeMinHealth.value, 0);
    }

    @Test
    public void savingWritesTheNewName() {
        Settings settings = new Settings();
        SettingsUtil.parseAndApply(settings, "experimentalmovement", "true");
        assertTrue(SettingsUtil.modifiedSettings(settings).contains(settings.fastMode));
        assertEquals("fastMode true", SettingsUtil.settingToString(settings.fastMode));
    }
}
