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

    @Test
    public void withoutTheStoneForOneOursStaysToo() {
        assertFalse(FurnaceReuse.makeNew(true, false, 300, 0, 0, true, false));
    }
}
