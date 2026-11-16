package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import adris.altoclef.util.helpers.DeathStash.Death;
import baritone.api.utils.Dimension;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// the mailbox. the world half (snapshot) reads a LocalPlayer and is not in here
public class DeathStashTest {

    private static Death death(int x, Dimension dimension) {
        return new Death(dimension, x, 64, -30, 1000, false, false, "mob by zombie", "zombie");
    }

    // it is static state, the next test must not inherit a death
    @Before
    @After
    public void empty() {
        DeathStash.clear();
    }

    @Test
    public void nothingToTakeAtFirst() {
        assertNull(DeathStash.take());
    }

    @Test
    public void aDeathIsTakenExactlyOnce() {
        Death d = death(12, Dimension.OVERWORLD);
        DeathStash.put(d);
        assertSame(d, DeathStash.take());
        assertNull(DeathStash.take());
    }

    @Test
    public void theLaterDeathWins() {
        DeathStash.put(death(1, Dimension.OVERWORLD));
        Death second = death(2, Dimension.NETHER);
        DeathStash.put(second);
        assertSame(second, DeathStash.take());
        assertNull(DeathStash.take());
    }

    @Test
    public void clearDropsAStaleOne() {
        DeathStash.put(death(1, Dimension.END));
        DeathStash.clear();
        assertNull(DeathStash.take());
    }

    @Test
    public void thePlaceReadsAsPlainCoordinates() {
        assertEquals("12 64 -30", death(12, Dimension.OVERWORLD).where());
    }

    @Test
    public void theDimensionIsTheOneItDiedIn() {
        // a nether death respawns in the overworld, the record must not follow the respawn
        DeathStash.put(death(5, Dimension.NETHER));
        assertEquals(Dimension.NETHER, DeathStash.take().dimension());
    }

    @Test
    public void theKillingBlowNamesTheKillerFirst() {
        assertEquals("piglin_brute", DeathStash.killer("piglin_brute", false, false, "blaze", 990, 1000));
    }

    @Test
    public void aBodylessBlowFallsBackToTheLastHitInsideTheWindow() {
        // the wither effect finishing what the skeleton started
        assertEquals("wither_skeleton", DeathStash.killer(null, false, false, "wither_skeleton", 1000 - DeathStash.ATTACKER_WINDOW_TICKS, 1000));
        assertNull(DeathStash.killer(null, false, false, "wither_skeleton", 1000 - DeathStash.ATTACKER_WINDOW_TICKS - 1, 1000));
        assertNull(DeathStash.killer(null, false, false, null, Long.MIN_VALUE, 1000));
        // a hit from the future is another world's clock
        assertNull(DeathStash.killer(null, false, false, "blaze", 1005, 1000));
    }

    @Test
    public void lavaAndTheVoidNameNobody() {
        assertNull(DeathStash.killer("blaze", true, false, "blaze", 999, 1000));
        assertNull(DeathStash.killer(null, false, true, "ghast", 999, 1000));
    }
}
