package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LavaHunt.Move;
import adris.altoclef.tasks.speedrun.gamer.portal.LakeSurvey.Lake;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class LavaHuntTest {
    private static final int BAG = 64;
    private static final int FAR = LavaHunt.PICK_REACH + 30;

    // a layout whose middle row cell is (x, 64, 0)
    private static Layout siteAt(int x) {
        return new Layout(x - 1, 64, 0, 1, 0, 0, 1);
    }

    private static Lake good(P anchor, int x, int cost, int fills) {
        return new Lake(anchor, Set.of(anchor), siteAt(x), cost, fills, false);
    }

    private static Lake bad(P anchor) {
        return new Lake(anchor, Set.of(anchor), null, 0, 0, false);
    }

    @Test
    public void seedsComeCheapestFirstAndHeightCostsMore() {
        P flatFar = new P(40, 64, 0);
        P deepNear = new P(5, 40, 0);
        P flatNear = new P(10, 64, 0);
        List<P> out = LavaHunt.byCost(List.of(deepNear, flatFar, flatNear, flatNear), 0, 64, 0);
        assertEquals(List.of(flatNear, flatFar, deepNear), out);
    }

    @Test
    public void theBandStaysAtTheSurfaceWhenWeStartInACave() {
        assertEquals(LavaHunt.SURFACE_Y, LavaHunt.bandCentre(2));
        assertEquals(LavaHunt.SURFACE_Y, LavaHunt.bandCentre(-30));
        // up on a hill it follows us
        assertEquals(90, LavaHunt.bandCentre(90));
    }

    @Test
    public void chunkCapLetsThreeThroughPerChunk() {
        LavaHunt.ChunkCap cap = new LavaHunt.ChunkCap();
        assertTrue(cap.take(0, 0));
        assertTrue(cap.take(15, 15));
        assertTrue(cap.take(3, 9));
        assertFalse(cap.take(1, 1));
        // next chunk over, and negative coordinates are their own chunks
        assertTrue(cap.take(16, 0));
        assertTrue(cap.take(-1, 0));
        assertTrue(cap.take(-16, -16));
        assertTrue(cap.take(-1, -1));
        assertTrue(cap.take(-2, -2));
        assertFalse(cap.take(-3, -3));
    }

    @Test
    public void chunkCapReportsFull() {
        LavaHunt.ChunkCap cap = new LavaHunt.ChunkCap();
        assertFalse(cap.full(0, 0));
        cap.take(0, 0);
        cap.take(1, 1);
        assertFalse(cap.full(2, 2));
        cap.take(2, 2);
        assertTrue(cap.full(3, 3));
        assertFalse(cap.full(16, 0));
    }

    @Test
    public void nothingIsDecidedBeforeAScanFinished() {
        LavaHunt h = new LavaHunt();
        // an empty list from a scan that hasn't run is not "no lava"
        assertEquals(Move.WAIT, h.decide(List.of(), false, false, 0, 64, 0, 0, BAG));
        assertEquals(Move.GIVE_UP, h.decide(List.of(), true, false, 0, 64, 0, 0, BAG));
    }

    @Test
    public void noGiveUpWhileALakeStillHasNoVerdict() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        assertEquals(a, h.nextToSurvey(List.of(a), 0, 64, 0, 0));
        assertEquals(Move.WAIT, h.decide(List.of(a), true, false, 0, 64, 0, 0, BAG));
        h.surveyed(bad(a), 1);
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 0, 64, 0, 2, BAG));
    }

    @Test
    public void aLakeInTheMiddleOfItsSurveyIsAReasonToWaitEvenIfTheSeedSlidOutOfTheList() {
        LavaHunt h = new LavaHunt();
        assertEquals(Move.WAIT, h.decide(List.of(), true, true, 0, 64, 0, 0, BAG));
        assertEquals(Move.GIVE_UP, h.decide(List.of(), true, false, 0, 64, 0, 1, BAG));
    }

    @Test
    public void theFirstWalkWaitsForEveryLakeToHaveAVerdict() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        P b = new P(150, 64, 0);
        List<P> seeds = List.of(a, b);
        h.surveyed(good(a, 100, 0, 0), 0);
        // b still has none: no walk yet, even though a passed
        assertEquals(b, h.nextToSurvey(seeds, 0, 64, 0, 1));
        assertEquals(Move.WAIT, h.decide(seeds, true, false, 0, 64, 0, 1, BAG));
        h.surveyed(bad(b), 2);
        assertNull(h.nextToSurvey(seeds, 0, 64, 0, 3));
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 3, BAG));
        assertEquals(new P(100, 64, 0), h.target());
    }

    @Test
    public void aWalkAlreadyGoingDoesntStopForANewLake() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        h.surveyed(good(a, 100, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        P fresh = new P(300, 64, 0);
        assertEquals(Move.WALK, h.decide(List.of(a, fresh), true, false, 5, 64, 0, 2, BAG));
    }

    @Test
    public void aSeedOfAnAlreadySurveyedLakeIsNotSurveyedAgain() {
        LavaHunt h = new LavaHunt();
        P a = new P(100, 64, 0);
        P cellOfA = new P(101, 64, 0);
        h.surveyed(new Lake(a, Set.of(a, cellOfA), null, 0, 0, false), 0);
        assertNull(h.nextToSurvey(List.of(cellOfA), 0, 64, 0, 1));
    }

    @Test
    public void aLakeWeCouldntReadAllOfIsNotBadAndComesBack() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        h.surveyed(new Lake(a, Set.of(a), null, 0, 0, true), 0);
        // not a walk target. nothing walks toward a lake it can't read, the cast is the answer then
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 0, 64, 0, 1, BAG));
        assertTrue(rejected.isEmpty());
        assertNull(h.nextToSurvey(List.of(a), 0, 64, 0, LavaHunt.UNKNOWN_TICKS));
        assertEquals(a, h.nextToSurvey(List.of(a), 0, 64, 0, LavaHunt.UNKNOWN_TICKS + 1));
    }

    @Test
    public void aLakeTheBagCantAffordIsSkippedButNeverRejected() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        h.surveyed(good(a, 100, 3, 2), 0);
        assertEquals(Move.GIVE_UP, h.decide(List.of(a), true, false, 0, 64, 0, 1, 9));
        assertTrue(rejected.isEmpty());
        // blocks later and it is the target
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 0, 64, 0, 2, 10));
        assertEquals(new P(100, 64, 0), h.target());
    }

    @Test
    public void theCheaperWalkWins() {
        LavaHunt h = new LavaHunt();
        P near = new P(60, 64, 0);
        P far = new P(150, 64, 0);
        h.surveyed(good(far, 150, 0, 0), 0);
        h.surveyed(good(near, 60, 2, 0), 0);
        h.decide(List.of(near, far), true, false, 0, 64, 0, 1, BAG);
        assertEquals(new P(60, 64, 0), h.target());
    }

    @Test
    public void pickTakesALakeInReachAndOnlyAPerfectOneWhileOthersAreStillBeingLookedAt() {
        LavaHunt h = new LavaHunt();
        P rough = new P(20, 64, 0);
        P perfect = new P(30, 64, 0);
        P far = new P(FAR, 64, 0);
        h.surveyed(good(rough, 20, 4, 0), 0);
        h.surveyed(good(far, FAR, 0, 0), 0);
        assertNull(h.pick(0, 64, 0, BAG, true));
        assertEquals(siteAt(20), h.pick(0, 64, 0, BAG, false));
        h.surveyed(good(perfect, 30, 0, 0), 0);
        assertEquals(siteAt(30), h.pick(0, 64, 0, BAG, true));
        // a lake far above or below us is not in reach
        assertNull(h.pick(0, 64 + LavaHunt.BAND + 1, 0, BAG, false));
    }

    @Test
    public void theLakeWeWalkedToIsPickedEvenWhileOthersAreStillBeingLookedAt() {
        LavaHunt h = new LavaHunt();
        P rough = new P(100, 64, 0);
        h.surveyed(good(rough, 100, 6, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(rough), true, false, 0, 64, 0, 0, BAG));
        assertEquals(siteAt(100), h.pick(95, 64, 0, BAG, true));
    }

    @Test
    public void aWalkThatGetsNowhereIsGivenUpAndTheNextLakeIsTaken() {
        List<P> rejected = new ArrayList<>();
        LavaHunt h = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        P b = new P(200, 64, 0);
        List<P> seeds = List.of(a, b);
        h.surveyed(good(a, 100, 0, 0), 0);
        h.surveyed(good(b, 200, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(seeds, true, false, 0, 64, 0, 0, BAG));
        assertEquals(new P(100, 64, 0), h.target());
        // 2 blocks of shuffling is jitter, 3 is the bar
        assertEquals(Move.WALK, h.decide(seeds, true, false, 2, 64, 0, LavaHunt.PROGRESS_TICKS, BAG));
        assertEquals(Move.WAIT, h.decide(seeds, true, false, 1, 64, 0, LavaHunt.PROGRESS_TICKS + 1, BAG));
        assertEquals(1, rejected.size());
        assertNull(h.target());
        assertEquals(Move.WALK, h.decide(seeds, true, false, 1, 64, 0, LavaHunt.PROGRESS_TICKS + 2, BAG));
        assertEquals(new P(200, 64, 0), h.target());
    }

    @Test
    public void oscillatingNeverBeatsTheBest() {
        LavaHunt h = new LavaHunt();
        P a = new P(150, 64, 0);
        h.surveyed(good(a, 150, 0, 0), 0);
        assertEquals(Move.WALK, h.decide(List.of(a), true, false, 10, 64, 0, 0, BAG));
        Move m = Move.WALK;
        int t = 1;
        // forth and back over the same few blocks, forever: every other step is a decrease but never a new best
        while (m == Move.WALK && t < LavaHunt.PROGRESS_TICKS * 3) {
            m = h.decide(List.of(a), true, false, t % 2 == 0 ? 10 : 12, 64, 0, t, BAG);
            t += 5;
        }
        assertEquals(Move.WAIT, m);
        assertTrue(t <= LavaHunt.PROGRESS_TICKS + 10);
    }

    @Test
    public void everyNewBestOfThreeBlocksRestartsTheWalkClock() {
        LavaHunt h = new LavaHunt();
        P a = new P(150, 64, 0);
        h.surveyed(good(a, 150, 0, 0), 0);
        int t = 0;
        int x = 0;
        // 4 blocks every 14 s: slow, but it is getting there
        for (int i = 0; i < 4; i++) {
            assertEquals(Move.WALK, h.decide(List.of(a), true, false, x, 64, 0, t, BAG));
            t += LavaHunt.PROGRESS_TICKS - 20;
            x += 4;
        }
        assertEquals(new P(150, 64, 0), h.target());
    }

    @Test
    public void theWholeWalkHasAHardCap() {
        LavaHunt h = new LavaHunt();
        P a = new P(150, 64, 0);
        h.surveyed(good(a, 150, 0, 0), 0);
        int x = 0;
        int t = 0;
        Move m = Move.WALK;
        // always a bit closer, forever
        while (t <= LavaHunt.WALK_TICKS + 200 && m == Move.WALK) {
            m = h.decide(List.of(a), true, false, x, 64, 0, t, BAG);
            x += 4;
            t += 100;
        }
        assertEquals(Move.WAIT, m);
        assertNull(h.target());
    }

    @Test
    public void aRejectedLakeStaysRejectedForTheNextTry() {
        List<P> rejected = new ArrayList<>();
        LavaHunt first = new LavaHunt(rejected);
        P a = new P(100, 64, 0);
        first.surveyed(good(a, 100, 0, 0), 0);
        first.siteFailed(new P(101, 64, 0));
        // the lake is gone from the verdicts and its cells are not looked at again
        assertNull(first.nextToSurvey(List.of(a, new P(110, 64, 10)), 0, 64, 0, 1));
        // a retry builds a new hunt on the same list: nothing there is surveyed, let alone walked to
        LavaHunt retry = new LavaHunt(rejected);
        assertNull(retry.nextToSurvey(List.of(a), 0, 64, 0, 0));
        assertEquals(Move.GIVE_UP, retry.decide(List.of(a), true, false, 0, 64, 0, 0, BAG));
        // another lake is still fair game
        assertEquals(new P(300, 64, 0), retry.nextToSurvey(List.of(a, new P(300, 64, 0)), 0, 64, 0, 1));
    }

    @Test
    public void aStaleVerdictDroppedAtPickTimeIsSurveyedAgain() {
        LavaHunt h = new LavaHunt();
        P a = new P(20, 64, 0);
        h.surveyed(good(a, 20, 0, 0), 0);
        assertNull(h.nextToSurvey(List.of(a), 0, 64, 0, 0));
        h.drop(siteAt(20));
        assertEquals(a, h.nextToSurvey(List.of(a), 0, 64, 0, 1));
    }

    @Test
    public void theCastLakeIsTheNearestOneBigEnoughForTheFlood() {
        P tiny = new P(5, 64, 0);
        P near = new P(30, 64, 0);
        P far = new P(90, 64, 0);
        // the tiny puddle is the closest seed but the cast wants a flood, so it is walked past
        assertEquals(near, LavaHunt.castLake(List.of(far, tiny, near), 0, 64, 0, 12, seed -> seed.equals(tiny) ? 3 : 20));
        assertNull(LavaHunt.castLake(List.of(tiny), 0, 64, 0, 12, seed -> 3));
        assertNull(LavaHunt.castLake(List.of(), 0, 64, 0, 12, seed -> 99));
    }

    @Test
    public void theFetchBandIsTheSurfaceAndNotTheCaves() {
        assertTrue(LavaHunt.inBand(64, 70));
        assertTrue(LavaHunt.inBand(64 + LavaHunt.BAND, 64));
        assertFalse(LavaHunt.inBand(64 + LavaHunt.BAND + 1, 64));
        // standing in a cave does not move the band down with us
        assertFalse(LavaHunt.inBand(2, 10));
        assertTrue(LavaHunt.inBand(60 - LavaHunt.BAND, 10));
    }
}
