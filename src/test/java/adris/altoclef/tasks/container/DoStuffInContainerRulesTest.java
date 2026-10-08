package adris.altoclef.tasks.container;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the 23:14 log: the table left the bag, the walk cut the placer off mid verify, and the force timer crafted a second table
public class DoStuffInContainerRulesTest {
    @Test
    public void thePlacerStaysThroughVerifyWithTheItemGone() {
        assertTrue(DoStuffInContainerTask.keepPlacing(true, true, false, false));
        // the click took the table out of the bag, the block has not shown up yet
        assertTrue(DoStuffInContainerTask.keepPlacing(false, true, false, true));
        // item gone and nothing to wait for: not our placer's business any more
        assertFalse(DoStuffInContainerTask.keepPlacing(false, true, false, false));
        // done is done
        assertFalse(DoStuffInContainerTask.keepPlacing(false, true, true, true));
        assertFalse(DoStuffInContainerTask.keepPlacing(true, false, false, false));
    }

    @Test
    public void aStandingTableIsNotRemadeByTheForceTimer() {
        // force timer running, 3 s since the click, nearest is ours and standing, the walk is cheap: walk
        assertFalse(DoStuffInContainerTask.makeNewNow(true, true, false, true, true));
        // same but the placed block is not in the world (picked up, never landed): the force timer keeps placing
        assertTrue(DoStuffInContainerTask.makeNewNow(true, false, false, true, true));
        // within the 3 s after a placement we go to it
        assertFalse(DoStuffInContainerTask.makeNewNow(true, false, true, true, false));
        // no container at all is always make one
        assertTrue(DoStuffInContainerTask.makeNewNow(false, false, false, false, false));
    }

    // the placer instance lives on after the craft, so a table we left 30 blocks back still "stands"
    @Test
    public void aStandingTableThatIsTooFarToWalkStillLosesToANewOne() {
        assertTrue(DoStuffInContainerTask.makeNewNow(true, true, true, true, true));
    }
}
