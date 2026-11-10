package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalBedRulesTest {
    @Test
    public void aStandingBedBeatsTheBagAndTheBagBeatsACraft() {
        assertEquals(PortalBedRules.Source.STANDING, PortalBedRules.source(true, 1, true));
        assertEquals(PortalBedRules.Source.BAG, PortalBedRules.source(false, 1, true));
        assertEquals(PortalBedRules.Source.CRAFT, PortalBedRules.source(false, 0, true));
        // nothing to place and nothing to click: skipped, never a hunt
        assertEquals(PortalBedRules.Source.NONE, PortalBedRules.source(false, 0, false));
    }

    @Test
    public void aCraftIsThreeOfAColourAndThreePlanks() {
        assertTrue(PortalBedRules.canCraft(3, 3));
        assertTrue(PortalBedRules.canCraft(27, 30));
        assertFalse(PortalBedRules.canCraft(2, 30));
        // wood after the table's cut
        assertFalse(PortalBedRules.canCraft(27, 2));
    }

    @Test
    public void oneRetryThenGoIn() {
        assertEquals(PortalBedRules.After.DONE, PortalBedRules.after(true, 1));
        assertEquals(PortalBedRules.After.DONE, PortalBedRules.after(true, 2));
        assertEquals(PortalBedRules.After.RETRY, PortalBedRules.after(false, 1));
        assertEquals(PortalBedRules.After.GIVE_UP, PortalBedRules.after(false, PortalBedRules.MAX_TRIES));
        assertEquals(PortalBedRules.After.GIVE_UP, PortalBedRules.after(false, PortalBedRules.MAX_TRIES + 1));
    }
}
