package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.DeathRules.Source;
import baritone.api.utils.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

// who gets to say a new player instance was a death. the three witnesses are plain booleans here
public class DeathRulesTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

    // ---- is the pile worth the walk

    private static final int FOOD_LINE = 45;

    private static DeathRules.Worth rich() {
        return new DeathRules.Worth(35, 1, true, 120);
    }

    private static String why(double blocks, DeathRules.Worth worth, int killedHere) {
        return DeathRules.refuse(true, blocks, 1000, worth, FOOD_LINE, killedHere);
    }

    @Test
    public void aNearRichPileIsWorthTheWalk() {
        assertNull(why(80, rich(), 0));
    }

    @Test
    public void aFarRichPileWithinReachIsStillWorthIt() {
        assertNull(why(469, rich(), 0));
        assertNull(why(1000, rich(), 0));
    }

    @Test
    public void pastTheReachItIsLeftAndSaysSo() {
        assertEquals("1001 blocks is further than the 1000 we walk", why(1001, rich(), 0));
    }

    @Test
    public void aPoorPileIsLeftAtAnyDistance() {
        DeathRules.Worth poor = new DeathRules.Worth(0, 0, false, 6);
        assertEquals("the bag was nearly empty (6 food)", why(40, poor, 0));
        assertEquals("the bag was nearly empty (nothing much)", why(600, new DeathRules.Worth(0, 0, false, 0), 0));
        // two iron is a smelt away from nothing, a handful of food is a hunt
        assertTrue(why(40, new DeathRules.Worth(2, 0, false, FOOD_LINE - 1), 0).startsWith("the bag was nearly empty"));
    }

    @Test
    public void eachKindOfRichIsRichOnItsOwn() {
        assertNull(why(500, new DeathRules.Worth(3, 0, false, 0), 0));
        assertNull(why(500, new DeathRules.Worth(0, 1, false, 0), 0));
        assertNull(why(500, new DeathRules.Worth(0, 0, false, FOOD_LINE), 0));
    }

    @Test
    public void aBarePickIsOnlyWorthAShortWalk() {
        DeathRules.Worth pick = new DeathRules.Worth(0, 0, true, 10);
        assertNull(why(DeathRules.NEAR_BLOCKS, pick, 0));
        assertTrue(why(DeathRules.NEAR_BLOCKS + 1, pick, 0).startsWith("the bag was nearly empty"));
    }

    @Test
    public void aSpotThatHasKilledUsBeforeIsNotWalkedIntoAgainWhateverIsThere() {
        assertEquals("this spot has killed us 2 times", why(80, rich(), 1));
        assertEquals("this spot has killed us 3 times", why(80, rich(), 2));
    }

    @Test
    public void aBagWeNeverSawIsNotAReasonToLeave() {
        assertNull(why(200, null, 0));
    }

    @Test
    public void aPileInAnotherDimensionIsNotThisRulesBusiness() {
        assertEquals("it is in another dimension", DeathRules.refuse(false, 10, 1000, rich(), FOOD_LINE, 0));
    }

    private static RunState.Death death(Dimension dimension, int x, int z) {
        RunState.Death d = new RunState.Death();
        d.dimension = dimension.name();
        d.x = x;
        d.z = z;
        return d;
    }

    @Test
    public void deathsCloseTogetherAreTheSameSpot() {
        List<RunState.Death> before = List.of(death(Dimension.OVERWORLD, 100, 100), death(Dimension.OVERWORLD, 130, 100),
                death(Dimension.OVERWORLD, 400, 100), death(Dimension.NETHER, 110, 100));
        assertEquals(2, DeathRules.killedHere(before, Dimension.OVERWORLD, 110, 100));
        assertEquals(0, DeathRules.killedHere(before, Dimension.OVERWORLD, 250, 100));
        assertEquals(1, DeathRules.killedHere(before, Dimension.NETHER, 110, 100));
        assertEquals(0, DeathRules.killedHere(List.of(), Dimension.OVERWORLD, 0, 0));
        // an old save can have no dimension on an entry, that must not blow up
        assertEquals(0, DeathRules.killedHere(List.of(new RunState.Death()), Dimension.OVERWORLD, 0, 0));
    }

    @Test
    public void whatTheBagHeldComesOffTheFacts() {
        FakeFacts f = new FakeFacts().give(Items.RAW_IRON, 30).give(Items.IRON_INGOT, 5).give(Items.IRON_PICKAXE, 1)
                .give(Items.STONE_AXE, 1);
        f.worn.add(Items.IRON_CHESTPLATE);
        f.foodUnits = 120;
        assertEquals(new DeathRules.Worth(35, 2, true, 120), DeathRules.worth(f));
        assertEquals(new DeathRules.Worth(0, 0, false, 0), DeathRules.worth(new FakeFacts()));
        // a stone axe alone is not a pile
        assertEquals(new DeathRules.Worth(0, 0, false, 0), DeathRules.worth(new FakeFacts().give(Items.STONE_AXE, 1)));
    }

    @Test
    public void theLogLinesSayWhatWasInIt() {
        assertEquals("death: going back for the pile, 469 blocks, 35 iron, 1 iron or better piece, a pick, 120 food",
                DeathRules.going(469.2, rich()));
        assertEquals("death: going back for the pile, 12 blocks", DeathRules.going(12, null));
        assertEquals("death: leaving the pile, it is a long way", DeathRules.leaving("it is a long way"));
    }
}
