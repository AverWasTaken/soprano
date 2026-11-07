package baritone.behavior.trip;

import baritone.api.pathing.goals.GoalBlock;
import org.junit.Test;

import static org.junit.Assert.*;

// the corner toast's state: what each step of a trip turns it into, and that answering anywhere else takes it down
public class TripToastTest {

    private static final GatherPlan STUCK_PLAN = new GatherPlan(Trip.Ask.STUCK, 20, 24, false);
    private static final GatherPlan FASTER_PLAN = new GatherPlan(Trip.Ask.FASTER, 10, 13, true);

    @Test
    public void askShowsTheQuestion() {
        TripToast toast = new TripToast();
        assertEquals(TripToast.Kind.NONE, toast.view(0).kind());
        toast.ask(7, FASTER_PLAN, 1600);
        TripToast.View v = toast.view(0);
        assertTrue(v.asking());
        assertEquals(7, v.tripId());
        assertEquals(Trip.Ask.FASTER, v.ask());
        assertEquals(13, v.blocks());
        assertTrue(v.pickaxe());
        assertEquals(1600, v.savedTicks(), 0);
    }

    @Test
    public void answeringInChatTakesTheQuestionDown() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), "overworld");
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        TripToast toast = new TripToast();
        toast.ask(trip.id, STUCK_PLAN, 0);
        toast.sync(book.current());
        assertTrue("still pending, still up", toast.view(0).asking());
        book.answer(trip.id, false);
        toast.sync(book.current());
        assertEquals(TripToast.Kind.NONE, toast.view(0).kind());
    }

    @Test
    public void aNewTripOrNoTripTakesItDown() {
        TripToast toast = new TripToast();
        toast.ask(3, STUCK_PLAN, 0);
        toast.sync(null);
        assertEquals(TripToast.Kind.NONE, toast.view(0).kind());

        TripBook book = new TripBook();
        Trip other = book.start(new GoalBlock(0, 0, 0), "overworld");
        book.ask(other.id, Trip.Ask.STUCK, STUCK_PLAN);
        toast.ask(other.id + 100, STUCK_PLAN, 0);
        toast.sync(book.current());
        assertEquals(TripToast.Kind.NONE, toast.view(0).kind());
    }

    @Test
    public void collectingKeepsItsIdWhileTheCountMoves() {
        TripToast toast = new TripToast();
        toast.ask(5, STUCK_PLAN, 0);
        int askId = toast.view(0).id();
        toast.collecting(5, false, 0, 24, TripToast.Phase.BY_HAND);
        TripToast.View first = toast.view(0);
        assertTrue(first.collecting());
        assertNotEquals("a new kind is a new toast", askId, first.id());
        toast.collecting(5, false, 12, 24, TripToast.Phase.MINING);
        TripToast.View second = toast.view(0);
        assertEquals("same toast, new numbers", first.id(), second.id());
        assertEquals(12, second.have());
        assertEquals(TripToast.Phase.MINING, second.phase());
        // more than asked for (a stack of dirt came along) still reads 24/24
        toast.collecting(5, false, 40, 24, TripToast.Phase.MINING);
        assertEquals(24, toast.view(0).have());
        toast.collecting(5, false, -3, 24, TripToast.Phase.MINING);
        assertEquals(0, toast.view(0).have());
    }

    @Test
    public void collectingGoesWhenTheGatherStops() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), "overworld");
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        book.answer(trip.id, true);
        book.startGather(trip.id, STUCK_PLAN, 2);
        TripToast toast = new TripToast();
        toast.collecting(trip.id, false, 3, 24, TripToast.Phase.BY_HAND);
        toast.sync(book.current());
        assertTrue(toast.view(0).collecting());
        book.refuse(trip.id);
        toast.sync(book.current());
        assertEquals(TripToast.Kind.NONE, toast.view(0).kind());
    }

    @Test
    public void doneLingersThenGoes() {
        TripToast toast = new TripToast();
        toast.collecting(5, false, 20, 24, TripToast.Phase.MINING);
        toast.done(5, false, 24, 1000);
        TripToast.View v = toast.view(1000);
        assertEquals(TripToast.Kind.DONE, v.kind());
        assertEquals(24, v.need());
        // the trip carrying on is not a reason to take "got them" down early
        toast.sync(null);
        assertEquals(TripToast.Kind.DONE, toast.view(1000 + TripToast.DONE_HOLD_MS - 1).kind());
        assertEquals(TripToast.Kind.NONE, toast.view(1000 + TripToast.DONE_HOLD_MS).kind());
    }

    @Test
    public void clearIsClear() {
        TripToast toast = new TripToast();
        toast.done(1, true, 0, 0);
        toast.clear();
        assertEquals(TripToast.Kind.NONE, toast.view(0).kind());
    }
}
