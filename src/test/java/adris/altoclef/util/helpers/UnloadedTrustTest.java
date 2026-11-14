package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.UnloadedTrust.Part;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnloadedTrustTest {
    private static final BlockPos BED = new BlockPos(976, 37, 1541);
    private static final BlockPos OTHER = new BlockPos(10, 64, 10);

    @Test
    public void pickedBlindThenRefusedOnceLoadedIsCaughtOnce() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, false);
        assertTrue(trust.caught(BED, true, true, false));
        // one ban is the whole answer, the next drop of the same block is somebody else's business
        assertFalse(trust.caught(BED, true, true, false));
    }

    @Test
    public void pickedWhileLoadedNeverCounts() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, true);
        assertFalse(trust.caught(BED, true, true, false));
    }

    @Test
    public void heldWhileLoadedForgetsTheBlindPick() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, false);
        // walked up to it, it loaded and passed isValid: an ordinary pursuit from here on
        trust.seen(BED, true);
        assertFalse(trust.caught(BED, true, true, false));
    }

    @Test
    public void aGoneBlockIsTheTrackersJob() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, false);
        assertFalse(trust.caught(BED, true, false, true));
        assertFalse("and it is forgotten", trust.picked(BED));
    }

    @Test
    public void breakableOnceLoadedIsNoBan() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, false);
        assertFalse(trust.caught(BED, true, true, true));
        assertFalse(trust.picked(BED));
    }

    @Test
    public void aDropWhileStillUnloadedKeepsWaiting() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, false);
        assertFalse(trust.caught(BED, false, true, true));
        assertTrue(trust.picked(BED));
        assertTrue(trust.caught(BED, true, true, false));
    }

    @Test
    public void blocksDontShareTheirMarks() {
        UnloadedTrust trust = new UnloadedTrust();
        trust.seen(BED, false);
        assertFalse(trust.caught(OTHER, true, true, false));
        assertTrue(trust.picked(BED));
    }

    @Test
    public void theCapClearsInsteadOfGrowing() {
        UnloadedTrust trust = new UnloadedTrust();
        for (int i = 0; i < 256; i++) {
            trust.seen(new BlockPos(i, 0, 0), false);
        }
        trust.seen(BED, false);
        assertFalse(trust.picked(new BlockPos(0, 0, 0)));
        assertTrue(trust.picked(BED));
    }

    @Test
    public void theFirstFailingPartWinsInCanBreaksOrder() {
        assertEquals(Part.DESTROY_SPEED, UnloadedTrust.failing(true, true, true, true, false, true));
        assertEquals(Part.AVOID_RULE, UnloadedTrust.failing(false, true, true, true, false, true));
        assertEquals(Part.UNREACHABLE, UnloadedTrust.failing(false, false, true, true, false, true));
        assertEquals(Part.TOO_SLOW, UnloadedTrust.failing(false, false, false, true, false, true));
        assertEquals(Part.PLAUSIBLE, UnloadedTrust.failing(false, false, false, false, false, true));
        assertEquals(Part.OCEAN, UnloadedTrust.failing(false, false, false, false, true, true));
        assertEquals(Part.OTHER, UnloadedTrust.failing(false, false, false, false, true, false));
    }
}
