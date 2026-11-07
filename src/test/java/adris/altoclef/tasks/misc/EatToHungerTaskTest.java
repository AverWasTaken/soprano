package adris.altoclef.tasks.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EatToHungerTaskTest {

    @Test
    public void itsDoneAtTheLine() {
        EatToHungerTask t = new EatToHungerTask(14);
        t.step(13, true);
        assertFalse(t.isFinished(null));
        t.step(14, true);
        assertTrue(t.isFinished(null));
        assertNull(t.failure());
    }

    @Test
    public void alreadyFullIsDoneEvenWithNothingToEat() {
        EatToHungerTask t = new EatToHungerTask(20);
        t.step(20, false);
        assertTrue(t.isFinished(null));
        assertNull(t.failure());
    }

    @Test
    public void noFoodFailsButNotOnTheFirstLook() {
        EatToHungerTask t = new EatToHungerTask(20);
        t.step(5, false);
        assertFalse("the food answer can be a little old", t.isFinished(null));
        for (int i = 0; i < 30; i++) {
            t.step(5, false);
        }
        assertEquals("nothing to eat", t.failure());
    }

    @Test
    public void foodTurningUpResetsTheNoFoodCount() {
        EatToHungerTask t = new EatToHungerTask(20);
        for (int i = 0; i < 15; i++) {
            t.step(5, false);
        }
        t.step(5, true);
        for (int i = 0; i < 15; i++) {
            t.step(5, false);
        }
        assertNull(t.failure());
    }

    @Test
    public void aBarThatWontMoveFails() {
        EatToHungerTask t = new EatToHungerTask(20);
        for (int i = 0; i < 700; i++) {
            t.step(5, true);
        }
        assertTrue(t.failure(), t.failure().contains("stayed at 5"));
    }

    @Test
    public void everyBiteResetsTheStallClock() {
        EatToHungerTask t = new EatToHungerTask(20);
        for (int bar = 1; bar < 10; bar++) {
            for (int i = 0; i < 400; i++) {
                t.step(bar, true);
            }
        }
        assertNull(t.failure());
        assertFalse(t.isFinished(null));
    }
}
