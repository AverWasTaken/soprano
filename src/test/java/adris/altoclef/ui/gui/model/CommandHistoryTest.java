package adris.altoclef.ui.gui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CommandHistoryTest {

    @Test
    public void upWalksBackAndStopsAtTheOldest() {
        CommandHistory h = new CommandHistory();
        h.add("one");
        h.add("two");
        h.add("three");
        assertEquals("three", h.back(""));
        assertEquals("two", h.back("three"));
        assertEquals("one", h.back("two"));
        assertEquals("one", h.back("one"));
    }

    @Test
    public void downComesBackToTheDraft() {
        CommandHistory h = new CommandHistory();
        h.add("one");
        h.add("two");
        assertEquals("two", h.back("half typed"));
        assertEquals("one", h.back("two"));
        assertEquals("two", h.forward("one"));
        assertEquals("half typed", h.forward("two"));
        assertFalse(h.browsing());
        assertEquals("half typed", h.forward("half typed"));
    }

    @Test
    public void emptyHistoryChangesNothing() {
        CommandHistory h = new CommandHistory();
        assertEquals("abc", h.back("abc"));
        assertEquals("abc", h.forward("abc"));
    }

    @Test
    public void keepsTheLastTwenty() {
        CommandHistory h = new CommandHistory();
        for (int i = 0; i < 25; i++) {
            h.add("c" + i);
        }
        assertEquals(CommandHistory.MAX, h.size());
        assertEquals("c5", h.lines().get(0));
        assertEquals("c24", h.lines().get(19));
    }

    @Test
    public void repeatsAndBlanksAreNotStored() {
        CommandHistory h = new CommandHistory();
        h.add("stop");
        h.add("stop");
        h.add("  ");
        h.add(null);
        assertEquals(1, h.size());
        h.add("go");
        h.add("stop");
        assertEquals(3, h.size());
    }

    @Test
    public void addingResetsTheCursor() {
        CommandHistory h = new CommandHistory();
        h.add("a");
        h.add("b");
        h.back("");
        assertTrue(h.browsing());
        h.add("c");
        assertFalse(h.browsing());
        assertEquals("c", h.back(""));
    }
}
