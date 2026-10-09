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
import adris.altoclef.util.helpers.CombatCommit.Why;
import adris.altoclef.util.helpers.FoeRules.Candidate;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

// who counts as a foe in which dimension, and what the machine does with them. no minecraft: entity types are registry paths
public class FoeRulesTest {

    private static final long NEVER = CombatCommit.NEVER;

    // an angry mob that can reach us, never hit us, not excluded
    private static Candidate mob(String type, double distance) {
        return new Candidate(type, type.hashCode(), distance, () -> false, () -> true, () -> true, NEVER, 0, false, false);
    }

    private static Candidate hitUs(String type, double distance, long sinceHit) {
        return new Candidate(type, type.hashCode(), distance, () -> false, () -> true, () -> true, sinceHit, 0, false, false);
    }

    private static Candidate excluded(String type, double distance) {
        return new Candidate(type, type.hashCode(), distance, () -> true, () -> true, () -> true, 3, 0, false, false);
    }

    private static Candidate slime(String type, int size, double distance) {
        return new Candidate(type, 77, distance, () -> false, () -> true, () -> true, NEVER, size, false, false);
    }

    private static Foe accept(Dimension dimension, Candidate c) {
        return FoeRules.accept(dimension, c);
    }

    private static Tick tick(long now, float health, Foe target, Foe... foes) {
        return new Tick(now, health, 0, 0, List.of(foes), target);
    }

    // ---- the overworld is the baseline

    @Test
    public void anAngryReachableMobIsAFoeInEveryDimension() {
        for (Dimension d : Dimension.values()) {
            Foe foe = accept(d, mob("zombie", 5));
            assertNotNull(d.name(), foe);
            assertEquals(Kind.NORMAL, foe.kind());
            assertEquals(5, foe.distance(), 0);
            assertFalse(foe.ranged());
        }
    }

    @Test
    public void somethingNotAngryIsNeverAFoe() {
        Candidate calm = new Candidate("zombie", 1, 2, () -> false, () -> false, () -> true, 3, 0, false, false);
        for (Dimension d : Dimension.values()) {
            assertNull(d.name(), accept(d, calm));
        }
    }

    @Test
    public void aMobThatCannotHarmUsIsNotAFoeUnlessItHitUs() {
        Candidate stuck = new Candidate("zombie", 1, 4, () -> false, () -> true, () -> false, NEVER, 0, false, false);
        assertNull(accept(Dimension.OVERWORLD, stuck));
        // it hit us 3 ticks ago: that can hurt us, no questions
        Candidate hit = new Candidate("zombie", 1, 4, () -> false, () -> true, () -> false, 3, 0, false, false);
        assertNotNull(accept(Dimension.OVERWORLD, hit));
        // a hit from a long time ago is a hit nobody remembers
        Candidate old = new Candidate("zombie", 1, 4, () -> false, () -> true, () -> false, CombatCommit.HIT_MEMORY + 1, 0, false, false);
        assertNull(accept(Dimension.OVERWORLD, old));
    }

    @Test
    public void aSizeOneSlimeIsABouncingPetAndABigOneIsAFoe() {
        for (Dimension d : Dimension.values()) {
            assertNull(d.name(), accept(d, slime("slime", 1, 2)));
            assertNull(d.name(), accept(d, slime("magma_cube", 1, 2)));
            assertNotNull(d.name(), accept(d, slime("slime", 2, 2)));
            assertNotNull(d.name(), accept(d, slime("magma_cube", 4, 2)));
        }
    }

