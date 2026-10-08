package adris.altoclef.util.helpers;

import static adris.altoclef.util.helpers.CombatRules.Stance.CALM;
import static adris.altoclef.util.helpers.CombatRules.Stance.EAT;
import static adris.altoclef.util.helpers.CombatRules.Stance.EAT_GAPPLE;
import static adris.altoclef.util.helpers.CombatRules.Stance.FIGHT;
import static adris.altoclef.util.helpers.CombatRules.Stance.FLEE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// the piglin that killed the bot: hp 10, hit a second ago, standing right there
public class CombatRulesTest {

    private static final long LONG_AGO = Long.MAX_VALUE / 2;
    private static final double NOBODY = Double.POSITIVE_INFINITY;

    private static CombatRules.Stance stance(float health, double threat, long hurtAgo, boolean gapple) {
        return CombatRules.stance(CombatRules.inCombat(threat, hurtAgo, false), health, threat, gapple);
    }

    @Test
    public void mobRightNextToUsIsACombat() {
        assertTrue(CombatRules.inCombat(2, LONG_AGO, false));
        assertTrue(CombatRules.inCombat(6, LONG_AGO, false));
    }

    @Test
    public void mobTenBlocksAwayIsNot() {
        assertFalse(CombatRules.inCombat(10, LONG_AGO, false));
    }

    @Test
    public void hitRecentlyWithSomethingStillAroundIsACombat() {
        assertTrue(CombatRules.inCombat(10, 20, false));
        assertTrue(CombatRules.inCombat(10, 40, false));
        assertFalse(CombatRules.inCombat(10, 41, false));
    }

    @Test
    public void hitRecentlyButNothingLeftIsOver() {
        // we killed it, there is nobody to fight and nothing stopping us from a sandwich
        assertFalse(CombatRules.inCombat(NOBODY, 5, false));
    }

    @Test
    public void litCreeperIsACombatEvenWithNobodyElse() {
        assertTrue(CombatRules.inCombat(NOBODY, LONG_AGO, true));
    }

    @Test
    public void calmMeansEatWhenYouWant() {
        assertEquals(CALM, stance(10, NOBODY, LONG_AGO, false));
        assertEquals(CALM, stance(2, 12, LONG_AGO, true));
        assertTrue(CALM.mayEat());
    }

    @Test
    public void tenHpPiglinInOurFaceIsAFightNotALunch() {
        // the bug. needsToEat said yes at 10 hp, which turned the defense off, which killed us
        CombatRules.Stance s = stance(10, 1.5, 10, false);
        assertEquals(FIGHT, s);
        assertFalse(s.mayEat());
    }

    @Test
    public void healthyFightStaysAFight() {
        assertEquals(FIGHT, stance(20, 3, 100, false));
        assertEquals(FIGHT, stance(9, 3, 100, false));
    }

    @Test
    public void lowHpInAFightMeansLeave() {
        CombatRules.Stance s = stance(8, 2, 5, false);
        assertEquals(FLEE, s);
        assertFalse(s.mayEat());
        assertEquals(FLEE, stance(5, 2, 5, false));
    }

    @Test
    public void almostDeadWithNothingCloseTakesAQuickBite() {
        CombatRules.Stance s = stance(4, 5, 10, false);
        assertEquals(EAT, s);
        assertTrue(s.mayEat());
    }

    @Test
    public void almostDeadWithSomethingOnUsRuns() {
        assertEquals(FLEE, stance(4, 2, 10, false));
        assertEquals(FLEE, stance(2, 3, 10, false));
    }

    @Test
    public void quickBiteIsNotAnOptionAtEightHp() {
        // 8 hp with a mob five blocks out is still a flee, the bite is for the last four
        assertEquals(FLEE, stance(8, 5, 10, false));
    }

    @Test
    public void gappleInAFightIsEatenWhenHurt() {
        CombatRules.Stance s = stance(8, 1, 3, true);
        assertEquals(EAT_GAPPLE, s);
        assertTrue(s.mayEat());
        assertEquals(EAT_GAPPLE, stance(3, 1, 3, true));
    }

    @Test
    public void gappleIsNotWastedWhileHealthy() {
        assertEquals(FIGHT, stance(16, 1, 3, true));
    }

    @Test
    public void gappleDoesNothingOutsideAFight() {
        assertEquals(CALM, stance(4, NOBODY, LONG_AGO, true));
    }

    @Test
    public void aPriorityNeedsATaskThatIsStillRunning() {
        // "Mob Defense holds the wheel at 70.0 with nothing running"
        assertEquals(70f, CombatRules.wheelPriority(70, true, false), 0);
        assertEquals(0f, CombatRules.wheelPriority(70, false, false), 0);
        assertEquals("a run we were already outside of", 0f, CombatRules.wheelPriority(80, true, true), 0);
        assertEquals("not asking for it is not asking for it", Float.NEGATIVE_INFINITY, CombatRules.wheelPriority(Float.NEGATIVE_INFINITY, false, true), 0);
        assertEquals(0f, CombatRules.wheelPriority(0, false, false), 0);
    }

    @Test
    public void fusingCreeperAtLowHpIsStillAFlee() {
        assertEquals(FLEE, CombatRules.stance(CombatRules.inCombat(NOBODY, LONG_AGO, true), 6, 4, false));
    }
}
