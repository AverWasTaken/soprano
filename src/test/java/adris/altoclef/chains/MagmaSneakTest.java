package adris.altoclef.chains;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// shift down on magma, and only when nobody is walking us off it
public class MagmaSneakTest {

    @Test
    public void standingOnMagmaSneaks() {
        assertTrue(WorldSurvivalChain.magmaSneakNow(true, true, false));
    }

    @Test
    public void aWalkOffTheMagmaIsLeftAlone() {
        assertFalse(WorldSurvivalChain.magmaSneakNow(true, true, true));
    }

    @Test
    public void inTheAirOrOnStoneItLetsGo() {
        assertFalse(WorldSurvivalChain.magmaSneakNow(false, true, false));
        assertFalse(WorldSurvivalChain.magmaSneakNow(true, false, false));
    }
}
