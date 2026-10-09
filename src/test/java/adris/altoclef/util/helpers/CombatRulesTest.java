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

// the piglin that killed the bot: hp 10, standing right there
public class CombatRulesTest {

    private static final double NOBODY = Double.POSITIVE_INFINITY;

    // in a fight with the nearest thing at `threat` blocks
    private static CombatRules.Stance fighting(float health, double threat, boolean gapple) {
        return CombatRules.stance(true, health, threat, gapple);
    }

    @Test
    public void calmMeansEatWhenYouWant() {
        assertEquals(CALM, CombatRules.stance(false, 10, NOBODY, false));
        assertEquals(CALM, CombatRules.stance(false, 2, 12, true));
        assertTrue(CALM.mayEat());
    }

    @Test
    public void tenHpPiglinInOurFaceIsAFightNotALunch() {
        // the bug. needsToEat said yes at 10 hp, which turned the defense off, which killed us
        CombatRules.Stance s = fighting(10, 1.5, false);
        assertEquals(FIGHT, s);
        assertFalse(s.mayEat());
    }

    @Test
    public void healthyFightStaysAFight() {
        assertEquals(FIGHT, fighting(20, 3, false));
        assertEquals(FIGHT, fighting(9, 3, false));
    }

    @Test
    public void lowHpInAFightMeansLeave() {
        CombatRules.Stance s = fighting(8, 2, false);
        assertEquals(FLEE, s);
        assertFalse(s.mayEat());
        assertEquals(FLEE, fighting(5, 2, false));
    }

    @Test
    public void almostDeadWithNothingCloseTakesAQuickBite() {
        CombatRules.Stance s = fighting(4, 5, false);
        assertEquals(EAT, s);
        assertTrue(s.mayEat());
    }

    @Test
    public void almostDeadWithSomethingOnUsRuns() {
        assertEquals(FLEE, fighting(4, 2, false));
        assertEquals(FLEE, fighting(2, 3, false));
    }

    @Test
    public void quickBiteIsNotAnOptionAtEightHp() {
        // 8 hp with a mob five blocks out is still a flee, the bite is for the last four
        assertEquals(FLEE, fighting(8, 5, false));
    }

    @Test
    public void gappleInAFightIsEatenWhenHurt() {
        CombatRules.Stance s = fighting(8, 1, true);
        assertEquals(EAT_GAPPLE, s);
        assertTrue(s.mayEat());
        assertEquals(EAT_GAPPLE, fighting(3, 1, true));
    }

    @Test
    public void gappleIsNotWastedWhileHealthy() {
        assertEquals(FIGHT, fighting(16, 1, true));
    }

    @Test
    public void gappleDoesNothingOutsideAFight() {
        assertEquals(CALM, CombatRules.stance(false, 4, NOBODY, true));
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
        assertEquals(FLEE, fighting(6, 4, false));
    }

    // ---- the one hp line

    @Test
    public void theFleeLineIsEight() {
        assertEquals(8, CombatRules.FLEE_HEALTH, 0);
    }

    @Test
    public void theCommitmentAndTheFleeStanceUseTheSameLine() {
        assertEquals(CombatRules.FLEE_HEALTH, CombatCommit.FLEE_HP, 0);
        // hp 8 is a flee in the stance and a run in the machine, hp 8.5 is neither
        assertEquals(FLEE, fighting(CombatRules.FLEE_HEALTH, 2, false));
        assertEquals(FIGHT, fighting(CombatRules.FLEE_HEALTH + 0.5f, 2, false));
        assertEquals(CombatCommit.Event.RUN_START, new CombatCommit().step(new CombatCommit.Tick(100, CombatRules.FLEE_HEALTH, true, 0, 0,
                3, java.util.List.of(new CombatCommit.Foe(1, 2, false, false, CombatCommit.NEVER)), null, false)));
        assertEquals(CombatCommit.Event.FIGHT_START, new CombatCommit().step(new CombatCommit.Tick(100, CombatRules.FLEE_HEALTH + 0.5f, true, 0, 0,
                3, java.util.List.of(new CombatCommit.Foe(1, 2, false, false, CombatCommit.NEVER)), null, false)));
    }

    @Test
    public void theAuraShieldGateIsTheSameLine() {
        // KillAura asks aboveFleeLine and nothing else: 8 is not healthy enough, 8.5 is, and the old 10 gate is gone
        assertFalse(CombatRules.aboveFleeLine(CombatRules.FLEE_HEALTH));
        assertTrue(CombatRules.aboveFleeLine(CombatRules.FLEE_HEALTH + 0.5f));
        assertTrue(CombatRules.aboveFleeLine(9));
        assertFalse(CombatRules.aboveFleeLine(0));
    }

    @Test
    public void theHeavyLineIsTheOnlyExceptionAndItIsAboveTheFleeLine() {
        assertEquals(10, CombatRules.HEAVY_FLEE_HEALTH, 0);
        assertEquals(CombatRules.HEAVY_FLEE_HEALTH, CombatCommit.HEAVY_FLEE_HP, 0);
        assertTrue(CombatRules.HEAVY_FLEE_HEALTH > CombatRules.FLEE_HEALTH);
    }

    @Test
    public void theSharedRangesAreWhereTheMachineReadsThem() {
        assertEquals(3, CombatRules.CONTACT_RANGE, 0);
        assertEquals(6, CombatRules.SWARM_RANGE, 0);
        assertEquals(8, CombatRules.LOW_HP_RANGE, 0);
        assertEquals(7, CombatRules.CREEPER_NO_IGNORE, 0);
        assertEquals(10, CombatRules.CREEPER_RANGE, 0);
        assertEquals(CombatRules.CONTACT_RANGE, CombatCommit.CONTACT, 0);
        assertEquals(CombatRules.SWARM_RANGE, CombatCommit.CROWD_RANGE, 0);
        assertEquals(CombatRules.LOW_HP_RANGE, CombatCommit.LOW_HP_RANGE, 0);
        // the stall tracker uses the same two, a mob in contact is never stuck and one on our heels while we run neither
        assertEquals(CombatRules.CONTACT_RANGE, MobReachRules.STALL_EXEMPT_RANGE, 0);
        assertEquals(CombatRules.LOW_HP_RANGE, MobReachRules.FLEEING_STALL_RANGE, 0);
    }
}
