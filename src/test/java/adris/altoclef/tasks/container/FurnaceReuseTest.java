package adris.altoclef.tasks.container;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// walk to the remembered furnace or place a new one
public class FurnaceReuseTest {
    @Test
    public void aFurnaceRightNextToUsIsReused() {
        assertTrue(FurnaceReuse.cheapToReach(5, 0, 5));
        assertFalse(FurnaceReuse.makeNew(true, true, 5, 0, 5));
    }

    @Test
    public void threeHundredBlocksAwayIsNotWorthAWalkWithStoneInTheBag() {
        // the run this came from
        assertFalse(FurnaceReuse.cheapToReach(300, 0, 0));
        assertTrue(FurnaceReuse.makeNew(true, true, 300, 0, 0));
    }

    @Test
    public void heightCountsFourTimes() {
        // 8 across and 3 down: 8 + 12 = 20, the budget exactly
        assertTrue(FurnaceReuse.cheapToReach(8, -3, 0));
        // one more block of drop is not
        assertFalse(FurnaceReuse.cheapToReach(8, -4, 0));
        // it is the vertical gap, whichever way
        assertFalse(FurnaceReuse.cheapToReach(0, 6, 0));
    }

    @Test
    public void withoutAFurnaceOrTheStoneTheOldBehaviourStays() {
        assertFalse(FurnaceReuse.canMakeCheaply(false, 3, true));
        assertFalse(FurnaceReuse.makeNew(true, FurnaceReuse.canMakeCheaply(false, 3, true), 300, 0, 0));
        // stone but no table and no wood for one
        assertFalse(FurnaceReuse.canMakeCheaply(false, 20, false));
    }

    @Test
    public void aCarriedFurnaceOrEightCobbleAndATableIsCheap() {
        assertTrue(FurnaceReuse.canMakeCheaply(true, 0, false));
        assertTrue(FurnaceReuse.canMakeCheaply(false, 8, true));
        assertFalse(FurnaceReuse.canMakeCheaply(false, 7, true));
    }

    @Test
    public void noRememberedFurnaceMeansANewOne() {
        assertTrue(FurnaceReuse.makeNew(false, false, 0, 0, 0));
    }

    @Test
    public void aFurnaceOfOursNineBlocksBelowIsStillWorthTheWalk() {
        // the 16:08 numbers: 3 across, 9 up, 7 over. 7.6 flat + 36 for the height is over the 20 budget, so a second furnace
        // went down on top of the crafting table
        assertTrue(FurnaceReuse.makeNew(true, true, 3, 9, 7));
        assertFalse(FurnaceReuse.makeNew(true, true, 3, 9, 7, true, false));
    }

    @Test
    public void ourOwnFurnaceStillLosesToAFreshOneFromAnOldArea() {
        assertTrue(FurnaceReuse.makeNew(true, true, 300, 0, 0, true, false));
        // right at the stretched budget it is still reachable, one block past it is not
        assertFalse(FurnaceReuse.makeNew(true, true, FurnaceReuse.OURS_BUDGET, 0, 0, true, false));
        assertTrue(FurnaceReuse.makeNew(true, true, FurnaceReuse.OURS_BUDGET + 1, 0, 0, true, false));
        // and a furnace that is not ours gets no stretch
        assertTrue(FurnaceReuse.makeNew(true, true, 40, 0, 0, false, false));
        assertFalse(FurnaceReuse.makeNew(true, true, 40, 0, 0, true, false));
    }

    @Test
    public void aFurnaceWithOurOreInItIsNeverLeftForANewOne() {
        assertFalse(FurnaceReuse.makeNew(true, true, 300, 40, 0, true, true));
        assertFalse(FurnaceReuse.makeNew(true, true, 300, 0, 0, false, true));
        // nothing remembered is still a new one, whatever the flags say
        assertTrue(FurnaceReuse.makeNew(false, false, 0, 0, 0, true, true));
    }

    // the smoker used to be "never make a new one" (its cache slots were compared to null and are never null), so the cook
    // walked to any smoker it knew about, from anywhere. now it prices the walk like the furnace does
    @Test
    public void aSmokerFarBelowLosesToAFreshOneWhenOneCanBeMade() {
        // 40 s of walking down a cave: 30 blocks down is 120 of walk
        boolean cheap = FurnaceReuse.canMakeSmokerCheaply(false, true, 0, 4, true);
        assertTrue(cheap);
        assertTrue(FurnaceReuse.makeNew(true, cheap, 5, -30, 5, true, false));
        // a smoker in the bag is the cheapest of all
        assertTrue(FurnaceReuse.canMakeSmokerCheaply(true, false, 0, 0, false));
    }

    @Test
    public void aNearbySmokerOfOursIsReused() {
        boolean cheap = FurnaceReuse.canMakeSmokerCheaply(false, true, 20, 8, true);
        assertFalse(FurnaceReuse.makeNew(true, cheap, 6, 3, 2, true, false));
    }

    @Test
    public void aSmokerWithOurMeatInItIsNeverLeftBehind() {
        assertFalse(FurnaceReuse.makeNew(true, true, 300, -40, 0, true, true));
    }

    @Test
    public void noSmokerPossibleMeansWalkingLikeBefore() {
        // no furnace, no stone, or fewer than four logs, or nowhere to craft it
        assertFalse(FurnaceReuse.canMakeSmokerCheaply(false, false, 7, 8, true));
        assertFalse(FurnaceReuse.canMakeSmokerCheaply(false, true, 0, 3, true));
        assertFalse(FurnaceReuse.canMakeSmokerCheaply(false, false, 8, 4, false));
        assertTrue(FurnaceReuse.canMakeSmokerCheaply(false, false, 8, 4, true));
        assertFalse(FurnaceReuse.makeNew(true, false, 300, -40, 0, true, false));
    }

    // the cook need flips between the smoker and the furnace kinds with the answer, and a flip restarts the cook task, so the
    // line has a margin once a smoker is counted
    @Test
    public void theSmokerLineHasAMarginSoItDoesNotFlapAtTheBoundary() {
        double line = FurnaceReuse.OURS_BUDGET;
        assertTrue(FurnaceReuse.smokerWorthWalking(false, line));
        assertFalse(FurnaceReuse.smokerWorthWalking(false, line + 1));
        // already counted: stays until a quarter past
        assertTrue(FurnaceReuse.smokerWorthWalking(true, line + 1));
        assertTrue(FurnaceReuse.smokerWorthWalking(true, line * 1.25));
        assertFalse(FurnaceReuse.smokerWorthWalking(true, line * 1.25 + 1));
        // 80 blocks straight down is never worth it
        assertFalse(FurnaceReuse.smokerWorthWalking(true, adris.altoclef.util.helpers.WalkCost.estimate(0, -80, 0)));
    }

    @Test
    public void withoutTheStoneForOneOursStaysToo() {
        assertFalse(FurnaceReuse.makeNew(true, false, 300, 0, 0, true, false));
    }
}
