package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatRules.Stance;
import org.junit.Test;

// who may eat while we are committed. the stance is what the food chain asks, so this is the whole eating policy
public class CombatCommitStanceTest {

    private static final double NOBODY = Double.POSITIVE_INFINITY;

    @Test
    public void nobodyCommittedAndNothingWrongIsAMealtime() {
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 20, NOBODY, false, false));
        // a zombie ten blocks off is scenery, lunch carries on
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 12, 10, false, false));
        assertTrue(CombatCommit.stance(Mode.NONE, 12, 5, false, false).mayEat());
    }

    @Test
    public void aFightIsNeverAMealtime() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 20, 2, false, false));
        // even when the target wandered off the list, we are still in it
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 20, NOBODY, false, false));
        assertFalse(CombatCommit.stance(Mode.FIGHT, 20, 2, false, false).mayEat());
    }

    @Test
    public void aRunWithSomethingCloseDoesNotStopForSnacks() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.RUN, 20, 8, false, false));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 6, 8, false, false));
        assertFalse(CombatCommit.stance(Mode.RUN, 6, 5, false, false).mayEat());
    }

    // the run stays through a bite and the food chain does it in the background, once the nearest thing angry at us is past
    // the bite clearance. closer than that chewing (walking at a fraction of the speed) just hands them the gap
    @Test
    public void aRunMayEatOnceNothingAngryIsWithinEight() {
        assertTrue(CombatCommit.stance(Mode.RUN, 20, 12.5, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 5, 30, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 3, NOBODY, false, false).mayEat());
        assertTrue(CombatCommit.stance(Mode.RUN, 7, 8.5, false, false).mayEat());
    }

    @Test
    public void aFollowerBetweenEightAndTwelveMayNowWatchUsEat() {
        // (this used to be a no meal band all the way out to the run's own clear line, so a hurt bot with a spider on its
        // heels never ate at all)
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 20, 11, false, false));
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 7, 9, false, false));
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.RUN, 7, CombatCommit.RUN_CLEAR, false, false));
        // and the moment it closes to the line the bite is off again
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, CombatRules.RUN_BITE_CLEARANCE, false, false));
        assertFalse(CombatCommit.stance(Mode.RUN, 20, CombatRules.RUN_BITE_CLEARANCE, false, false).mayEat());
    }

    @Test
    public void aShooterWithALineOnUsKeepsTheRunOffTheMenu() {
        assertFalse(CombatCommit.stance(Mode.RUN, 7, 11, false, false, true).mayEat());
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, NOBODY, false, false, true));
        // not shot at, same spot: lunch
        assertTrue(CombatCommit.stance(Mode.RUN, 7, 11, false, false, false).mayEat());
    }

    @Test
    public void theBiteLineIsEightBlocksAndShootersCountFromFifteen() {
        assertEquals(8, CombatRules.RUN_BITE_CLEARANCE, 0);
        assertEquals(15, CombatRules.SHOOTER_RANGE, 0);
    }

    // the quick bite at hp 4 means "nothing next to us". committed, something is within 8, so it is a flee
    @Test
    public void theQuickBiteBecomesAFleeWhileCommitted() {
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.RUN, 3, 6, false, false));
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.FIGHT, 3, NOBODY, false, false));
        // not committed (a cooldown, a hurt walk) the old quick bite stands
        assertEquals(Stance.EAT, CombatCommit.stance(Mode.NONE, 3, 6, false, false));
    }

    @Test
    public void aGappleIsWhatGapplesAreFor() {
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.RUN, 6, 5, false, true));
        assertEquals(Stance.EAT_GAPPLE, CombatCommit.stance(Mode.NONE, 6, 5, false, true));
        // healthy, a gapple is not a snack
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.FIGHT, 18, 3, false, true));
    }

    @Test
    public void hurtWithSomethingNearIsNotAMealtimeEvenWithoutACommitment() {
        assertEquals(Stance.FLEE, CombatCommit.stance(Mode.NONE, 7, 6, false, false));
        // hurt but the nearest is past the line: eat
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, 7, 9, false, false));
    }

    // the shape of the cave sequence in neutral terms: hurt but not hungry enough to regenerate, nothing around, food in the bag
    @Test
    public void hurtAndCalmWithFoodInTheBagIsAMealtimeThatTheHealRuleWants() {
        float hp = 14;
        int hunger = 15;
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, hp, NOBODY, false, false));
        assertTrue(CombatRules.healingMeal(hp, hunger));
        // a skeleton ten blocks off that has not touched us is scenery, the meal goes ahead
        assertEquals(Stance.CALM, CombatCommit.stance(Mode.NONE, hp, 10, false, false));
    }

    // a run that ends clear leaves a calm, hurt bot, and the heal rule has it eat before the user task walks on
    @Test
    public void aRunThatEndsClearThenEatsUpToTheRegenLineBeforeWork() {
        CombatCommit c = new CombatCommit();
        long t = 1000;
        float hp = 6;
        int hunger = 15;
        // hurt with a zombie close: a run
        assertEquals(CombatCommit.Event.RUN_START, c.step(new CombatCommit.Tick(t, hp, 0, 0,
                java.util.List.of(new CombatCommit.Foe(1, 5, false, false, 3)), null)));
        // still on our heels at 6: feet only
        assertEquals(Stance.FLEE, CombatCommit.stance(c.mode(), hp, 6, false, false));
        // it falls back to 10 and the bot may chew while it walks on
        assertEquals(Stance.CALM, CombatCommit.stance(c.mode(), hp, 10, false, false));
        assertTrue(CombatRules.healingMeal(hp, hunger));
        // and it closes to 7: the bite stops
        assertEquals(Stance.FLEE, CombatCommit.stance(c.mode(), hp, 7, false, false));
        // far away and quiet for two seconds, 30 blocks out: the run is over
        CombatCommit.Event last = CombatCommit.Event.NONE;
        for (long now = t + 1; now <= t + 100 && c.mode() == Mode.RUN; now++) {
            last = c.step(new CombatCommit.Tick(now, hp, 30, 0, java.util.List.of(), null));
        }
        assertEquals(CombatCommit.Event.RUN_CLEAR, last);
        // calm again, hurt, food in the bag: a meal, then the user task
        assertEquals(Stance.CALM, CombatCommit.stance(c.mode(), hp, NOBODY, false, false));
        assertTrue(CombatRules.healingMeal(hp, hunger));
        // at 18 the bar regenerates by itself, the heal rule is done
        assertFalse(CombatRules.healingMeal(hp, CombatRules.REGEN_HUNGER));
    }

    @Test
    public void whyTheStanceIsWhatItIsSaysSo() {
        assertEquals("running, nearest 6 blocks, too close to eat", CombatCommit.stanceWhy(Mode.RUN, 6, 6, false, false));
        assertEquals("running, nearest 10 blocks, free to eat", CombatCommit.stanceWhy(Mode.RUN, 6, 10, false, false));
        assertEquals("running, a shooter has a line on us", CombatCommit.stanceWhy(Mode.RUN, 6, 10, false, true));
        assertEquals("fighting, no meals mid fight", CombatCommit.stanceWhy(Mode.FIGHT, 15, 2, false, false));
        assertEquals("a lit creeper is close", CombatCommit.stanceWhy(Mode.NONE, 15, NOBODY, true, false));
        assertEquals("hp 7 with something within 8, nearest 5 blocks", CombatCommit.stanceWhy(Mode.NONE, 7, 5, false, false));
        assertEquals("calm, nothing angry in sight", CombatCommit.stanceWhy(Mode.NONE, 15, NOBODY, false, false));
    }

    @Test
    public void aLitCreeperCloseKeepsUsOffTheMenu() {
        assertEquals(Stance.FIGHT, CombatCommit.stance(Mode.NONE, 20, NOBODY, true, false));
        assertFalse(CombatCommit.stance(Mode.NONE, 20, NOBODY, true, false).mayEat());
    }
}
