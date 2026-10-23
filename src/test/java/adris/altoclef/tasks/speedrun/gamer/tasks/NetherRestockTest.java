package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetherRestockTest {
    @Test
    public void aFullBagDoesNotRestock() {
        NetherRestock r = new NetherRestock();
        assertFalse(r.update(64, false, true, false, 0));
        assertFalse(r.update(30, false, true, false, 1));
    }

    @Test
    public void lowBagStartsAndHoldsUntilTheTarget() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(7, false, true, false, 0));
        // 30 is above the low bar but the dig is not over until the target
        assertTrue(r.update(30, false, true, false, 10));
        assertTrue(r.update(63, false, true, false, 20));
        assertFalse(r.update(64, false, true, false, 30));
        assertFalse(r.isActive());
    }

    @Test
    public void giveUpRestocksOnAHigherBar() {
        NetherRestock r = new NetherRestock();
        // 20 is fine while walking, but a give-up with that little left is suspicious
        assertFalse(r.update(20, false, true, false, 0));
        assertTrue(r.update(20, true, true, false, 1));
        NetherRestock plenty = new NetherRestock();
        assertFalse(plenty.update(40, true, true, false, 0));
    }

    @Test
    public void noPickNoRestock() {
        NetherRestock r = new NetherRestock();
        assertFalse(r.update(0, true, false, false, 0));
        assertTrue(r.update(0, true, true, false, 1));
        // lost the pick mid dig
        assertFalse(r.update(0, false, false, false, 2));
        assertFalse(r.isActive());
    }

    @Test
    public void aNormalFinishCoolsDownToo() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, false, 0));
        assertFalse(r.update(64, false, true, false, 10));
        // the bag drops straight back under the bar (a bridge ate it), still no new restock
        assertFalse(r.update(2, false, true, false, 11));
        assertTrue(r.update(2, false, true, false, 10 + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void exhaustedEndsItWithACooldown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, false, 0));
        assertTrue(r.update(2, false, true, true, 5));
        // a blip that clears is forgiven
        assertTrue(r.update(2, false, true, false, 6));
        assertTrue(r.update(2, false, true, true, 7));
        assertFalse(r.update(2, false, true, true, 7 + NetherRestock.EXHAUSTED_SECONDS));
        assertFalse(r.update(2, false, true, false, 8 + NetherRestock.EXHAUSTED_SECONDS));
        assertTrue(r.update(2, false, true, false, 7 + NetherRestock.EXHAUSTED_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void bandKeepsToTheStartingHeightAndRadius() {
        BlockPos o = new BlockPos(100, 70, 100);
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(100, 69, 100)));
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(133, 70, 100)));
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(101, 70, 100)));
        assertTrue(NetherRestock.inRestockBand(o, new BlockPos(132, 90, 100)));
        // the floor layer is out even away from where we stood: it can be the bridge we walk on
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(102, 69, 100)));
        assertFalse(NetherRestock.inRestockBand(o, new BlockPos(105, 68, 100)));
    }

    @Test
    public void aDigThatGoesNowhereStopsAndCoolsDown() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, false, 0));
        assertFalse(r.update(2, false, true, false, NetherRestock.MAX_SECONDS));
        // still empty, but we just tried
        assertFalse(r.update(2, false, true, false, NetherRestock.MAX_SECONDS + 10));
        assertTrue(r.update(2, false, true, false, NetherRestock.MAX_SECONDS + NetherRestock.COOLDOWN_SECONDS));
    }

    @Test
    public void dryEndsCountInARowAndAnyOtherEndResetsThem() {
        NetherRestock r = new NetherRestock();
        double t = 0;
        t = dryRestock(r, t);
        assertEquals(1, r.exhaustedEnds());
        // second one after the cooldown
        t = dryRestock(r, t + NetherRestock.COOLDOWN_SECONDS);
        assertEquals(2, r.exhaustedEnds());
        // one that ends on the clock breaks the run
        t += NetherRestock.COOLDOWN_SECONDS;
        assertTrue(r.update(2, false, true, false, t));
        assertFalse(r.update(2, false, true, false, t + NetherRestock.MAX_SECONDS));
        assertEquals(0, r.exhaustedEnds());
    }

    // starts a restock at t and feeds it "nothing left to dig" until it gives up, returns when it did
    private static double dryRestock(NetherRestock r, double t) {
        assertTrue(r.update(2, false, true, true, t));
        // the first exhausted tick starts the grace, the end comes EXHAUSTED_SECONDS after it
        assertTrue(r.update(2, false, true, true, t + 1));
        assertFalse(r.update(2, false, true, true, t + 1 + NetherRestock.EXHAUSTED_SECONDS));
        return t + 1 + NetherRestock.EXHAUSTED_SECONDS;
    }

    @Test
    public void aFullBagEndIsNotADryEnd() {
        NetherRestock r = new NetherRestock();
        assertTrue(r.update(2, false, true, true, 0));
        assertTrue(r.update(2, false, true, true, 1));
        assertFalse(r.update(NetherRestock.TARGET, false, true, true, 1 + NetherRestock.EXHAUSTED_SECONDS));
        assertEquals(0, r.exhaustedEnds());
    }
}
