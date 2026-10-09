package adris.altoclef.tasks.container;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.WalkCost;
import org.junit.Test;

// walk to the remembered furnace or place a new one. one line for all of it: WalkCost.STATION_NEAR, a straight line with the
// height counted. offsets are the block centre minus us
public class FurnaceReuseTest {
    @Test
    public void aFurnaceRightNextToUsIsReused() {
        assertFalse(FurnaceReuse.makeNew(true, true, 5, 0, 5, false));
    }

    @Test
    public void threeHundredBlocksAwayIsNotWorthAWalkWithStoneInTheBag() {
        assertTrue(FurnaceReuse.makeNew(true, true, 300, 0, 0, false));
    }

    // the old rule priced height at four flat blocks each, so a furnace 9 up and 7 over was "too far" and a second one went
    // down on top of the table. the straight line says 11.8, close
    @Test
    public void heightIsAStraightLineNowNotAFourfoldWalk() {
        assertFalse(FurnaceReuse.makeNew(true, true, 3, 9, 7, false));
        // 8 across and 4 down used to be 24 of walk and a new furnace, it is 8.9 of line
        assertFalse(FurnaceReuse.makeNew(true, true, 8, -4, 0, false));
        // 15 across and 15 up is 21.2 straight, past the line, whichever way the height goes
        assertTrue(FurnaceReuse.makeNew(true, true, 15, 15, 0, false));
        assertTrue(FurnaceReuse.makeNew(true, true, 15, -15, 0, false));
        // 10 each way is 17.3
        assertFalse(FurnaceReuse.makeNew(true, true, 10, 10, 10, false));
    }

    @Test
    public void theLineIsInclusiveAtTwentyOne() {
        assertFalse(FurnaceReuse.makeNew(true, true, WalkCost.STATION_NEAR, 0, 0, false));
        assertTrue(FurnaceReuse.makeNew(true, true, WalkCost.STATION_NEAR + 0.01, 0, 0, false));
        assertFalse(FurnaceReuse.makeNew(true, true, 0, -WalkCost.STATION_NEAR, 0, false));
        assertTrue(FurnaceReuse.makeNew(true, true, 0, -WalkCost.STATION_NEAR - 0.01, 0, false));
    }

    @Test
    public void withoutAFurnaceOrTheStoneTheOldBehaviourStays() {
        assertFalse(FurnaceReuse.canMakeCheaply(false, 3, true));
        assertFalse(FurnaceReuse.makeNew(true, FurnaceReuse.canMakeCheaply(false, 3, true), 300, 0, 0, false));
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
        assertTrue(FurnaceReuse.makeNew(false, false, 0, 0, 0, false));
        // whatever the other flags say
        assertTrue(FurnaceReuse.makeNew(false, false, 0, 0, 0, true));
        assertTrue(FurnaceReuse.makeNew(false, true, 300, 0, 0, true));
    }

    // no stretch for the ones we placed any more: ours or a village's, past the line is past the line
    @Test
    public void pastTheLineAFreshOneOnlyWinsWhenItIsCheap() {
        assertFalse(FurnaceReuse.makeNew(true, true, 20, 0, 0, false));
        assertTrue(FurnaceReuse.makeNew(true, true, 40, 0, 0, false));
        assertFalse(FurnaceReuse.makeNew(true, false, 40, 0, 0, false));
        assertFalse(FurnaceReuse.makeNew(true, false, 300, -40, 0, false));
    }

    @Test
    public void aFurnaceWithOurOreInItIsNeverLeftForANewOne() {
        assertFalse(FurnaceReuse.makeNew(true, true, 300, 40, 0, true));
        assertFalse(FurnaceReuse.makeNew(true, true, 300, 0, 0, true));
        // however cheap a new one is, the ore is in that one
        assertFalse(FurnaceReuse.makeNew(true, true, 1000, -200, 1000, true));
    }

    // the smoker used to be "never make a new one" (its cache slots were compared to null and are never null), so the cook
    // walked to any smoker it knew about, from anywhere. it goes through the same line as the furnace now
    @Test
    public void aSmokerFarBelowLosesToAFreshOneWhenOneCanBeMade() {
        // 30 blocks down is past the line
        boolean cheap = FurnaceReuse.canMakeSmokerCheaply(false, true, 0, 4, true);
        assertTrue(cheap);
        assertTrue(FurnaceReuse.makeNew(true, cheap, 5, -30, 5, false));
        // a smoker in the bag is the cheapest of all
        assertTrue(FurnaceReuse.canMakeSmokerCheaply(true, false, 0, 0, false));
    }

    @Test
    public void aNearbySmokerOfOursIsReused() {
        boolean cheap = FurnaceReuse.canMakeSmokerCheaply(false, true, 20, 8, true);
        assertFalse(FurnaceReuse.makeNew(true, cheap, 6, 3, 2, false));
    }

    @Test
    public void aSmokerWithOurMeatInItIsNeverLeftBehind() {
        assertFalse(FurnaceReuse.makeNew(true, true, 300, -40, 0, true));
    }

    @Test
    public void noSmokerPossibleMeansWalkingLikeBefore() {
        // no furnace, no stone, or fewer than four logs, or nowhere to craft it
        assertFalse(FurnaceReuse.canMakeSmokerCheaply(false, false, 7, 8, true));
        assertFalse(FurnaceReuse.canMakeSmokerCheaply(false, true, 0, 3, true));
        assertFalse(FurnaceReuse.canMakeSmokerCheaply(false, false, 8, 4, false));
        assertTrue(FurnaceReuse.canMakeSmokerCheaply(false, false, 8, 4, true));
        assertFalse(FurnaceReuse.makeNew(true, false, 300, -40, 0, false));
    }
}
