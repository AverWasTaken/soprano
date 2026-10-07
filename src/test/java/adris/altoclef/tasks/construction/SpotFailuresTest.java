package adris.altoclef.tasks.construction;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotFailuresTest {

    @Test
    public void oneStrikeIsNotOut() {
        SpotFailures f = new SpotFailures();
        assertFalse(f.fail(1, 64, 1));
        assertFalse(f.isBad(1, 64, 1));
        assertEquals(1, f.count(1, 64, 1));
    }

    @Test
    public void twoStrikesAreOutAndItSaysSoOnTheSecond() {
        SpotFailures f = new SpotFailures();
        assertFalse(f.fail(1, 64, 1));
        assertTrue(f.fail(1, 64, 1));
        assertTrue(f.isBad(1, 64, 1));
    }

    @Test
    public void strikesStayWithTheirSpot() {
        SpotFailures f = new SpotFailures();
        f.fail(1, 64, 1);
        f.fail(1, 64, 1);
        assertFalse(f.isBad(2, 64, 1));
        assertFalse(f.isBad(1, 65, 1));
        assertFalse(f.fail(2, 64, 1));
    }

    @Test
    public void clearGivesEverySpotItsChanceBack() {
        SpotFailures f = new SpotFailures();
        f.fail(-5, 64, 9);
        f.fail(-5, 64, 9);
        f.clear();
        assertFalse(f.isBad(-5, 64, 9));
        assertEquals(0, f.count(-5, 64, 9));
    }
}
