package adris.altoclef.tasks.movement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatRules;
import org.junit.Test;

// the pathfinder is left alone for a bit after a goal was handed to it and did not stick. a goal nothing can reach was
// re-sent on every tick, a full search each time, for as long as the run lasted
public class CommittedRunTaskTest {

    @Test
    public void theGoalPushesPastEveryLineTheCommitmentHoldsARunFor() {
        assertTrue(CommittedRunTask.CROWD_CLEAR > CombatCommit.WITHER_AGGRO);
        assertTrue(CommittedRunTask.CROWD_CLEAR > CombatRules.SHOOTER_RANGE);
    }

    @Test
    public void theFirstIssueAlwaysGoesThrough() {
        // nothing was handed over yet
        assertTrue(CommittedRunTask.mayReissue(0, Long.MIN_VALUE / 2));
        assertTrue(CommittedRunTask.mayReissue(5000, Long.MIN_VALUE / 2));
    }

    @Test
    public void asksAreSpacedByTheGap() {
        assertEquals(40, CommittedRunTask.REISSUE_GAP_TICKS);
        long issued = 1000;
        assertFalse(CommittedRunTask.mayReissue(issued + 1, issued));
        assertFalse(CommittedRunTask.mayReissue(issued + 39, issued));
        assertTrue(CommittedRunTask.mayReissue(issued + 40, issued));
        assertTrue(CommittedRunTask.mayReissue(issued + 400, issued));
    }

    @Test
    public void aClockThatWentBackwardsIsLongEnoughAgo() {
        // a new world: the old issue time is nonsense
        assertTrue(CommittedRunTask.mayReissue(10, 5000));
    }

    @Test
    public void aRunThatStartsMidFallLetsTheFallFinish() {
        // in the air: the soft cancel, the fall movement keeps its bucket or clutch
        assertFalse(CommittedRunTask.mayCancelHard(false, false, false));
        // standing, swimming or on a ladder there is nothing to fall out of
        assertTrue(CommittedRunTask.mayCancelHard(true, false, false));
        assertTrue(CommittedRunTask.mayCancelHard(false, true, false));
        assertTrue(CommittedRunTask.mayCancelHard(false, false, true));
    }

    @Test
    public void onlyAfloatMeansSwimToTheBank() {
        assertTrue(CommittedRunTask.afloat(true, false));
        // wading with the bottom under us walks out by itself
        assertFalse(CommittedRunTask.afloat(true, true));
        assertFalse(CommittedRunTask.afloat(false, false));
        assertFalse(CommittedRunTask.afloat(false, true));
    }

    @Test
    public void theWideShoreLookIsSpacedByItsOwnGap() {
        long gap = CommittedRunTask.WIDE_SHORE_GAP_TICKS;
        assertTrue(CommittedRunTask.mayReissue(1000, Long.MIN_VALUE / 2, gap));
        assertFalse(CommittedRunTask.mayReissue(1000 + gap - 1, 1000, gap));
        assertTrue(CommittedRunTask.mayReissue(1000 + gap, 1000, gap));
        // a whole reissue gap later is still too soon for the wide one
        assertFalse(CommittedRunTask.mayReissue(1000 + CommittedRunTask.REISSUE_GAP_TICKS, 1000, gap));
        assertTrue(CommittedRunTask.WIDE_SHORE_RADIUS > 32);
    }
}
