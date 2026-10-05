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

import baritone.api.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the registry every temporary value goes through. the whole point is that settings.txt only ever sees the user's own
public class SettingsOverridesTest {

    private Settings s;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void fresh() throws Exception {
        Constructor<Settings> ctor = Settings.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        s = ctor.newInstance();
        SettingsOverrides.restoreAll();
    }

    @After
    public void clean() {
        SettingsOverrides.restoreAll();
    }

    @Test
    public void restoreGivesTheUsersValueBack() {
        s.allowParkour.value = true;
        SettingsOverrides.put(s.allowParkour, false);
        assertFalse(s.allowParkour.value);
        SettingsOverrides.restoreAll();
        assertTrue(s.allowParkour.value);
        assertEquals(0, SettingsOverrides.heldCount());
    }

    @Test
    public void aSecondPutKeepsTheFirstUsersValue() {
        s.blockPlacementPenalty.value = 7.0;
        SettingsOverrides.put(s.blockPlacementPenalty, 0.0);
        SettingsOverrides.put(s.blockPlacementPenalty, Double.POSITIVE_INFINITY);
        assertEquals(Double.POSITIVE_INFINITY, s.blockPlacementPenalty.value, 0);
        SettingsOverrides.restoreAll();
        assertEquals(7.0, s.blockPlacementPenalty.value, 0);
    }

    @Test
    public void puttingTheUsersValueBackLetsGo() {
        s.followOffsetDistance.value = 3.0;
        SettingsOverrides.put(s.followOffsetDistance, 9.0);
        SettingsOverrides.put(s.followOffsetDistance, 3.0);
        assertFalse(SettingsOverrides.isHeld(s.followOffsetDistance));
        assertEquals(3.0, s.followOffsetDistance.value, 0);
    }

    @Test
    public void aSettingAlreadyAtOurValueIsNotHeld() {
        s.allowParkour.value = false;
        SettingsOverrides.put(s.allowParkour, false);
        assertEquals(0, SettingsOverrides.heldCount());
    }

    // the old Held compared with ==, and Boolean.TRUE is Boolean.TRUE, so the user's own `#set` looked like ours
    @Test
    public void anExplicitSetToTheSameValueStillSticks() {
        s.allowInventory.value = false;
        SettingsOverrides.put(s.allowInventory, true);
        // #set allowInventory true, mid task
        s.allowInventory.value = true;
        SettingsOverrides.userChanged(s.allowInventory);
        SettingsOverrides.restoreAll();
        assertTrue(s.allowInventory.value);
    }

    @Test
    public void anExplicitSetToAnotherValueSticksWithoutTheMarkerToo() {
        s.blockBreakAdditionalPenalty.value = 2.0;
        SettingsOverrides.put(s.blockBreakAdditionalPenalty, 0.0);
        s.blockBreakAdditionalPenalty.value = 5.0;
        SettingsOverrides.restoreAll();
        assertEquals(5.0, s.blockBreakAdditionalPenalty.value, 0);
    }

    @Test
    public void equalsNotIdentityForBoxedValues() {
        // a Double that is equal but not the same object has to count as still ours
        s.blockPlacementPenalty.value = 20.0;
        SettingsOverrides.put(s.blockPlacementPenalty, 1000.0);
        s.blockPlacementPenalty.value = Double.valueOf(500.0 * 2);
        SettingsOverrides.restoreAll();
        // still ours by value, so it went back
        assertEquals(20.0, s.blockPlacementPenalty.value, 0);
    }

    @Test
    public void saveSeesTheUsersValuesAndOursComeBack() {
        s.blockPlacementPenalty.value = 20.0;
        s.allowParkour.value = true;
        SettingsOverrides.put(s.blockPlacementPenalty, Double.POSITIVE_INFINITY);
        SettingsOverrides.put(s.allowParkour, false);
        List<Object> seen = new ArrayList<>();
        SettingsOverrides.saveWithoutOverrides(() -> {
            seen.add(s.blockPlacementPenalty.value);
            seen.add(s.allowParkour.value);
        });
        assertEquals(20.0, (double) seen.get(0), 0);
        assertEquals(true, seen.get(1));
        assertEquals(Double.POSITIVE_INFINITY, s.blockPlacementPenalty.value, 0);
        assertFalse(s.allowParkour.value);
    }

    @Test
    public void oursComeBackEvenWhenTheSaveThrows() {
        s.allowParkour.value = true;
        SettingsOverrides.put(s.allowParkour, false);
        try {
            SettingsOverrides.saveWithoutOverrides(() -> {
                throw new IllegalStateException("disk full");
            });
            fail();
        } catch (IllegalStateException expected) {
            // fine
        }
        assertFalse(s.allowParkour.value);
        SettingsOverrides.restoreAll();
        assertTrue(s.allowParkour.value);
    }

    @Test
    public void aSaveDoesNotTouchAValueTheUserChangedMidTask() {
        s.allowParkour.value = true;
        SettingsOverrides.put(s.allowParkour, false);
        s.allowParkour.value = true;
        SettingsOverrides.userChanged(s.allowParkour);
        List<Object> seen = new ArrayList<>();
        SettingsOverrides.saveWithoutOverrides(() -> seen.add(s.allowParkour.value));
        assertEquals(true, seen.get(0));
        assertTrue(s.allowParkour.value);
    }

    @Test
    public void vanillaOptionsOnlySwapOnTheOptionsSave() {
        boolean[] pause = {true};
        SettingsOverrides.put("options.pauseOnLostFocus", () -> pause[0], v -> pause[0] = v, false);
        assertFalse(pause[0]);

        // a settings.txt save must not touch it
        List<Object> seen = new ArrayList<>();
        SettingsOverrides.saveWithoutOverrides(() -> seen.add(pause[0]));
        assertEquals(false, seen.get(0));

        // options.txt does
        SettingsOverrides.beginOptionsSave();
        assertTrue(pause[0]);
        SettingsOverrides.endOptionsSave();
        assertFalse(pause[0]);

        SettingsOverrides.restoreAll();
        assertTrue(pause[0]);
    }

    @Test
    public void userChangedAllLetsGoOfEverything() {
        s.allowParkour.value = true;
        s.allowInventory.value = false;
        SettingsOverrides.put(s.allowParkour, false);
        SettingsOverrides.put(s.allowInventory, true);
        SettingsOverrides.userChangedAll();
        SettingsOverrides.restoreAll();
        assertFalse(s.allowParkour.value);
        assertTrue(s.allowInventory.value);
    }
}
