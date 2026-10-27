package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import java.util.Set;
import org.junit.Test;

// a cube or an angry piglin walking up to us on a lava rim is a fight before its first hit (its first hit is the knock into the
// lava), and a run along a rim keeps a leashed shield up for it
public class CombatCommitRimTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final Way RIM = new Way(Set.of(), true, 64).withRim(true);

    private static Foe cube(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER).withKnocker(true);
    }

    private static Tick tick(long now, float health, List<Foe> foes, Foe target, Way way) {
        return new Tick(now, health, 0, 0, foes, target, way);
    }

    @Test
    public void aCubeCloseOnARimIsAFightBeforeItHits() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(cube(1, 2.5)), null, RIM)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.RIM, c.why());
        assertEquals(1, c.targetId());
        assertTrue(CombatLog.line(baritone.api.utils.Dimension.NETHER, Event.FIGHT_START, Why.RIM, false, "magma_cube", "", "", 20, 0).contains("(on a rim,"));
    }

    @Test
    public void theSameCubeOffTheRimIsNothing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(cube(1, 2.5)), null, new Way(Set.of(), true, 64))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCubeFarOnARimIsNothingYet() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(cube(1, 3.5)), null, RIM)));
    }

    @Test
    public void aHoglinOnARimIsNotEngaged() {
        CombatCommit c = new CombatCommit();
        Foe hoglin = new Foe(1, 2.5, false, false, NEVER, Kind.HEAVY).withKnocker(true);
        c.step(tick(0, 20, List.of(hoglin), null, RIM));
        assertTrue(c.why() != Why.RIM);
    }

    @Test
    public void aPlainZombieOnARimIsNotEngaged() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 20, List.of(new Foe(1, 2.5, false, false, NEVER)), null, RIM)));
    }

    @Test
    public void lowHpOnARimIsNotAFightToPick() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 8, List.of(cube(1, 2.5)), null, RIM));
        assertTrue(c.why() != Why.RIM);
    }

    @Test
    public void aCrowdOnARimIsStillTheCrowdRule() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(cube(1, 2), cube(2, 2.2), cube(3, 2.4)), null, RIM));
        assertTrue(c.why() != Why.RIM);
    }

    @Test
    public void theRimFightLetsGoOnceItIsPastFourAndNotHitting() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(cube(1, 2.5)), null, RIM));
        Foe far = cube(1, 4.5);
        assertEquals(Event.FIGHT_CLEARED, c.step(tick(1, 20, List.of(far), far, RIM)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aRunOnARimWantsTheCubeAtTwo() {
        List<Foe> foes = List.of(cube(1, 2));
        Foe pick = CombatRules.rimRunFoe(true, foes);
        assertNotNull(pick);
        assertEquals(1, pick.id());
        assertNull(CombatRules.rimRunFoe(false, foes));
        assertNull(CombatRules.rimRunFoe(true, List.of(cube(1, 3.5))));
    }

    @Test
    public void aRunOnARimAlsoWantsAnythingPlainThatHitUs() {
        Foe zombie = new Foe(1, 2, false, false, 5);
        assertNotNull(CombatRules.rimRunFoe(true, List.of(zombie)));
        assertNull(CombatRules.rimRunFoe(true, List.of(new Foe(1, 2, false, false, NEVER))));
        assertNull(CombatRules.rimRunFoe(true, List.of(new Foe(1, 2, false, false, 5, Kind.HEAVY))));
    }

    // the brain calls pauseCorner every tick the run shield is up: standing still behind it is not being cornered, and the window
    // starts fresh when it lets go
    @Test
    public void aShieldedStandStillIsNotCorneredAndTheWindowStartsOverAfter() {
        CombatCommit c = new CombatCommit();
        Foe z = new Foe(1, 1.5, false, false, NEVER);
        Way way = new Way(Set.of(), true, 64);
        assertEquals(Event.RUN_START, c.step(tick(100, 6, List.of(z), null, way)));
        long shieldEnd = 101 + 3 * CombatCommit.CORNER_TICKS;
        for (long t = 101; t < shieldEnd; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 6, List.of(z), null, way)));
            c.pauseCorner();
        }
        assertEquals(Mode.RUN, c.mode());
        Event event = Event.NONE;
        long t = shieldEnd;
        for (; t <= shieldEnd + 2 * CombatCommit.CORNER_TICKS && event == Event.NONE; t++) {
            event = c.step(tick(t, 6, List.of(z), null, way));
        }
        assertEquals(Event.RUN_TO_FIGHT, event);
        assertTrue(t - shieldEnd >= CombatCommit.CORNER_TICKS);
    }

    @Test
    public void theRunShieldGivesUpAfterTheLeash() {
        RimHold hold = new RimHold();
        for (int i = 0; i < CombatRules.RIM_HOLD_TICKS; i++) assertTrue(hold.step(7));
        assertFalse(hold.step(7));
        assertFalse(hold.step(7));
        // another foe does not start a new leash, nobody wanting it does
        assertFalse(hold.step(8));
        assertFalse(hold.step(-1));
        assertTrue(hold.step(7));
    }

    @Test
    public void twoCubesTakingTurnsDoNotMakeAFreshLeashEachSwap() {
        RimHold hold = new RimHold();
        int up = 0;
        for (int i = 0; i < 3 * CombatRules.RIM_HOLD_TICKS; i++) {
            if (hold.step(i % 2 == 0 ? 7 : 8)) up++;
        }
        assertEquals(CombatRules.RIM_HOLD_TICKS, up);
    }
}
