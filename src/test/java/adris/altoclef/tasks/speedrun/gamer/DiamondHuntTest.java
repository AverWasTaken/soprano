package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiamondHuntTest {
    private static final KitNeed DIG = new KitNeed(KitPlanner.DIAMOND, 3);
    private static final KitNeed PICK = new KitNeed("diamond_pickaxe", 1);
    private static final KitNeed WOOL = new KitNeed("wool", 24);

    @Test
    public void onlyTheStretchBetweenTwoDigTicksCounts() {
        double hunted = DiamondHunt.add(0, -1, 100);
        assertEquals("the first dig tick starts the clock, it bills nothing", 0, hunted, 0);
        hunted = DiamondHunt.add(hunted, 100, 100.05);
        hunted = DiamondHunt.add(hunted, 100.05, 160);
        assertEquals(60, hunted, 1e-9);
        assertEquals("a clock that went back adds nothing", 60, DiamondHunt.add(hunted, 160, 150), 1e-9);
    }

    @Test
    public void heldTimeIsNotBilled() {
        // secondsInPhase hands the death walk's held time back, so over the walk the age does not move and nothing is billed
        assertEquals(10, DiamondHunt.add(10, 200, 200), 0);
        // a tick where something else led the plan drops lastAge, so the gap after it is not the dig's either
        assertEquals(10, DiamondHunt.add(10, -1, 500), 0);
    }

    @Test
    public void theClockSurvivesARetry() {
        // 5 minutes on the first attempt. the retry starts the attempt clock at 0 and IronPhase.onEnter drops lastAge
        double hunted = DiamondHunt.add(0, 600, 900);
        assertEquals(300, hunted, 0);
        hunted = DiamondHunt.add(hunted, -1, 0);
        hunted = DiamondHunt.add(hunted, 0, 180);
        assertEquals(480, hunted, 0);
        assertTrue("8 minutes all told is the end of it", DiamondHunt.over(hunted, 8, 22, 180, false));
        assertFalse(DiamondHunt.over(hunted - 1, 8, 22, 180, false));
    }

    @Test
    public void theBudgetLeavesNinetySecondsWhenNothingFollows() {
        double budget = 22;
        assertFalse(DiamondHunt.over(0, 8, budget, budget * 60 - 90, false));
        assertTrue(DiamondHunt.over(0, 8, budget, budget * 60 - 89, false));
    }

    @Test
    public void theBudgetLeavesThreeMinutesWhenWoolStillFollows() {
        double budget = 22;
        assertFalse(DiamondHunt.over(0, 8, budget, budget * 60 - 180, true));
        assertTrue(DiamondHunt.over(0, 8, budget, budget * 60 - 179, true));
        // the same moment with nothing behind the dig carries on
        assertFalse(DiamondHunt.over(0, 8, budget, budget * 60 - 179, false));
    }

    @Test
    public void aDigThatStartsUnderTheMarginIsOverAtOnce() {
        assertTrue(DiamondHunt.over(0, 8, 22, 22 * 60 - 30, false));
        assertFalse("no budget is no budget", DiamondHunt.over(0, 8, 0, 99999, true));
    }

    @Test
    public void thePicksOwnCraftIsNotSomethingAfterTheDig() {
        assertFalse(DiamondHunt.moreAfter(List.of(DIG)));
        assertFalse(DiamondHunt.moreAfter(List.of(DIG, PICK)));
        assertTrue(DiamondHunt.moreAfter(List.of(DIG, WOOL)));
        assertTrue(DiamondHunt.moreAfter(List.of(DIG, PICK, new KitNeed(KitNeed.FOOD, 100))));
        assertFalse("nothing is after a dig that is not there", DiamondHunt.moreAfter(List.of(WOOL)));
    }

    @Test
    public void diggingIsTheDigAtTheHead() {
        assertTrue(DiamondHunt.digging(List.of(DIG, WOOL)));
        assertFalse(DiamondHunt.digging(List.of(WOOL, DIG)));
        assertFalse(DiamondHunt.digging(List.of()));
        assertFalse(DiamondHunt.digging(null));
    }
}
