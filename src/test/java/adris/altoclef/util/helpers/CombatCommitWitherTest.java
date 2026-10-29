package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

// an aware wither skeleton with a line on us inside 5 blocks is a fight we start in the Nether, blazes watching or not
public class CombatCommitWitherTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe wither(int id, double distance, boolean sight) {
        return new Foe(id, distance, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, sight, NEVER).withWither(true);
    }

    private static Foe blaze() {
        return new Foe(2, 8, true, false, NEVER, Kind.FLYER, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Tick tick(long now, float hp, List<Foe> foes, Foe target, boolean armed) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, armed);
    }

    @Test
    public void startsOnAWitherSkeletonWithALineWhileABlazeWatches() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(0, 20, List.of(wither(1, 4.5, true), blaze()), null, true)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.IN_WAY, c.why());
        assertEquals(1, c.targetId());
    }

    @Test
    public void startsInsideTheCooldownToo() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(wither(1, 4, true)), null, true));
        // it died: the cooldown is on
        c.step(tick(1, 20, List.of(), null, true));
        assertEquals(Mode.NONE, c.mode());
        assertEquals(true, c.coolingDown(5));
        assertEquals(Event.FIGHT_START, c.step(tick(5, 20, List.of(wither(3, 4, true)), null, true)));
        assertEquals(3, c.targetId());
    }

    @Test
    public void notBeyondFiveBlocksNotWithoutALineNotBareHanded() {
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(wither(1, 5.5, true)), null, true)));
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(wither(1, 4, false)), null, true)));
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(wither(1, 4, true)), null, false)));
    }

    @Test
    public void notWhenLow() {
        // (hurt it is the low hp run's call, what matters is that it is not a fight we pick)
        CombatCommit c = new CombatCommit();
        org.junit.Assert.assertNotEquals(Event.FIGHT_START, c.step(tick(0, 4, List.of(wither(1, 4, true)), null, true)));
    }

    @Test
    public void notWithAPickaxeInHand() {
        Way pick = new Way(java.util.Set.of(), false, 0, false);
        assertEquals(Event.NONE, new CombatCommit().step(new Tick(0, 20, 0, 0, List.of(wither(1, 4, true)), null, pick, true)));
    }

    @Test
    public void aSkeletonThatIsNotMarkedStaysScenery() {
        // only the Nether marks one, an overworld skeleton is the shooter rules' business
        Foe plain = new Foe(1, 4, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
        assertEquals(Event.NONE, new CombatCommit().step(tick(0, 20, List.of(plain), null, true)));
    }

    @Test
    public void onlyTheNetherMarksAWitherSkeleton() {
        FoeRules.Candidate c = new FoeRules.Candidate("wither_skeleton", 3, 5, () -> false, () -> true, () -> true, NEVER, 0, false, false);
        org.junit.Assert.assertTrue(FoeRules.accept(Dimension.NETHER, c).wither());
        org.junit.Assert.assertFalse(FoeRules.accept(Dimension.OVERWORLD, c).wither());
    }

    @Test
    public void theFightHoldsThroughAKnockbackBlip() {
        CombatCommit c = new CombatCommit();
        c.step(tick(0, 20, List.of(wither(1, 3, true), blaze()), null, true));
        Foe far = wither(1, 4.6, true);
        for (long t = 1; t < 20; t++) {
            assertEquals(Event.NONE, c.step(tick(t, 20, List.of(far, blaze()), far, true)));
        }
        assertEquals(Mode.FIGHT, c.mode());
        Foe back = wither(1, 2, true);
        assertEquals(Event.NONE, c.step(tick(20, 20, List.of(back, blaze()), back, true)));
        assertEquals(Mode.FIGHT, c.mode());
    }
}
