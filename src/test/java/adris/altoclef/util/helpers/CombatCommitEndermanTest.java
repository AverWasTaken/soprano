package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import java.util.List;
import org.junit.Test;

// an angry enderman is fought to the end, never run from. water at low hp is the chain's (EndermanWater)
public class CombatCommitEndermanTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe enderman(double distance, long sinceHit) {
        return new Foe(5, distance, false, false, sinceHit, Kind.ENDERMAN);
    }

    private static Tick tick(long now, float health, List<Foe> foes, Foe target) {
        return new Tick(now, health, 0, 0, foes, target, Way.NONE);
    }

    @Test
    public void theKindComesFromTheType() {
        assertEquals(Kind.ENDERMAN, FoeRules.kindOf(Dimension.END, "enderman"));
        assertEquals(Kind.NORMAL, FoeRules.kindOf(Dimension.END, "zombie"));
    }

    @Test
    public void anEndermanThatHitUsIsAFightEvenAtThreeHp() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(2, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 3, List.of(e), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theFightNeverTurnsIntoARunAsTheHpDrops() {
        CombatCommit c = new CombatCommit();
        Foe e = enderman(2, 0);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(e), null)));
        for (long t = 1; t < 50; t++) {
            float hp = Math.max(1, 20 - t);
            assertEquals(Event.NONE, c.step(tick(t, hp, List.of(e), e)));
            assertEquals(Mode.FIGHT, c.mode());
        }
    }

    @Test
    public void threeEndermenOnUsIsNotACrowdRun() {
        CombatCommit c = new CombatCommit();
        Foe a = new Foe(1, 2, false, false, 0, Kind.ENDERMAN);
        Foe b = new Foe(2, 2, false, false, NEVER, Kind.ENDERMAN);
        Foe d = new Foe(3, 2, false, false, NEVER, Kind.ENDERMAN);
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(a, b, d), null)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void aZombieAtLowHpIsStillARun() {
        CombatCommit c = new CombatCommit();
        Foe z = new Foe(1, 2, false, false, 0);
        assertEquals(Event.RUN_START, c.step(tick(0, 5, List.of(z), null)));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void anEndermanBeingNearAtLowHpAndNotHittingUsStartsNothing() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(0, 4, List.of(enderman(3, NEVER)), null)));
    }

    @Test
    public void waterOnlyAtTheFloorWithABucketAndNotInTheNether() {
        double near = 3;
        assertTrue(EndermanWater.dunkNow(6, near, true, false, false));
        assertTrue(EndermanWater.dunkNow(2, near, true, false, false));
        assertFalse(EndermanWater.dunkNow(7, near, true, false, false));
        assertFalse(EndermanWater.dunkNow(4, near, false, false, false));
        assertFalse(EndermanWater.dunkNow(4, near, true, true, false));
        assertFalse(EndermanWater.dunkNow(4, near, true, false, true));
        assertFalse(EndermanWater.dunkNow(4, Double.POSITIVE_INFINITY, true, false, false));
        assertFalse(EndermanWater.dunkNow(4, EndermanWater.NEAR + 1, true, false, false));
    }

    @Test
    public void theWaterStaysWhileTheEndermanIsAbout() {
        assertTrue(EndermanWater.keepWater(3));
        assertFalse(EndermanWater.keepWater(Double.POSITIVE_INFINITY));
    }

    @Test
    public void anEndermanThatHitUsBarehandedIsStillAFightNotAnUnarmedRun() {
        CombatCommit c = new CombatCommit();
        Tick t = new Tick(0, 20, 0, 0, List.of(enderman(2, 0)), null, Way.NONE, false);
        assertEquals(Event.FIGHT_START, c.step(t));
        assertEquals(Event.RUN_START, new CombatCommit().step(new Tick(0, 20, 0, 0, List.of(new Foe(1, 2, false, false, 0)), null, Way.NONE, false)));
    }
}
