package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LavaHunt.Move;
import java.util.List;
import org.junit.Test;

public class LavaHuntTest {
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
    public void arrivedIsThreeDimensional() {
        P seed = new P(0, 10, 0);
        assertTrue(LavaHunt.arrived(seed, 5, 10, 5));
        assertTrue(LavaHunt.arrived(seed, 0, 18, 0));
        assertFalse(LavaHunt.arrived(seed, 0, 70, 0));
        assertFalse(LavaHunt.arrived(seed, 6, 10, 6));
    }

    @Test
    public void noSeedsMeansExplore() {
        assertEquals(Move.EXPLORE, new LavaHunt().decide(List.of(), 0, 64, 0, 0));
    }

    @Test
    public void walksToTheCheapestThenWaitsOnArrival() {
        LavaHunt h = new LavaHunt();
        P near = new P(30, 64, 0);
        P far = new P(80, 64, 0);
        assertEquals(Move.WALK, h.decide(List.of(far, near), 0, 64, 0, 0));
        assertEquals(near, h.target());
        assertEquals(Move.WAIT, h.decide(List.of(far, near), 25, 64, 0, 100));
    }

    @Test
    public void noSiteOnlyAfterAPassThatStartedAfterArrival() {
        LavaHunt h = new LavaHunt();
        P a = new P(30, 64, 0);
        P b = new P(40, 64, 5);
        P other = new P(200, 64, 0);
        List<P> seeds = List.of(a, b, other);
        h.passStarted();
        h.decide(seeds, 0, 64, 0, 0);
        // we arrive while pass 1 is running: it doesn't count, it started before we got there
        assertEquals(Move.WAIT, h.decide(seeds, 25, 64, 0, 100));
        h.passDone(1);
        assertTrue(h.noSite().isEmpty());
        assertEquals(a, h.target());
        // pass 2 started after arrival and found nothing
        h.passStarted();
        h.passDone(2);
        assertTrue(h.noSite().contains(a));
        assertTrue(h.noSite().contains(b));
        assertFalse(h.noSite().contains(other));
        assertNull(h.target());
        // the next cheapest is the far one
        assertEquals(Move.WALK, h.decide(seeds, 25, 64, 0, 200));
        assertEquals(other, h.target());
    }

    @Test
    public void everySeedMarkedMeansExplore() {
        LavaHunt h = new LavaHunt();
        P a = new P(30, 64, 0);
        h.decide(List.of(a), 25, 64, 0, 0);
        h.passStarted();
        h.passDone(1);
        assertEquals(Move.EXPLORE, h.decide(List.of(a), 25, 64, 0, 10));
    }

    @Test
    public void aSeedWeCantWalkToIsGivenUp() {
        LavaHunt h = new LavaHunt();
        P a = new P(30, 64, 0);
        assertEquals(Move.WALK, h.decide(List.of(a), 0, 64, 0, 0));
        assertEquals(Move.WALK, h.decide(List.of(a), 0, 64, 0, LavaHunt.WALK_TICKS));
        h.decide(List.of(a), 0, 64, 0, LavaHunt.WALK_TICKS + 1);
        assertTrue(h.noSite().contains(a));
        assertEquals(Move.EXPLORE, h.decide(List.of(a), 0, 64, 0, LavaHunt.WALK_TICKS + 2));
    }

    @Test
    public void aDeepSeedGetsALongerWalkThanOneInTheBand() {
        LavaHunt h = new LavaHunt();
        P deep = new P(30, 64 - LavaHunt.BAND - 10, 0);
        assertEquals(Move.WALK, h.decide(List.of(deep), 0, 64, 0, 0));
        assertEquals(Move.WALK, h.decide(List.of(deep), 0, 64, 0, LavaHunt.WALK_TICKS + 1));
        assertFalse(h.noSite().contains(deep));
        assertEquals(Move.WALK, h.decide(List.of(deep), 0, 64, 0, LavaHunt.WALK_TICKS_DEEP));
        h.decide(List.of(deep), 0, 64, 0, LavaHunt.WALK_TICKS_DEEP + 1);
        assertTrue(h.noSite().contains(deep));
    }

    @Test
    public void deepScanNeedsTwoEmptyLegsAfterABandPass() {
        LavaHunt h = new LavaHunt();
        assertFalse(h.deepAllowed());
        // the band hasn't finished once yet: a leg doesn't count
        h.legDone(false, true);
        h.legDone(false, true);
        assertFalse(h.deepAllowed());
        // seeds were around: not an empty leg
        h.legDone(true, false);
        assertFalse(h.deepAllowed());
        h.legDone(true, true);
        assertFalse(h.deepAllowed());
        h.legDone(true, true);
        assertTrue(h.deepAllowed());
    }

    @Test
    public void aNewCellOfARejectedLakeIsNotWalkedTo() {
        LavaHunt h = new LavaHunt();
        P a = new P(30, 64, 0);
        h.decide(List.of(a), 25, 64, 0, 0);
        h.passStarted();
        h.passDone(1);
        // a later scan brings a cell of the same lake that was never in the list when it got marked
        assertEquals(Move.EXPLORE, h.decide(List.of(new P(40, 64, 10)), 25, 64, 0, 10));
        assertEquals(Move.WALK, h.decide(List.of(new P(90, 64, 0)), 25, 64, 0, 20));
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
}
