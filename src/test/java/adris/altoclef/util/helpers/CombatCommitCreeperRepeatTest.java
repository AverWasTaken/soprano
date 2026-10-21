package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// a creeper the step already dealt with lighting up beside us again is the run's, not a third lap of the step
public class CombatCommitCreeperRepeatTest {

    private static final long NOW = 1000;

    private static Foe creeper(double distance) {
        return new Foe(9, distance, false, true, CombatCommit.NEVER);
    }

    private static Foe zombie(double distance, long sinceHit) {
        return new Foe(1, distance, false, false, sinceHit);
    }

    private static Tick tick(long now, Foe target, boolean repeat, Foe... foes) {
        return new Tick(now, 20, 0, 0, List.of(foes), target, Way.NONE, true, false, false, repeat);
    }

    @Test
    public void aCreeperThatCameBackIsARun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, null, true, creeper(2.5))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.REPEAT_CREEPER, c.why());
    }

    @Test
    public void theSameCreeperWithoutTheRepeatIsScenery() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(NOW, null, false, creeper(2.5))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theCooldownDoesNotStopIt() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, null, false, z)));
        // the zombie is dead, the cooldown is on
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, null, false)));
        assertEquals(Event.RUN_START, c.step(tick(NOW + 2, null, true, creeper(2.5))));
        assertEquals(Why.REPEAT_CREEPER, c.why());
    }

    @Test
    public void aFightIsTillDeadCreeperOrNot() {
        CombatCommit c = new CombatCommit();
        Foe z = zombie(1.5, 0);
        c.step(tick(NOW, null, false, z));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, z, true, z, creeper(2.5))));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theRunLineSaysWhy() {
        assertEquals("a creeper that keeps coming back, hp 20", CombatLog.runWhy(Why.REPEAT_CREEPER, 20));
    }
}
