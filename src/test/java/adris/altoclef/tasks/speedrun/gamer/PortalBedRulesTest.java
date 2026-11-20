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

    // a flat world with the surface at 70, and the ground within r of the portal raised (or sunk) to h
    private static int surface(int x, int z, int h, int r) {
        return Math.abs(x) <= r && Math.abs(z) <= r ? h : 70;
    }

    @Test
    public void aPortalOutInTheOpenIsShallow() {
        // the frame top over the portal's own column reads as solid, the columns beside it don't
        assertEquals(0, PortalBedRules.portalDepth(0, 70, 0, (x, z) -> x == 0 && z == 0 ? 74 : 70));
        assertFalse(PortalBedRules.tooDeep(PortalBedRules.portalDepth(0, 70, 0, (x, z) -> x == 0 && z == 0 ? 74 : 70)));
        // on a hillside: the rim is lower than the portal on the way down
        assertEquals(0, PortalBedRules.portalDepth(0, 90, 0, (x, z) -> 90 - x));
    }

    @Test
    public void aPortalUnderRockIsDeep() {
        assertEquals(53, PortalBedRules.portalDepth(0, 55, 0, (x, z) -> 108));
        assertTrue(PortalBedRules.tooDeep(PortalBedRules.portalDepth(0, 55, 0, (x, z) -> 108)));
        // under a hill that drops to the valley floor right past the rim: still rock over our heads
        assertEquals(30, PortalBedRules.portalDepth(0, 70, 0, (x, z) -> surface(x, z, 100, 11)));
        // the 1x1 shaft we dug down to the pool, right beside the frame: one open column, still a cave
        assertEquals(53, PortalBedRules.portalDepth(0, 55, 0, (x, z) -> x == 1 && z == 0 ? 55 : 108));
        // and with the frame on top too (three columns a few over the portal), the field around it still wins
        assertEquals(0, PortalBedRules.portalDepth(0, 70, 0, (x, z) -> z == 0 && x >= -1 && x <= 1 ? 74 : 70));
    }

    @Test
    public void aPitOpenToTheSkyIsDeepWhenEveryWayOutIsUp() {
        // a 10 wide shaft down to 50 with the ground at 70 all around
        assertEquals(20, PortalBedRules.portalDepth(0, 50, 0, (x, z) -> surface(x, z, 50, 10)));
        // a ravine that runs off past the rim reads as open. a deep one is a climb too, but the rim can't tell those apart
        assertEquals(0, PortalBedRules.portalDepth(0, 50, 0, (x, z) -> Math.abs(z) <= 2 ? 50 : 70));
    }

    @Test
    public void theLineIsTheSmeltClimbsLine() {
        assertFalse(PortalBedRules.tooDeep(SmeltSurface.GO_UP_DEPTH));
        assertTrue(PortalBedRules.tooDeep(SmeltSurface.GO_UP_DEPTH + 1));
        int line = SmeltSurface.GO_UP_DEPTH;
        assertEquals(line, PortalBedRules.portalDepth(0, 70 - line, 0, (x, z) -> 70));
        assertEquals(line + 1, PortalBedRules.portalDepth(0, 70 - line - 1, 0, (x, z) -> 70));
    }

    @Test
    public void columnsItCantSeeDontCount() {
        // unloaded reads as UNKNOWN, never as the floor of the world (that would make every rim "open")
        assertEquals(40, PortalBedRules.portalDepth(0, 60, 0, (x, z) -> x > 0 ? PortalBedRules.UNKNOWN : 100));
        assertEquals(0, PortalBedRules.portalDepth(0, 60, 0, (x, z) -> PortalBedRules.UNKNOWN));
    }
}
