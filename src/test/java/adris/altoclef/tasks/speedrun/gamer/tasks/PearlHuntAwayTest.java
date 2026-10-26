package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlHuntAwayTest {
    private static PearlHuntTask.AwayCount fresh() {
        return new PearlHuntTask.AwayCount(0, Long.MIN_VALUE / 2);
    }

    @Test
    public void aFlipFlopStillReachesTheGiveUp() {
        PearlHuntTask.AwayCount c = fresh();
        // lava in reach every other tick, never calm for long
        for (int t = 0; t < 600; t++) {
            c = c.step(t, t % 2 == 0);
        }
        assertEquals(300, c.ticks());
    }

    @Test
    public void fiveCalmSecondsStartTheCountOver() {
        PearlHuntTask.AwayCount c = fresh();
        for (int t = 0; t < 50; t++) {
            c = c.step(t, true);
        }
        // last flee at 49
        assertEquals(50, c.step(148, false).ticks());
        assertEquals(0, c.step(149, false).ticks());
    }

    @Test
    public void ticksSpentOnOtherBranchesCountAsCalm() {
        PearlHuntTask.AwayCount c = fresh();
        for (int t = 0; t < 299; t++) {
            c = c.step(t, true);
        }
        // a fight owned the next 400 ticks and only the first call of the tick after it looks at the clock
        c = c.step(700, false);
        assertEquals(0, c.ticks());
        assertEquals(1, c.step(701, true).ticks());
    }

    @Test
    public void backsOffOnlyWhenThereIsSomethingToLoot() {
        assertTrue(PearlHuntTask.backsOffFrom(true, true));
        assertFalse(PearlHuntTask.backsOffFrom(true, false));
        assertFalse(PearlHuntTask.backsOffFrom(false, true));
    }
}
