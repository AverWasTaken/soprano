package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.RecoverHold.Body;
import java.util.List;
import org.junit.Test;

// a run in the water with a trident drowned about: "nothing within 12 for two seconds" is it between throws, not gone. the run
// only calls clear once the last ranged hit is SHOT_MEMORY old, and the cap still ends it
public class CombatCommitWetRunTest {

    private static final Body DRY = new Body(20, true, false, false, false, false);
    private static final Body WET = new Body(20, true, false, false, false, true);

    private static Tick tick(long now, float health, double x, Body body, Foe... foes) {
        return new Tick(now, health, x, 0, List.of(foes), null, Way.NONE, true, false, false, body);
    }

    private static Foe zombie(double distance) {
        return new Foe(1, distance, false, false, CombatCommit.NEVER);
    }

    // a low hp run from a zombie at 5 at tick start, then steps from x=30 on, swimming a bit each tick so the stuck rule stays out of it.
    // hits: the ticks a ranged hit lands. returns the tick and the event the run ended on
    private record End(long tick, Event event) {
    }

    private static End run(CombatCommit c, long start, Body body, long... hits) {
        assertEquals(Event.RUN_START, c.step(tick(start, 6, 0, body, zombie(5))));
        int h = 0;
        for (long now = start + 1; now < start + 2 * CombatCommit.RUN_CAP; now++) {
            if (h < hits.length && hits[h] == now) {
                c.rangedHit(now);
                h++;
            }
            Event e = c.step(tick(now, 10, 30 + 0.2 * (now - start), body));
            if (e != Event.NONE) return new End(now, e);
        }
        throw new AssertionError("the run never ended");
    }

    @Test
    public void theRuleNeedsTheWaterAndAFreshHit() {
        assertTrue(CombatCommit.wetShot(true, 1000, 1000));
        assertTrue(CombatCommit.wetShot(true, 1000 + CombatCommit.SHOT_MEMORY, 1000));
        assertFalse(CombatCommit.wetShot(true, 1001 + CombatCommit.SHOT_MEMORY, 1000));
        assertFalse(CombatCommit.wetShot(false, 1000, 1000));
        // nobody ever hit us
        assertFalse(CombatCommit.wetShot(true, 1000, Long.MIN_VALUE / 2));
        // a clock that went backwards is no fresh hit
        assertFalse(CombatCommit.wetShot(true, 900, 1000));
    }

    @Test
    public void aWetRunWaitsOutTheLastHitBeforeCallingClear() {
        CombatCommit c = new CombatCommit();
        c.rangedHit(1000);
        End end = run(c, 1000, WET);
        assertEquals(Event.RUN_CLEAR, end.event());
        // the plain clear would have come at 1041, the hit holds it to the first tick past SHOT_MEMORY
        assertEquals(1001 + CombatCommit.SHOT_MEMORY, end.tick());
    }

    @Test
    public void theSameHitOnDryLandClearsAsBefore() {
        CombatCommit c = new CombatCommit();
        c.rangedHit(1000);
        End end = run(c, 1000, DRY);
        assertEquals(Event.RUN_CLEAR, end.event());
        assertEquals(1001 + CombatCommit.RUN_CLEAR_TICKS, end.tick());
    }

    @Test
    public void aWetRunWithNoHitClearsAsBefore() {
        CombatCommit c = new CombatCommit();
        End end = run(c, 1000, WET);
        assertEquals(Event.RUN_CLEAR, end.event());
        assertEquals(1001 + CombatCommit.RUN_CLEAR_TICKS, end.tick());
    }

    @Test
    public void hitsThatKeepComingRunIntoTheCapAndNoHoldAfloat() {
        CombatCommit c = new CombatCommit();
        c.rangedHit(1000);
        long[] hits = new long[(int) (CombatCommit.RUN_CAP / 50)];
        for (int i = 0; i < hits.length; i++) hits[i] = 1050 + 50L * i;
        End end = run(c, 1000, WET, hits);
        assertEquals(Event.RUN_CAP, end.event());
        assertEquals(1000 + CombatCommit.RUN_CAP, end.tick());
        // ended hurt in the water: the walk gets the wheel, not a heal hold next to the drowned
        assertFalse(c.recovering());
    }
}