    @Test
    public void theCostlyQuestionsAreOnlyAskedWhenTheCheapOnesLeaveTheMobInTheRunning() {
        AtomicInteger angry = new AtomicInteger();
        AtomicInteger harm = new AtomicInteger();
        // an excluded mob never gets as far as being asked if it is angry or can harm us
        Candidate excludedOne = new Candidate("zombie", 1, 2, () -> true, () -> {
            angry.incrementAndGet();
            return true;
        }, () -> {
            harm.incrementAndGet();
            return true;
        }, NEVER, 0, false, false);
        assertNull(accept(Dimension.NETHER, excludedOne));
        assertEquals(0, angry.get());
        assertEquals(0, harm.get());
        // one that hit us does not need a reachability verdict
        Candidate hitOne = new Candidate("zombie", 1, 2, () -> false, () -> {
            angry.incrementAndGet();
            return true;
        }, () -> {
            harm.incrementAndGet();
            return true;
        }, 4, 0, false, false);
        assertNotNull(accept(Dimension.NETHER, hitOne));
        assertEquals(1, angry.get());
        assertEquals(0, harm.get());
        // and a size 1 slime and a ghast in the nether are settled before anything is asked
        assertNull(accept(Dimension.NETHER, new Candidate("ghast", 2, 20, () -> {
            throw new AssertionError("asked about exclusion");
        }, () -> {
            throw new AssertionError("asked about anger");
        }, () -> {
            throw new AssertionError("asked about harm");
        }, 3, 0, true, false)));
    }

    // ---- the nether

    @Test
    public void aGhastIsNeverAFoeInTheNether() {
        // its fireballs are the aura's and NetherPhase's cover, there is nothing to walk up to
        assertNull(accept(Dimension.NETHER, mob("ghast", 12)));
        assertNull(accept(Dimension.NETHER, hitUs("ghast", 12, 2)));
        assertNull(accept(Dimension.NETHER, hitUs("ghast", 3, 0)));
        assertTrue(FoeRules.neverAFoe(Dimension.NETHER, "ghast"));
    }

    @Test
    public void aGhastThatHitUsNeverStartsAnythingAtAnyHp() {
        CombatCommit c = new CombatCommit();
        List<Foe> foes = new ArrayList<>();
        Foe ghast = accept(Dimension.NETHER, hitUs("ghast", 10, 2));
        if (ghast != null) foes.add(ghast);
        for (float hp : new float[]{20, 12, 8, 3}) {
            assertEquals("hp " + hp, Event.NONE, c.step(tick(100, hp, null, foes.toArray(new Foe[0]))));
        }
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aBlazeIsAFlyerOnlyInTheNether() {
        Foe nether = accept(Dimension.NETHER, hitUs("blaze", 9, 3));
        assertNotNull(nether);
        assertEquals(Kind.FLYER, nether.kind());
        // it shoots
        assertTrue(nether.ranged());
        assertEquals(Kind.NORMAL, accept(Dimension.OVERWORLD, mob("blaze", 9)).kind());
        assertEquals(Kind.NORMAL, accept(Dimension.END, mob("blaze", 9)).kind());
    }

    @Test
    public void aBlazeThatHitUsFromBeyondContactIsWalkedPast() {
        // no chase and no run: walking on is what spoils its aim
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(100, 20, null, accept(Dimension.NETHER, hitUs("blaze", 9, 3)))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aBlazeThatHitUsRightNextToUsIsAnOrdinaryFight() {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(100, 20, null, accept(Dimension.NETHER, hitUs("blaze", 2, 3)))));
        assertEquals(Why.HIT, c.why());
    }

