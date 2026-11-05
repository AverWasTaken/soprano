package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShedTallyTest {

    @Test
    public void theCapIsTwelveThrows() {
        ShedTally t = new ShedTally();
        for (int i = 0; i < 12; i++) {
            assertFalse(t.spent());
            t.thrown();
        }
        assertEquals(12, ShedTally.CAP);
        assertTrue(t.spent());
    }

    @Test
    public void onlyTheFirstAskGetsTheDump() {
        ShedTally t = new ShedTally();
        assertTrue(t.first());
        assertFalse(t.first());
        t.thrown();
        assertFalse(t.first());
    }

    @Test
    public void theCapSaysSoOnce() {
        ShedTally t = new ShedTally();
        assertTrue(t.sayCap());
        assertFalse(t.sayCap());
    }

    @Test
    public void stuckSaysSoOnceUntilAThrowWorksAgain() {
        ShedTally t = new ShedTally();
        assertTrue(t.sayStuck());
        assertFalse(t.sayStuck());
        t.thrown();
        assertTrue(t.sayStuck());
    }
}
