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
}
