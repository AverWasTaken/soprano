package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.List;
import org.junit.Test;

// a run at low hp that hits its cap with the mobs still on our heels. it used to end, and the low hp rule started a fresh
// run with a fresh origin on the very same second, forever. now: one more window from the same origin, then turn around
public class CombatCommitChasedTest {

    private static final long NOW = 1000;
    private static final long NEVER = CombatCommit.NEVER;
    private static final long CAP = CombatCommit.RUN_CAP;

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit);
    }

    private static Foe zombie(int id, double distance) {
        return zombie(id, distance, NEVER);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, false, true, NEVER);
    }

    private static Foe warden(int id, double distance) {
        return new Foe(id, distance, false, false, NEVER, Kind.UNTOUCHABLE);
    }

    private static Tick tick(long now, float health, double x, Foe... foes) {
        return new Tick(now, health, x, 0, List.of(foes), null);
    }

    // two blocks back and forth every half second: moving, so never cornered, never stuck, never far
    private static double shuffle(long now) {
        return ((now - NOW) / 10) % 2 == 0 ? 0 : 2;
    }

    private static void holds(CombatCommit c, long from, long to, float health, Foe... foes) {
        for (long now = from; now <= to; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, health, shuffle(now), foes)));
        }
    }

    // hp 7, a zombie five blocks back the whole time (inside the watch, outside reach)
    private static CombatCommit lowHpRun() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, 0, zombie(1, 5))));
        assertEquals(Why.LOW_HP, c.why());
        return c;
    }

    private static CombatCommit extendedRun(Foe... foes) {
        CombatCommit c = lowHpRun();
        holds(c, NOW + 1, NOW + CAP - 1, 7, foes);
        assertEquals(Event.RUN_EXTENDED, c.step(tick(NOW + CAP, 7, shuffle(NOW + CAP), foes)));
        return c;
    }

    @Test
    public void theFirstCapStillChasedExtendsTheSameRun() {
        CombatCommit c = extendedRun(zombie(1, 5));
        assertEquals(Mode.RUN, c.mode());
        // same origin and the same start: it is one run, the distance keeps counting from where it began
        assertEquals(0, c.originX(), 0);
        assertEquals(NOW, c.startTick());
        assertFalse(c.coolingDown(NOW + CAP));
    }

    @Test
    public void theExtensionIsAWholeCapWindow() {
        CombatCommit c = extendedRun(zombie(1, 5));
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, zombie(1, 5));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void theSecondCapStillChasedTurnsAroundAndFights() {
        CombatCommit c = extendedRun(zombie(1, 5));
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, zombie(1, 5));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 2 * CAP, 7, 0, zombie(1, 5))));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Why.CHASED, c.why());
        assertEquals(1, c.targetId());
        // boxed in rules: a fight we turned around for does not bail back into a run at low hp, that is the loop again
        assertTrue(c.cornered());
        Foe target = zombie(1, 4);
        assertEquals(Event.NONE, c.step(new Tick(NOW + 2 * CAP + 1, 6, 0, 0, List.of(target, zombie(2, 6)), target)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    @Test
    public void theTurnPicksWhatIsOnUsOrHitUsOverTheClosest() {
        // 2 is closest but never touched us, 3 hit us from six blocks: 3 it is
        Foe[] foes = {zombie(2, 4), zombie(3, 6, 20)};
        CombatCommit c = extendedRun(foes);
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, foes);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 2 * CAP, 7, 0, foes)));
        assertEquals(3, c.targetId());
    }

    @Test
    public void nobodyOnUsTurnsOnTheClosestWeCanWalkUpTo() {
        // the creeper is closer, but walking up to a creeper is not a fight
        Foe[] foes = {creeper(2, 4), zombie(3, 7)};
        CombatCommit c = extendedRun(foes);
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, foes);
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 2 * CAP, 7, 0, foes)));
        assertEquals(3, c.targetId());
    }

    @Test
    public void neverTurnsOnTheWarden() {
        CombatCommit c = extendedRun(warden(2, 7));
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, warden(2, 7));
        // it stays a run, never a fight with it: the old run ends and danger starts the next one on the same step
        assertEquals(Event.RUN_START, c.step(tick(NOW + 2 * CAP, 7, 5, warden(2, 7))));
        assertEquals(Event.RUN_CAP, c.endedFirst());
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.HEAVY, c.why());
        assertEquals(5, c.originX(), 0);
    }

    // ---- no gap between one commitment and the next

    @Test
    public void aChasedFightThatWinsWithAFreshHitOnUsRunsOnTheSameStep() {
        CombatCommit c = extendedRun(zombie(1, 5));
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, zombie(1, 5));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 2 * CAP, 7, 0, zombie(1, 5))));
        // zombie 1 dies as a skeleton puts an arrow in us from six blocks: the fight is over and the run is on, no tick between
        Foe skeleton = new Foe(2, 6, true, false, 0);
        assertEquals(Event.RUN_START, c.step(tick(NOW + 2 * CAP + 1, 6, 0, skeleton)));
        assertEquals(Event.FIGHT_DEAD, c.endedFirst());
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aStalledFightWithSomethingElseSwingingAtUsGoesStraightToThatOne() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 0))));
        Foe hangingBack = zombie(1, 5, 50);
        long stall = NOW + CombatCommit.STALL_TICKS;
        for (long now = NOW + 1; now < stall; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(new Tick(now, 20, 0, 0, List.of(hangingBack, zombie(2, 5)), hangingBack)));
        }
        // the stall lands on the tick zombie 2 walks up and swings: one step, two lines, and never Mode.NONE in between
        assertEquals(Event.FIGHT_START, c.step(new Tick(stall, 20, 0, 0, List.of(hangingBack, zombie(2, 2, 0)), hangingBack)));
        assertEquals(Event.FIGHT_STALLED, c.endedFirst());
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(2, c.targetId());
        assertTrue(c.isIgnored(1));
    }

    @Test
    public void anEndWithNothingNextIsJustTheEnd() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 0))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 1, 20, 0, zombie(2, 5))));
        assertEquals(Event.NONE, c.endedFirst());
        assertEquals(Mode.NONE, c.mode());
        // and the next step forgets it
        assertEquals(Event.NONE, c.step(tick(NOW + 2, 20, 0)));
        assertEquals(Event.NONE, c.endedFirst());
    }

    @Test
    public void aRunThatStartsFromIdleHasNothingEndedFirst() {
        CombatCommit c = lowHpRun();
        assertEquals(Event.NONE, c.endedFirst());
    }

    @Test
    public void anExtendedRunStillEndsClearFromTheOriginalOrigin() {
        CombatCommit c = extendedRun(zombie(1, 5));
        // 24 out from where it started, nothing within 12 for two seconds: over, no fight
        long from = NOW + CAP + 1;
        for (long now = from; now < from + CombatCommit.RUN_CLEAR_TICKS; now++) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, 7, 24)));
        }
        assertEquals(Event.RUN_CLEAR, c.step(tick(from + CombatCommit.RUN_CLEAR_TICKS, 7, 24)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCapWithNothingWithinEightJustEnds() {
        CombatCommit c = lowHpRun();
        holds(c, NOW + 1, NOW + CAP - 1, 7, zombie(1, 9));
        assertEquals(Event.RUN_CAP, c.step(tick(NOW + CAP, 7, shuffle(NOW + CAP), zombie(1, 9))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aCapWhileHealthyJustEnds() {
        CombatCommit c = lowHpRun();
        // ate on the way, hp 15 now: the run is done, the zombie at five is walked past
        holds(c, NOW + 1, NOW + CAP - 1, 15, zombie(1, 5));
        assertEquals(Event.RUN_CAP, c.step(tick(NOW + CAP, 15, shuffle(NOW + CAP), zombie(1, 5))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aNewRunGetsItsOwnExtension() {
        // a chased fight that wins, the cooldown, then a fresh low hp run: that one can be extended again
        CombatCommit c = extendedRun(zombie(1, 5));
        holds(c, NOW + CAP + 1, NOW + 2 * CAP - 1, 7, zombie(1, 5));
        assertEquals(Event.RUN_CHASED_FIGHT, c.step(tick(NOW + 2 * CAP, 7, 0, zombie(1, 5))));
        long won = NOW + 2 * CAP + 1;
        assertEquals(Event.FIGHT_DEAD, c.step(tick(won, 7, 0)));
        long again = won + CombatCommit.COOLDOWN;
        assertEquals(Event.RUN_START, c.step(tick(again, 7, 0, zombie(4, 5))));
        holds(c, again + 1, again + CAP - 1, 7, zombie(4, 5));
        assertEquals(Event.RUN_EXTENDED, c.step(tick(again + CAP, 7, shuffle(again + CAP), zombie(4, 5))));
    }

    // ---- low hp in the cooldown

    private static CombatCommit wonFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, 0, zombie(1, 2, 0))));
        assertEquals(Event.FIGHT_DEAD, c.step(tick(NOW + 10, 20, 0)));
        assertTrue(c.coolingDown(NOW + 11));
        return c;
    }

    @Test
    public void lowHpInTheCooldownIgnoresSomethingJustStandingWithinEight() {
        CombatCommit c = wonFight();
        holds(c, NOW + 11, NOW + 10 + CombatCommit.COOLDOWN - 1, 6, zombie(2, 5));
        assertEquals(Mode.NONE, c.mode());
        // and after the cooldown it is a run again, like always
        assertEquals(Event.RUN_START, c.step(tick(NOW + 10 + CombatCommit.COOLDOWN, 6, 0, zombie(2, 5))));
    }

    @Test
    public void lowHpInTheCooldownStillRunsFromSomethingInContact() {
        CombatCommit c = wonFight();
        assertEquals(Event.RUN_START, c.step(tick(NOW + 11, 6, 0, zombie(2, CombatCommit.CONTACT))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void lowHpInTheCooldownStillRunsFromAFreshHit() {
        CombatCommit c = wonFight();
        assertEquals(Event.NONE, c.step(tick(NOW + 11, 6, 0, zombie(2, 6, CombatCommit.FRESH + 1))));
        assertEquals(Event.RUN_START, c.step(tick(NOW + 12, 6, 0, zombie(2, 6, CombatCommit.FRESH))));
        assertEquals(Why.LOW_HP, c.why());
    }
}
