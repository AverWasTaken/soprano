package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.GolemRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.GolemRules.Verdict;
import org.junit.Test;

// the vanilla numbers behind the golem hunt and when it is allowed to start
public class GolemRulesTest {
    private static final double MARGIN = 0.3;
    private static final double RANGE = GolemRules.INTERACT_RANGE;

    @Test
    public void threeBlocksUpIsSafeOnFlatGround() {
        // golem on y 64, we stand on y 64: three blocks of pillar puts our feet on 67, golem head is 66.7
        assertEquals(3, GolemRules.blocksToRaise(64, 64, MARGIN, 5));
        assertFalse(GolemRules.golemCanHitUs(67, 64));
        // two blocks is feet on 66 against a head on 66.7: it gets us
        assertTrue(GolemRules.golemCanHitUs(66, 64));
    }

    @Test
    public void theHeightFollowsTheGolemNotOurFloor() {
        // it is standing in a ditch one block lower, so one block less of pillar
        assertEquals(2, GolemRules.blocksToRaise(64, 63, MARGIN, 5));
        // it is up on a step, one block more
        assertEquals(4, GolemRules.blocksToRaise(64, 65, MARGIN, 5));
        // a path block makes its feet 0.0625 lower, still 3
        assertEquals(3, GolemRules.blocksToRaise(64, 63.9375, MARGIN, 5));
    }

    @Test
    public void alreadyHighEnoughNeedsNothing() {
        assertEquals(0, GolemRules.blocksToRaise(67, 64, MARGIN, 5));
        assertEquals(0, GolemRules.blocksToRaise(70, 64, MARGIN, 5));
    }

    @Test
    public void aTowerThatIsTooTallIsNotWorthIt() {
        // golem on a hill two blocks above us
        assertEquals(-1, GolemRules.blocksToRaise(64, 66, MARGIN, 4));
        assertEquals(5, GolemRules.blocksToRaise(64, 66, MARGIN, 5));
    }

    @Test
    public void floatNoiseDoesNotAddABlock() {
        assertEquals(3, GolemRules.blocksToRaise(64, 64.0000001, MARGIN, 5));
        assertEquals(3, GolemRules.blocksToRaise(64.0000001, 64.0000001, MARGIN, 5));
    }

    @Test
    public void safeFeetIsHeadPlusMargin() {
        assertEquals(64 + 2.7 + MARGIN, GolemRules.safeFeetY(64, MARGIN), 1e-9);
    }

    @Test
    public void reachIsFromTheEyeToTheNearestPointOfTheBox() {
        // feet on 67 so the eye is on 68.62 and the head of a golem on 64 is 66.7: vertical gap 1.92
        double eyeY = 67 + GolemRules.EYE_HEIGHT;
        // standing against the pillar, center 1.2 away, box edge 0.5 from our axis
        assertTrue(GolemRules.inReachOfGolem(0, eyeY, 0, 1.2, 64, 0, RANGE));
        assertTrue(GolemRules.inReachOfGolem(0, eyeY, 0, 1.2, 64, 1.2, RANGE));
        // about 2.3 past the box edge is the limit at this height
        assertTrue(GolemRules.inReachOfGolem(0, eyeY, 0, 2.9, 64, 0, RANGE));
        assertFalse(GolemRules.inReachOfGolem(0, eyeY, 0, 3.2, 64, 0, RANGE));
    }

    @Test
    public void fourBlocksUpBarelyReachesAnythingSoThreeIsTheTarget() {
        double eyeY = 68 + GolemRules.EYE_HEIGHT;
        // only the golem right against the pillar on a straight side works, a diagonal one is out
        assertTrue(GolemRules.inReachOfGolem(0, eyeY, 0, 1.2, 64, 0, RANGE));
        assertFalse(GolemRules.inReachOfGolem(0, eyeY, 0, 1.2, 64, 1.2, RANGE));
    }

    @Test
    public void aGolemUnderUsIsReachedThroughTheTopOfItsBox() {
        assertTrue(GolemRules.inReach(0, 68, 0, -0.7, 64, -0.7, 0.7, 66.7, 0.7, 3.0));
        assertFalse(GolemRules.inReach(0, 70, 0, -0.7, 64, -0.7, 0.7, 66.7, 0.7, 3.0));
    }

    // (ironNeeded, overworld, golemFound, golemAngry, alreadyTried, attemptsUsed, maxAttempts, hasWeapon, buildBlocks,
    // minBlocks, health, minHealth, hostilesNearby, onGround, inFluid)
    private static Inputs fine() {
        return new Inputs(true, true, true, false, false, 0, 2, true, 20, 5, 20f, 14f, false, true, false, 3);
    }

    @Test
    public void huntsWhenEverythingIsFine() {
        assertEquals(Verdict.GO, GolemRules.shouldHunt(fine()));
    }

