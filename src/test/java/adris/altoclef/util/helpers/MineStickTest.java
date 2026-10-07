package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MineStickTest {
    private static final BlockPos BLOCK = new BlockPos(-97, 83, 3);

    @Test
    public void noBreakNoHold() {
        assertNull(new MineStick().holdOn(0, p -> true));
    }

    @Test
    public void aBlockBeingBrokenIsHeldEveryTickUntilItIsGone() {
        MineStick stick = new MineStick();
        stick.breaking(10, BLOCK);
        for (int tick = 10; tick < 25; tick++) {
            assertEquals(BLOCK, stick.holdOn(tick, p -> true));
        }
        // it broke: the caller's check fails and the drops get their turn
        assertNull(stick.holdOn(26, p -> false));
        // and it stays released, a block that came back is a new block
        assertNull(stick.holdOn(27, p -> true));
    }

    @Test
    public void aPauseLongerThanTheFiveHundredMillisecondFlagStillHolds() {
        // the controller's flag drops after 10 ticks of nothing, a tool swap pause is longer than that
        MineStick stick = new MineStick();
        stick.breaking(100, BLOCK);
        assertEquals(BLOCK, stick.holdOn(100 + 15, p -> true));
        assertEquals(BLOCK, stick.holdOn(100 + MineStick.GRACE_TICKS, p -> true));
    }

    @Test
    public void aBreakThatNeverResumesIsLetGoOfEventually() {
        MineStick stick = new MineStick();
        stick.breaking(100, BLOCK);
        assertNull(stick.holdOn(100 + MineStick.GRACE_TICKS + 1, p -> true));
    }

    @Test
    public void everyFreshTickOfBreakingRestartsTheGrace() {
        MineStick stick = new MineStick();
        stick.breaking(0, BLOCK);
        stick.breaking(25, BLOCK);
        assertEquals(BLOCK, stick.holdOn(50, p -> true));
    }

    @Test
    public void anotherBlockBeingBrokenTakesTheHold() {
        MineStick stick = new MineStick();
        BlockPos other = BLOCK.east();
        stick.breaking(0, BLOCK);
        stick.breaking(5, other);
        assertEquals(other, stick.holdOn(6, p -> true));
        assertTrue(stick.isHolding(other));
        assertFalse(stick.isHolding(BLOCK));
    }

    @Test
    public void clearForgetsIt() {
        MineStick stick = new MineStick();
        stick.breaking(0, BLOCK);
        stick.clear();
        assertNull(stick.holdOn(1, p -> true));
        assertFalse(stick.isHolding(BLOCK));
    }

    @Test
    public void aNewTargetNeedsToBeTwiceAsCloseNotJustCloser() {
        // squared distances: 2x closer is a quarter
        assertTrue(MineStick.clearlyCloser(1, 4));
        assertFalse(MineStick.clearlyCloser(2, 4));
        // the block and the drop next to it trade places as we step, neither is twice as close as the other
        assertFalse(MineStick.clearlyCloser(2.25, 4));
        assertFalse(MineStick.clearlyCloser(4, 2.25));
    }

    @Test
    public void toolSwapIsLeftAloneOnlyWithACrackOnThatBlock() {
        assertTrue(MineStick.toolSwapWouldReset(true, BLOCK, 0.3, BLOCK));
        // nothing cracked yet: the swap is the cheap moment
        assertFalse(MineStick.toolSwapWouldReset(true, BLOCK, 0.0, BLOCK));
        // somebody else's crack, or a stale flag
        assertFalse(MineStick.toolSwapWouldReset(true, BLOCK.east(), 0.3, BLOCK));
        assertFalse(MineStick.toolSwapWouldReset(false, BLOCK, 0.3, BLOCK));
        assertFalse(MineStick.toolSwapWouldReset(true, null, 0.3, BLOCK));
    }
}
