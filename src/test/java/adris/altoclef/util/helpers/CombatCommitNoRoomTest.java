package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import baritone.api.utils.Dimension;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// a run that goes nowhere with a melee foe in real reach is the cornered fight now, not after the three second window
public class CombatCommitNoRoomTest {

    private static final long NOW = 1000;
    private static final long FIRES = NOW + CombatCommit.NO_ROOM_GRACE + CombatCommit.NO_ROOM_TICKS;

    private static Foe measured(int id, double distance, double gap) {
        return new Foe(id, distance, false, false, CombatCommit.NEVER, Kind.NORMAL, gap);
    }

    private static Tick runTick(long now, double x, double z, Foe... foes) {
        return new Tick(now, 20, x, z, List.of(foes), null);
    }

    private static CombatCommit runFrom(long now) {
        CombatCommit c = new CombatCommit();
        Foe far = new Foe(900, 8, false, false, CombatCommit.NEVER);
        assertEquals(Event.RUN_START, c.step(new Tick(now, 8, 0, 0, List.of(far), null)));
        return c;
    }

    private static void holds(CombatCommit c, long from, long to, LongFunction<Tick> scene) {
        for (long now = from; now <= to; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(scene.apply(now)));
        }
    }

    @Test
    public void aStandingRunWithAMobOnTopOfUsIsAFightAfterTheGraceAndTheWindow() {
        CombatCommit c = runFrom(NOW);
        Foe onUs = measured(7, 1.8, 0.2);
        holds(c, NOW + 1, FIRES - 1, now -> runTick(now, 0, 0, onUs));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(FIRES, 0, 0, onUs)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(7, c.targetId());
        assertEquals(CombatCommit.Why.CORNERED, c.why());
        assertTrue(c.cornered());
        assertTrue(c.noRoom());
        // way before the three second window would have said so
        assertTrue(FIRES - NOW < CombatCommit.CORNER_TICKS);
    }

    @Test
    public void aRunThatIsMovingNeverTripsIt() {
        CombatCommit c = runFrom(NOW);
        Foe onUs = measured(7, 1.8, 0.2);
        // a trot is 0.2 a tick: two blocks in every window
        holds(c, NOW + 1, NOW + 50, now -> runTick(now, (now - NOW) * 0.2, 0, onUs));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void aFoeNobodyMeasuredLeavesTheOldWindowAlone() {
        CombatCommit c = runFrom(NOW);
        Foe unmeasured = new Foe(7, 1.8, false, false, CombatCommit.NEVER);
        holds(c, NOW + 1, NOW + CombatCommit.CORNER_TICKS, now -> runTick(now, 0, 0, unmeasured));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 1 + CombatCommit.CORNER_TICKS, 0, 0, unmeasured)));
        assertFalse(c.noRoom());
    }

    @Test
    public void aMobOutsideTheRealReachDoesNotCount() {
        CombatCommit c = runFrom(NOW);
        // inside the old four block feet line, outside the three block eye to box reach
        Foe near = measured(7, 3.5, 3.2);
        holds(c, NOW + 1, FIRES + 10, now -> runTick(now, 0, 0, near));
    }

    @Test
    public void shootersCreepersAndWardensAreNotWhatThisRuleFightsFor() {
        CombatCommit c = runFrom(NOW);
        Foe skeleton = new Foe(1, 1.8, true, false, CombatCommit.NEVER, Kind.NORMAL, 0.5);
        Foe creeper = new Foe(2, 1.8, false, true, CombatCommit.NEVER, Kind.NORMAL, 0.5);
        Foe warden = new Foe(3, 1.8, false, false, CombatCommit.NEVER, Kind.UNTOUCHABLE, 0.5);
        holds(c, NOW + 1, FIRES + 5, now -> runTick(now, 0, 0, skeleton, creeper, warden));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theWindowStartsOverWhenTheFoeStepsOutOfReachAndBack() {
        CombatCommit c = runFrom(NOW);
        Foe in = measured(7, 1.8, 0.2);
        Foe out = measured(7, 3.5, 3.2);
        holds(c, NOW + 1, NOW + 18, now -> runTick(now, 0, 0, in));
        holds(c, NOW + 19, NOW + 20, now -> runTick(now, 0, 0, out));
        // back in at NOW + 21: a fresh window, so nothing before NOW + 31
        holds(c, NOW + 21, NOW + 30, now -> runTick(now, 0, 0, in));
        assertEquals(Event.RUN_TO_FIGHT, c.step(runTick(NOW + 31, 0, 0, in)));
    }

    @Test
    public void theLogLineSaysThereWasNoRoom() {
        String plain = CombatLog.line(Dimension.OVERWORLD, Event.RUN_TO_FIGHT, CombatCommit.Why.CORNERED, true, "zombie", "it", "1 zombie", 20, 0);
        String cramped = CombatLog.line(Dimension.OVERWORLD, Event.RUN_TO_FIGHT, CombatCommit.Why.CORNERED, true, true, "zombie", "it", "1 zombie", 20, 0);
        assertEquals("combat: [overworld] cornered, fighting zombie", plain);
        assertEquals("combat: [overworld] cornered, no room, fighting zombie", cramped);
    }
}
