package adris.altoclef.control;

import static org.junit.Assert.assertEquals;

import adris.altoclef.control.CraftScreenRules.Action;
import adris.altoclef.control.CraftScreenRules.Facts;
import org.junit.Test;

public class CraftScreenRulesTest {

    // a grid click on a quiet survival tick with nothing on screen, the one case that opens
    private static Facts click() {
        return new Facts(true, false, false, false, true, false, true, false, false, 0, false);
    }

    // our screen is up, nobody clicked this tick, idle for this long
    private static Facts up(int idle, boolean gridEmpty) {
        return new Facts(true, true, true, true, false, false, true, false, false, idle, gridEmpty);
    }

    @Test
    public void aGridClickOpensTheInventory() {
        assertEquals(Action.OPEN, CraftScreenRules.decide(click()));
    }

    @Test
    public void noClickNoScreen() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, false, false, false, false, false, true, false, false, 0, true)));
    }

    @Test
    public void theSettingOffNeverOpens() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(false, false, false, false, true, false, true, false, false, 0, false)));
    }

    @Test
    public void creativeNeverOpens() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, false, false, false, true, true, true, false, false, 0, false)));
    }

    @Test
    public void neverOpensOverThePlayersScreen() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, false, false, true, true, false, true, false, false, 0, false)));
    }

    @Test
    public void neverOpensWithAnotherMenuOpen() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, false, false, false, true, false, false, false, false, 0, false)));
    }

    @Test
    public void neverOpensIntoTrouble() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, false, false, false, true, false, true, true, false, 0, false)));
    }

    @Test
    public void neverOpensWhileAKeyIsHeld() {
        // a magma sneak pin, a ledge brake, a hand on W: the screen would let go of it
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, false, false, false, true, false, true, false, true, 0, false)));
    }

    @Test
    public void aHeldKeyDoesNotCloseItThough() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, true, true, true, true, false, true, false, true, 0, false)));
    }

    @Test
    public void troubleClosesItRightAwayEvenMidCraft() {
        assertEquals(Action.CLOSE, CraftScreenRules.decide(new Facts(true, true, true, true, true, false, true, true, false, 0, false)));
    }

    @Test
    public void turningTheSettingOffClosesIt() {
        assertEquals(Action.CLOSE, CraftScreenRules.decide(new Facts(false, true, true, true, false, false, true, false, false, 0, false)));
    }

    @Test
    public void aClickKeepsItUp() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(new Facts(true, true, true, true, true, false, true, false, false, 0, true)));
    }

    @Test
    public void anEmptyGridLingersThenCloses() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(up(CraftScreenRules.LINGER_TICKS - 1, true)));
        assertEquals(Action.CLOSE, CraftScreenRules.decide(up(CraftScreenRules.LINGER_TICKS, true)));
    }

    @Test
    public void aFullGridWaitsForTheCraftUntilItLooksDropped() {
        assertEquals(Action.NOTHING, CraftScreenRules.decide(up(CraftScreenRules.LINGER_TICKS, false)));
        assertEquals(Action.NOTHING, CraftScreenRules.decide(up(CraftScreenRules.ABANDON_TICKS - 1, false)));
        assertEquals(Action.CLOSE, CraftScreenRules.decide(up(CraftScreenRules.ABANDON_TICKS, false)));
    }

    @Test
    public void aScreenThatIsNotOursAnymoreIsForgottenNotClosed() {
        // the player pressed E, or opened chat over it: closing would shut theirs
        assertEquals(Action.FORGET, CraftScreenRules.decide(new Facts(true, true, false, true, false, false, true, true, false, 50, true)));
        assertEquals(Action.FORGET, CraftScreenRules.decide(new Facts(false, true, false, false, false, false, true, false, false, 0, true)));
    }
}
