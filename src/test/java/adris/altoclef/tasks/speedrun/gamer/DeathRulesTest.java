package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DeathRules.Source;
import org.junit.Test;

// who gets to say a new player instance was a death. the three witnesses are plain booleans here
public class DeathRulesTest {

    @Test
    public void ourOwnTickSawIt() {
        assertEquals(Source.TICK, DeathRules.source(true, false, false));
        assertTrue(DeathRules.seen(true, false, false));
    }

    @Test
    public void onlyTheStashSawIt() {
        // mob defense held the wheel through the fight, the death and the death screen: no tick, no zero health on a body
        // we ever looked at, just the packet
        assertEquals(Source.STASH, DeathRules.source(false, true, false));
        assertTrue(DeathRules.seen(false, true, false));
    }

    @Test
    public void onlyTheOldInstanceKnows() {
        assertEquals(Source.OLD_INSTANCE, DeathRules.source(false, false, true));
        assertTrue(DeathRules.seen(false, false, true));
    }

    @Test
    public void aPortalIsNotADeath() {
        // new instance, old one alive, nothing in the stash
        assertEquals(Source.NONE, DeathRules.source(false, false, false));
        assertFalse(DeathRules.seen(false, false, false));
    }

    @Test
    public void theStashBeatsTheTickWhenBothHaveARecord() {
        // the packet is the earlier sample, the tick can have seen the body after it slid
        assertEquals(Source.STASH, DeathRules.source(true, true, true));
        assertEquals(Source.STASH, DeathRules.source(true, true, false));
    }

    @Test
    public void theTickBeatsTheOldInstance() {
        assertEquals(Source.TICK, DeathRules.source(true, false, true));
    }

    @Test
    public void everyWitnessCountsOnItsOwn() {
        for (int bits = 1; bits < 8; bits++) {
            assertTrue("bits " + bits, DeathRules.seen((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0));
        }
        assertFalse(DeathRules.seen(false, false, false));
    }
}
