package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetherRestockTest {
    @Test
    public void aFullBagDoesNotRestock() {
        NetherRestock r = new NetherRestock();
        assertFalse(r.update(64, false, true, 0));
        assertFalse(r.update(30, false, true, 1));
    }

    @Test
    public void lowBagStartsAndHoldsUntilTheTarget() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(7, false, true, 0));
        // 30 is above the low bar but the dig is not over until the target
        assertTrue(r.update(30, false, true, 10));
        assertTrue(r.update(63, false, true, 20));
        assertFalse(r.update(64, false, true, 30));
        assertFalse(r.isActive());
    }

    @Test
    public void giveUpRestocksOnAHigherBar() {
        NetherRestock r = new NetherRestock();
        // 20 is fine while walking, but a give-up with that little left is suspicious
        assertFalse(r.update(20, false, true, 0));
        assertTrue(r.update(20, true, true, 1));
        NetherRestock plenty = new NetherRestock();
        assertFalse(plenty.update(40, true, true, 0));
    }

    @Test
    public void noPickNoRestock() {
        NetherRestock r = new NetherRestock();
        assertFalse(r.update(0, true, false, 0));
        assertTrue(r.update(0, true, true, 1));
        // lost the pick mid dig
        assertFalse(r.update(0, false, false, 2));
        assertFalse(r.isActive());
    }

    @Test
    public void aDigThatGoesNowhereStopsAndCoolsDown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, 0));
        assertFalse(r.update(2, false, true, NetherRestock.MAX_SECONDS));
        // still empty, but we just tried
        assertFalse(r.update(2, false, true, NetherRestock.MAX_SECONDS + 10));
        assertTrue(r.update(2, false, true, NetherRestock.MAX_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }
}
