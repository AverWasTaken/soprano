package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FollowForTaskTest {

    private final FollowForTask t = new FollowForTask("Steve", 30);

    @Test
    public void itKeepsGoingUntilTheTimeIsUp() {
        t.step(false, false, true);
        assertFalse(t.isFinished(null));
        t.step(true, false, true);
        assertTrue(t.isFinished(null));
        assertNull(t.failure());
    }

    @Test
    public void seeingThemOnceCounts() {
        t.step(false, false, true);
        t.step(true, false, false);
        assertNull(t.failure());
        assertTrue(t.isFinished(null));
    }

    @Test
    public void somebodyWhoNeverShowedUpIsAFailure() {
        t.step(false, false, false);
        assertFalse(t.isFinished(null));
        t.step(true, false, false);
        assertEquals("never saw Steve", t.failure());
    }

    @Test
    public void losingThemFailsRightAway() {
        t.step(false, true, true);
        assertEquals("lost Steve", t.failure());
        assertTrue(t.isFinished(null));
    }

    @Test
    public void losingThemBeatsTheClock() {
        t.step(true, true, true);
        assertEquals("lost Steve", t.failure());
    }
}
