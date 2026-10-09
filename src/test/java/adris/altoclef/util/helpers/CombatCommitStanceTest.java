package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatRules.Stance;
import org.junit.Test;

// who may eat while we are committed. the stance is what the food chain asks, so this is the whole eating policy
public class CombatCommitStanceTest {

    private static final double NOBODY = Double.POSITIVE_INFINITY;

    @Test
    public void nobodyCommittedAndNothingWrongIsAMealtime() {
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 20, NOBODY, false, false));
        // a zombie ten blocks off is scenery, lunch carries on
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 12, 10, false, false));
        assertTrue(CombatCommit.stance(Mode.NONE, 12, 5, false, false).mayEat());
    }

    @Test
    public void aFightIsNeverAMealtime() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 20, 2, false, false));
        // even when the target wandered off the list, we are still in it
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 20, NOBODY, false, false));
        assertFalse(CombatCommit.stance(Mode.FIGHT, 20, 2, false, false).mayEat());
    }

    @Test
    public void aRunWithSomethingCloseDoesNotStopForSnacks() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.RUN, 20, 8, false, false));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 6, 8, false, false));
        assertFalse(CombatCommit.stance(Mode.RUN, 6, 5, false, false).mayEat());
    }

    // the run stays through a bite and the food chain does it in the background. but only once the run is clear: chewing
    // is a third of the walking speed, a bite with somebody at 9 blocks is dropped a second later and picked up again
    @Test
    public void aRunMayEatOnceNothingAngryIsWithinSixteen() {
        assertTrue(CombatCommit.stance(Mode.RUN, 20, 16.5, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 5, 30, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 3, NOBODY, false, false).mayEat());
    }

    @Test
    public void aFollowerBetweenEightAndSixteenStillMeansNoMeal() {
        assertFalse(CombatCommit.stance(Mode.RUN, 20, 12, false, false).mayEat());
        assertFalse(CombatCommit.stance(Mode.RUN, 7, 9, false, false).mayEat());
        assertFalse(CombatCommit.stance(Mode.RUN, 7, CombatCommit.RUN_CLEAR, false, false).mayEat());
        // low enough that the old quick bite would have fired: still a flee while somebody is following
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 12, false, false));
    }

    // the quick bite at hp 4 means "nothing next to us". committed, something is within 8, so it is a flee
    @Test
    public void theQuickBiteBecomesAFleeWhileCommitted() {
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 6, false, false));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.FIGHT, 3, NOBODY, false, false));
        // not committed (a cooldown, a hurt walk) the old quick bite stands
        assertEquals(Stance.EAT, CombatCommit.stance(Mode.NONE, 3, 6, false, false));
    }

    @Test
    public void aGappleIsWhatGapplesAreFor() {
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.RUN, 6, 5, false, true));
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.NONE, 6, 5, false, true));
        // healthy, a gapple is not a snack
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 18, 3, false, true));
    }

    @Test
    public void hurtWithSomethingNearIsNotAMealtimeEvenWithoutACommitment() {
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.NONE, 7, 6, false, false));
        // hurt but the nearest is past the line: eat
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 7, 9, false, false));
    }

    @Test
    public void aLitCreeperCloseKeepsUsOffTheMenu() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.NONE, 20, NOBODY, true, false));
        assertFalse(CombatCommit.stance(Mode.NONE, 20, NOBODY, true, false).mayEat());
    }
}
