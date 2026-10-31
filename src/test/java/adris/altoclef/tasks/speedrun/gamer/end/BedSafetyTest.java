package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the floor bed's real cost to us, against the armor we wear
public class BedSafetyTest {

    @Test
    public void armorFollowsCombatRules() {
        // no armor, all of it
        assertEquals(27, BedSafety.afterArmor(27, 0, 0), 1e-9);
        // full iron: 15 - 27/2 = 1.5 is under the 20% floor (3), so 12% off
        assertEquals(27 * 0.88, BedSafety.afterArmor(27, 15, 0), 1e-9);
        // full diamond: 20 - 27/4 = 13.25
        assertEquals(27 * (1 - 13.25 / 25), BedSafety.afterArmor(27, 20, 8), 1e-9);
    }

    @Test
    public void difficultyScalesTheBed() {
        assertEquals(0, BedSafety.scaled(27, 0), 1e-9);
        assertEquals(14.5, BedSafety.scaled(27, 1), 1e-9);
        assertEquals(27, BedSafety.scaled(27, 2), 1e-9);
        assertEquals(40.5, BedSafety.scaled(27, 3), 1e-9);
        // easy never makes a tiny hit bigger
        assertEquals(1.5, BedSafety.scaled(1.5, 1), 1e-9);
    }

    // a 4.5 block floor bed is ~27 raw on normal: full iron at full health does not live through it, diamond does
    @Test
    public void fullIronDoesNotSurviveTheFloorBed() {
        assertFalse(BedSafety.survives(20, 0, 27, 2, 15, 0));
        assertTrue(BedSafety.survives(20, 0, 27, 2, 20, 8));
        assertTrue(BedSafety.survives(20, 8, 27, 2, 15, 0));
    }
}
