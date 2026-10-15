package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class ReplantBookTest {
    // cells are just their distance from us
    private static double dist(Integer cell) {
        return cell * cell;
    }

    @Test
    public void picksTheNearestHoleInRange() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(5);
        book.add(2);
        book.add(30);
        assertEquals(Integer.valueOf(2), book.pick(0, ReplantBookTest::dist, 100));
    }

    @Test
    public void nothingOutOfRangeIsPicked() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(30);
        assertNull(book.pick(0, ReplantBookTest::dist, 100));
        assertFalse(book.committed());
        assertTrue(book.any());
    }

    @Test
    public void staysOnItsHoleWhenAnotherOneGetsCloser() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(8);
        assertEquals(Integer.valueOf(8), book.pick(0, ReplantBookTest::dist, 100));
        book.add(1);
        assertEquals(Integer.valueOf(8), book.pick(1, ReplantBookTest::dist, 100));
    }

    @Test
    public void aCommittedHoleStaysEvenWhenItWalksOutOfRange() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(8);
        book.pick(0, ReplantBookTest::dist, 100);
        book.refresh(1, c -> true);
        assertTrue(book.committed());
        assertEquals(0, book.near(c -> 400, 100));
        assertEquals(Integer.valueOf(8), book.pick(2, c -> 400, 100));
    }

    @Test
    public void aPlantedOrTrampledHoleIsForgotten() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(1);
        book.add(2);
        book.pick(0, ReplantBookTest::dist, 100);
        // 1 got planted, 2 got trampled into dirt: neither is a hole anymore
        Set<Integer> stillHoles = new HashSet<>();
        book.refresh(1, stillHoles::contains);
        assertFalse(book.any());
        assertFalse(book.committed());
    }

    @Test
    public void aHoleThatEatsItsWholeBudgetIsGivenUpOnForGood() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(1);
        book.add(4);
        assertEquals(Integer.valueOf(1), book.pick(0, ReplantBookTest::dist, 100));
        book.refresh(100, c -> true);
        assertEquals(Integer.valueOf(1), book.pick(100, ReplantBookTest::dist, 100));
        book.refresh(101, c -> true);
        assertFalse(book.committed());
        // the next one gets its turn, and the dud can't sneak back in
        assertEquals(Integer.valueOf(4), book.pick(101, ReplantBookTest::dist, 100));
        book.add(1);
        book.refresh(102, c -> true);
        assertEquals(1, book.near(ReplantBookTest::dist, 100));
    }

    @Test
    public void everyHoleBeingADudEndsTheLoop() {
        ReplantBook<Integer> book = new ReplantBook<>(10);
        book.add(1);
        book.add(2);
        int now = 0;
        // the whole book, one dud at a time, then it is empty and replanting is over
        for (int i = 0; i < 2; i++) {
            assertTrue(book.pick(now, ReplantBookTest::dist, 100) != null);
            now += 11;
            book.refresh(now, c -> true);
        }
        assertFalse(book.any());
        assertNull(book.pick(now, ReplantBookTest::dist, 100));
    }

    @Test
    public void releaseDropsTheWalkButKeepsTheHoles() {
        ReplantBook<Integer> book = new ReplantBook<>(100);
        book.add(3);
        book.pick(0, ReplantBookTest::dist, 100);
        book.release();
        assertFalse(book.committed());
        assertTrue(book.any());
    }
}