    @Test
    public void everyConditionCanSayNo() {
        assertEquals(Verdict.NO_IRON_NEEDED, GolemRules.shouldHunt(new Inputs(false, true, true, false, false, 0, 2, true, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.WRONG_DIMENSION, GolemRules.shouldHunt(new Inputs(true, false, true, false, false, 0, 2, true, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.NO_GOLEM, GolemRules.shouldHunt(new Inputs(true, true, false, false, false, 0, 2, true, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.GOLEM_ANGRY, GolemRules.shouldHunt(new Inputs(true, true, true, true, false, 0, 2, true, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.TRIED, GolemRules.shouldHunt(new Inputs(true, true, true, false, true, 0, 2, true, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.OUT_OF_ATTEMPTS, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 2, 2, true, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.NO_WEAPON, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, false, 20, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.NO_BLOCKS, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 4, 5, 20f, 14f, false, true, false, 3)));
        assertEquals(Verdict.TOO_HURT, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 20, 5, 13f, 14f, false, true, false, 3)));
        assertEquals(Verdict.HOSTILES, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 20, 5, 20f, 14f, true, true, false, 3)));
        assertEquals(Verdict.NOT_STANDING, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 20, 5, 20f, 14f, false, false, false, 3)));
        assertEquals(Verdict.NOT_STANDING, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 20, 5, 20f, 14f, false, true, true, 3)));
    }

    @Test
    public void exactlyTheMinimumsAreEnough() {
        assertEquals(Verdict.GO, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 1, 2, true, 5, 5, 14f, 14f, false, true, false, 3)));
    }

    @Test
    public void launchNeedCountsOnStandingOneBlockUnderTheGolem() {
        // flat ground: three blocks, plus the block of slack we always assume
        assertEquals(4, GolemRules.launchNeed(64, 64, MARGIN, 5));
        // we are far above it (a hill over the village), we walk down to its ground, same answer
        assertEquals(4, GolemRules.launchNeed(80, 64, MARGIN, 5));
        // it is on a step above us: where we end up standing follows the golem, so still the same
        assertEquals(4, GolemRules.launchNeed(64, 65, MARGIN, 5));
    }

    @Test
    public void aGolemUpACliffOrOutOfOurTunnelIsNotStarted() {
        // the run that started this: bot in an iron tunnel, golem on the surface
        assertEquals(-1, GolemRules.launchNeed(38, 70, MARGIN, 5));
        assertEquals(-1, GolemRules.launchNeed(58, 65, MARGIN, 5));
        // 6 up is still a walk
        assertTrue(GolemRules.launchNeed(58, 64, MARGIN, 5) >= 0);
        // and a pillar over the cap says no however close we are
        assertEquals(-1, GolemRules.launchNeed(64, 64, MARGIN, 3));
    }

    @Test
    public void theBagHoldsTheNeedPlusTwoSpare() {
        assertEquals(6, GolemRules.blocksWanted(4, 5));
        // the floor wins when the need is small
        assertEquals(5, GolemRules.blocksWanted(2, 5));
    }

    @Test
    public void tooFewBlocksForTheNeedIsNoBlocksNotAFight() {
        // need 4 wants 6: five in the bag was exactly the run that walked up and bailed
        assertEquals(Verdict.NO_BLOCKS, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 5, 5, 20f, 14f, false, true, false, 4)));
        assertEquals(Verdict.GO, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 6, 5, 20f, 14f, false, true, false, 4)));
    }

    @Test
    public void groundThatCannotBePillaredIsBadGroundWhateverTheBag() {
        assertEquals(Verdict.BAD_GROUND, GolemRules.shouldHunt(new Inputs(true, true, true, false, false, 0, 2, true, 64, 5, 20f, 14f, false, true, false, -1)));
    }

    @Test
    public void onlyAbortsAboutTheDayGiveTheGolemBack() {
        for (GolemRules.Abort why : new GolemRules.Abort[]{GolemRules.Abort.NO_BLOCKS, GolemRules.Abort.PILLAR_STUCK,
                GolemRules.Abort.MONSTERS, GolemRules.Abort.ANGRY_ON_GROUND}) {
            assertTrue(why.toString(), GolemRules.refund(why, 0));
        }
        for (GolemRules.Abort why : new GolemRules.Abort[]{GolemRules.Abort.NONE, GolemRules.Abort.TOO_TALL,
                GolemRules.Abort.UNREACHABLE, GolemRules.Abort.GONE, GolemRules.Abort.GAVE_UP}) {
            assertFalse(why.toString(), GolemRules.refund(why, 0));
        }
    }

    @Test
    public void refundsRunOut() {
        assertTrue(GolemRules.refund(GolemRules.Abort.NO_BLOCKS, GolemRules.MAX_REFUNDS - 1));
        assertFalse(GolemRules.refund(GolemRules.Abort.NO_BLOCKS, GolemRules.MAX_REFUNDS));
    }

    @Test
    public void aCooldownHoldsUntilItsTick() {
        assertFalse(GolemRules.coolingDown(100, null));
        assertTrue(GolemRules.coolingDown(100, 101L));
        assertFalse(GolemRules.coolingDown(101, 101L));
    }

    @Test
    public void neverComesDownWhileItIsAngry() {
        long calm = 45 * 20;
        // alive and angry right under us: stay, however long ago we last hit it
        assertFalse(GolemRules.safeToComeDown(true, true, 2, 100000, calm, 24));
        // alive, calm, but we hit it ten seconds ago: anger lasts up to 39 s
        assertFalse(GolemRules.safeToComeDown(true, false, 2, 200, calm, 24));
        // calm and the timer ran out
        assertTrue(GolemRules.safeToComeDown(true, false, 2, calm + 1, calm, 24));
        // far away and calm: it is not coming
        assertTrue(GolemRules.safeToComeDown(true, false, 30, 10, calm, 24));
        // dead or gone: nothing to be afraid of
        assertTrue(GolemRules.safeToComeDown(false, true, 2, 0, calm, 24));
    }
}
