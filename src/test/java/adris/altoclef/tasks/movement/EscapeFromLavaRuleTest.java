package adris.altoclef.tasks.movement;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// lava pathing is for being in lava, not for being on fire next to it
public class EscapeFromLavaRuleTest {

    @Test
    public void inLavaSwimsThroughIt() {
        assertTrue(EscapeFromLavaTask.lavaPathingAllowed(true, 0));
    }

    @Test
    public void onFireOnDryLandDoesNot() {
        // the task starts with the grace already spent when we're only burning
        assertFalse(EscapeFromLavaTask.lavaPathingAllowed(false, 10));
        assertFalse(EscapeFromLavaTask.lavaPathingAllowed(false, 1000));
    }

    @Test
    public void justOutOfItKeepsItForAMoment() {
        // the surface flickers in and out at the shore, a flip a tick would rebuild the tables every tick
        assertTrue(EscapeFromLavaTask.lavaPathingAllowed(false, 1));
        assertTrue(EscapeFromLavaTask.lavaPathingAllowed(false, 9));
    }
}