    @Test
    public void aBlazeIsLeftToTheRodTaskWhileItRuns() {
        // CollectBlazeRodsTask adds a mob defense exclusion for blazes, the foe filter honours it: nothing to fight or run from
        assertNull(accept(Dimension.NETHER, excluded("blaze", 9)));
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(100, 4, null)));
    }

    @Test
    public void theHeavyHittersAreHeavyInEveryDimension() {
        for (String type : new String[]{"wither_skeleton", "hoglin", "zoglin", "piglin_brute", "vindicator", "ravager"}) {
            for (Dimension d : Dimension.values()) {
                Foe foe = accept(d, mob(type, 4));
                assertNotNull(type, foe);
                assertEquals(type + " in " + d, Kind.HEAVY, foe.kind());
                assertFalse(type, foe.mustRun(11));
                assertTrue(type, foe.mustRun(10));
                assertTrue(type, foe.mustRun(5));
            }
        }
    }

    @Test
    public void aWitherSkeletonIsAMeleeMobEvenThoughItIsASkeleton() {
        // the class says ranged, the sword says melee: it counts towards the melee numbers (crits, the weapon pick)
        Candidate wither = new Candidate("wither_skeleton", 5, 2.5, () -> false, () -> true, () -> true, 3, 0, true, false);
        Foe foe = accept(Dimension.NETHER, wither);
        assertNotNull(foe);
        assertFalse(foe.ranged());
        assertTrue(foe.melee());
        // an ordinary skeleton stays a shooter
        Candidate skeleton = new Candidate("skeleton", 6, 2.5, () -> false, () -> true, () -> true, NEVER, 0, true, false);
        assertTrue(accept(Dimension.NETHER, skeleton).ranged());
        // it hit us next to us: hurt enough that it is a run, and a fight above that
        assertEquals(Event.RUN_START, new CombatCommit().step(tick(100, 10, null, foe)));
        assertEquals(Event.FIGHT_START, new CombatCommit().step(tick(100, 14, null, foe)));
    }

    @Test
    public void theFightTargetLookedUpOnItsOwnShootsTheSameWayAsAFoe() {
        // CombatBrain builds the fight target outside the foe list and asks the same question
        assertFalse(FoeRules.shoots("wither_skeleton", true, Kind.HEAVY));
        assertTrue(FoeRules.shoots("skeleton", true, Kind.NORMAL));
        assertTrue(FoeRules.shoots("blaze", false, Kind.FLYER));
        assertFalse(FoeRules.shoots("zombie", false, Kind.NORMAL));
        assertFalse(FoeRules.shoots("warden", false, Kind.UNTOUCHABLE));
    }

    @Test
    public void theAuraKeepsSwingingAtABlazeBesideUsWhileTheRodTaskHasItOutOfMobDefense() {
        // the rod task excludes blazes from mob defense and only takes them off the aura beyond 3.5 blocks: one beside us is
        // the aura's, whether or not the chain's own swing list has it (it never will, an excluded mob is not a foe)
        assertTrue(FoeRules.auraMaySwingAt(false, true));
        assertTrue(FoeRules.auraMaySwingAt(true, false));
        assertTrue(FoeRules.auraMaySwingAt(true, true));
        // a hostile nobody is fighting and nobody hit us with is left alone
        assertFalse(FoeRules.auraMaySwingAt(false, false));
    }

    @Test
    public void theWardenAndTheWitherAreNeverAFight() {
        for (String type : new String[]{"warden", "wither"}) {
            Foe foe = accept(Dimension.OVERWORLD, mob(type, 4));
            assertEquals(type, Kind.UNTOUCHABLE, foe.kind());
            assertTrue(type, foe.mustRun(20));
        }
    }

    @Test
    public void everyoneElseInTheNetherIsAnOrdinaryFoe() {
        for (String type : new String[]{"zombified_piglin", "piglin", "skeleton", "magma_cube", "endermite", "silverfish", "zombie"}) {
            Foe foe = accept(Dimension.NETHER, mob(type, 4));
            assertNotNull(type, foe);
            assertEquals(type, Kind.NORMAL, foe.kind());
            assertFalse(type, foe.mustRun(1));
        }
    }

    @Test
    public void aZombifiedPiglinIsNotAFoeUntilItIsProvokedAndThenItIsTheGenericRule() {
        // the neutral rule decides whether it is angry at all (this is what EntityHelper feeds in as `angry`)
        boolean neutral = NeutralMobs.neutralNow("zombified_piglin", false, false);
        assertTrue(neutral);
        boolean calmAt10 = NeutralMobs.hostile(neutral, true, false, false, 10);
        assertFalse(calmAt10);
        Candidate calm = new Candidate("zombified_piglin", 1, 10, () -> false, () -> calmAt10, () -> true, NEVER, 0, false, false);
        assertNull(accept(Dimension.NETHER, calm));
        CombatCommit c = new CombatCommit();
        assertEquals(Event.NONE, c.step(tick(100, 20, null)));

        // one of them hit us (provoked), it is angry, it is in contact: a fight
        boolean angry = NeutralMobs.hostile(neutral, true, true, false, 2);
        assertTrue(angry);
        Foe one = accept(Dimension.NETHER, new Candidate("zombified_piglin", 1, 2, () -> false, () -> angry, () -> true, 3, 0, false, false));
        assertEquals(Event.FIGHT_START, c.step(tick(101, 20, null, one)));

        // the group came with it: three of them around us is not a reason to run, the one chewing on us is a fight
        CombatCommit group = new CombatCommit();
        Foe a = accept(Dimension.NETHER, new Candidate("zombified_piglin", 1, 2, () -> false, () -> true, () -> true, 3, 0, false, false));
        Foe b = accept(Dimension.NETHER, new Candidate("zombified_piglin", 2, 4, () -> false, () -> true, () -> true, NEVER, 0, false, false));
        Foe d = accept(Dimension.NETHER, new Candidate("zombified_piglin", 3, 5, () -> false, () -> true, () -> true, NEVER, 0, false, false));
        assertEquals(Event.FIGHT_START, group.step(tick(100, 20, null, a, b, d)));
        assertEquals(Why.HIT, group.why());
        assertEquals(1, group.targetId());
    }

    @Test
    public void aPiglinWearingGoldIsNotAFoeAndOneWithoutIsTheGenericRule() {
        assertTrue(NeutralMobs.neutralNow("piglin", false, true));
        assertFalse(NeutralMobs.neutralNow("piglin", false, false));
        // not neutral: the aggressive flag is the whole question
        assertTrue(NeutralMobs.hostile(false, true, false, false, 12));
        Foe foe = accept(Dimension.NETHER, mob("piglin", 12));
        assertNotNull(foe);
        assertEquals(Event.NONE, new CombatCommit().step(tick(100, 20, null, foe)));
    }

    // ---- the end

    @Test
    public void theDragonIsNeverAFoeInTheEnd() {
        assertNull(accept(Dimension.END, mob("ender_dragon", 5)));
        assertNull(accept(Dimension.END, hitUs("ender_dragon", 5, 0)));
        assertTrue(FoeRules.neverAFoe(Dimension.END, "ender_dragon"));
        // nothing else is walled off
        assertFalse(FoeRules.neverAFoe(Dimension.END, "enderman"));
        assertFalse(FoeRules.neverAFoe(Dimension.END, "shulker"));
        assertFalse(FoeRules.neverAFoe(Dimension.NETHER, "ender_dragon"));
    }

    @Test
    public void theChainNeverRunsFromTheDragonAtAnyHp() {
        // the dragon hits us (it is `getEntity()` of the damage) and we are nearly dead: DragonPhase's call, not the chain's
        CombatCommit c = new CombatCommit();
        Foe dragon = accept(Dimension.END, hitUs("ender_dragon", 4, 0));
        List<Foe> foes = new ArrayList<>();
        if (dragon != null) foes.add(dragon);
        for (float hp : new float[]{20, 10, 8, 2}) {
            assertEquals("hp " + hp, Event.NONE, c.step(tick(100, hp, null, foes.toArray(new Foe[0]))));
        }
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void theDragonTasksOwnTheDragonAndTheEndermenAroundTheCrystals() {
        assertTrue(FoeRules.dragonFightOwns("ender_dragon"));
        assertTrue(FoeRules.dragonFightOwns("enderman"));
        // endermites and shulkers are not theirs, they are a generic fight
        assertFalse(FoeRules.dragonFightOwns("endermite"));
        assertFalse(FoeRules.dragonFightOwns("shulker"));
        assertFalse(FoeRules.dragonFightOwns("zombie"));
    }

    @Test
    public void anEndermanAroundTheCrystalsIsNotAFoeWhileTheDragonTaskIsLive() {
        // the task's behaviour exclusion is what the foe filter is handed as `excluded`
        Candidate man = new Candidate("enderman", 5, 2, () -> FoeRules.dragonFightOwns("enderman"), () -> true, () -> true, 3, 0, false, false);
        assertNull(accept(Dimension.END, man));
        // the same enderman with the task not running (the exclusion is popped with it) is a generic foe
        Candidate loose = new Candidate("enderman", 5, 2, () -> false, () -> true, () -> true, 3, 0, false, false);
        Foe foe = accept(Dimension.END, loose);
        assertNotNull(foe);
        assertEquals(Kind.NORMAL, foe.kind());
    }

    @Test
    public void midCrystalNothingIsEverCommittedToAtLowHp() {
        // dragon, three endermen on us and hp 5: everything is owned by the task, the machine sees an empty list and stays out
        List<Foe> foes = new ArrayList<>();
        for (String type : new String[]{"ender_dragon", "enderman", "enderman", "enderman"}) {
            Candidate c = new Candidate(type, foes.size() + 1, 2, () -> FoeRules.dragonFightOwns(type), () -> true, () -> true, 0, 0, false, false);
            Foe foe = accept(Dimension.END, c);
            if (foe != null) foes.add(foe);
        }
        assertTrue(foes.isEmpty());
        assertEquals(Event.NONE, new CombatCommit().step(tick(100, 5, null, foes.toArray(new Foe[0]))));
    }

    // ---- the user tasks that fight on their own terms

    @Test
    public void theGolemOnItsPillarIsNotAFoe() {
        // GolemFightTask excludes it from mob defense while it fights from above
        assertNull(accept(Dimension.OVERWORLD, excluded("iron_golem", 3)));
        assertEquals(Event.NONE, new CombatCommit().step(tick(100, 5, null)));
    }

    @Test
    public void anExcludedMobIsNeverAFoeWhateverItIs() {
        for (Dimension d : Dimension.values()) {
            for (String type : new String[]{"blaze", "iron_golem", "enderman", "zombie", "wither_skeleton", "warden"}) {
                assertNull(type + " in " + d, accept(d, excluded(type, 2)));
            }
        }
    }

    @Test
    public void aPearlHuntedEndermanNotHittingUsIsNotACommitment() {
        // PearlHunt runs its own kill task on endermen that are already after us. the chain only acts on a hit in contact
        // or danger, so an angry enderman at five blocks is left to it in every dimension
        for (Dimension d : Dimension.values()) {
            Foe man = accept(d, mob("enderman", 5));
            assertNotNull(man);
            assertEquals(d.name(), Event.NONE, new CombatCommit().step(tick(100, 20, null, man)));
        }
    }

    @Test
    public void anEndermanThatHitsUsIsTheSameFightPearlHuntWasHavingNotARun() {
        Foe man = accept(Dimension.NETHER, hitUs("enderman", 2, 2));
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(tick(100, 20, null, man)));
        assertEquals(man.id(), c.targetId());
        // dead: the fight is over and the user task (looting the pearl) has the wheel back
        assertEquals(Event.FIGHT_DEAD, c.step(tick(130, 20, null)));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aStaringEndermanIsNeverProvokedByTheFoeFilterAlone() {
        // looked at / hit is EntityHelper's `angry` (aggressive and a line of sight): not angry means not a foe
        Candidate calm = new Candidate("enderman", 5, 2, () -> false, () -> false, () -> true, NEVER, 0, false, false);
        for (Dimension d : Dimension.values()) {
            assertNull(d.name(), accept(d, calm));
        }
    }
}
