package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.altoclef.SettingsOverrides;
import baritone.api.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// boats are on for the eye walks and the room search only, and leaving those hands the user's value back
public class GamerTaskBoatsTest {

    private Settings s;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
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
    public void onlyTheEyeWalkAndTheRoomRow() {
        for (GamerPhase p : GamerPhase.values()) {
            assertEquals(p.name(), p == GamerPhase.LOCATE || p == GamerPhase.ROOM, GamerTask.rowsBoats(p));
        }
    }

    @Test
    public void heldThroughLocateAndRoomAndLetGoAfter() {
        s.allowBoats.value = false;
        Boolean put = GamerTask.updateBoats(s.allowBoats, GamerPhase.RETURN, null, false);
        assertFalse(s.allowBoats.value);
        assertFalse(SettingsOverrides.isHeld(s.allowBoats));
        put = GamerTask.updateBoats(s.allowBoats, GamerPhase.LOCATE, put, false);
        assertTrue(s.allowBoats.value);
        assertTrue(SettingsOverrides.isHeld(s.allowBoats));
        put = GamerTask.updateBoats(s.allowBoats, GamerPhase.ROOM, put, false);
        assertTrue(s.allowBoats.value);
        put = GamerTask.updateBoats(s.allowBoats, GamerPhase.OPEN, put, false);
        assertFalse(s.allowBoats.value);
        assertFalse(SettingsOverrides.isHeld(s.allowBoats));
        // a death in LOCATE regresses to GATHER: off again
        put = GamerTask.updateBoats(s.allowBoats, GamerPhase.LOCATE, put, false);
        GamerTask.updateBoats(s.allowBoats, GamerPhase.GATHER, put, false);
        assertFalse(s.allowBoats.value);
    }

    // the run stopping mid LOCATE (restoreAll) gives the user's off back, and a user who had it on keeps it on after ROOM
    @Test
    public void theUsersValueComesBack() {
        s.allowBoats.value = false;
        GamerTask.updateBoats(s.allowBoats, GamerPhase.LOCATE, null, false);
        SettingsOverrides.restoreAll();
        assertFalse(s.allowBoats.value);
        s.allowBoats.value = true;
        Boolean put = GamerTask.updateBoats(s.allowBoats, GamerPhase.ROOM, null, true);
        GamerTask.updateBoats(s.allowBoats, GamerPhase.END_PREP, put, true);
        assertTrue(s.allowBoats.value);
    }

    // a #set mid phase sticks: nothing is put again until the phase changes
    @Test
    public void aSetInsideThePhaseIsLeftAlone() {
        s.allowBoats.value = false;
        Boolean put = GamerTask.updateBoats(s.allowBoats, GamerPhase.LOCATE, null, false);
        s.allowBoats.value = false;
        SettingsOverrides.userChanged(s.allowBoats);
        put = GamerTask.updateBoats(s.allowBoats, GamerPhase.LOCATE, put, false);
        assertFalse(s.allowBoats.value);
    }
}
