package adris.altoclef.trace;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DebugCollapseTest {

    private final DebugCollapse c = new DebugCollapse();
    private final List<String> lines = new ArrayList<>();

    private void offer(String text, long ms, long tick) {
        c.offer(text, ms, tick, lines::add);
    }

    @Test
    public void aFirstLinePrintsAtOnce() {
        offer("Pausing", 0, 0);
        assertEquals(List.of("Pausing"), lines);
    }

    @Test
    public void repeatsAreCountedNotPrinted() {
        offer("Pausing", 0, 0);
        for (int t = 1; t < 40; t++) {
            offer("Pausing", t * 50L, t);
        }
        assertEquals(List.of("Pausing"), lines);
    }

    @Test
    public void aChangeSaysHowManyThenPrintsTheNewOne() {
        offer("Pausing", 0, 0);
        for (int t = 1; t <= 36; t++) {
            offer("Pausing", t * 50L, t);
        }
        offer("Moving", 1900, 38);
        assertEquals(List.of("Pausing", "Pausing x37 over 36t", "Moving"), lines);
    }

    @Test
    public void aLoneLineGetsNoSummary() {
        offer("a", 0, 0);
        offer("b", 50, 1);
        offer("a", 100, 2);
        assertEquals(List.of("a", "b", "a"), lines);
    }

    @Test
    public void aLongRunSaysItselfEveryFiveSeconds() {
        offer("Pausing", 0, 0);
        for (int t = 1; t <= 100; t++) {
            offer("Pausing", t * 50L, t);
        }
        // the 5 s mark is tick 100
        assertEquals(List.of("Pausing", "Pausing x101 over 100t"), lines);
        for (int t = 101; t <= 200; t++) {
            offer("Pausing", t * 50L, t);
        }
        // the follow-up counts only what the first summary did not
        assertEquals(List.of("Pausing", "Pausing x101 over 100t", "Pausing x100 over 100t"), lines);
    }

    @Test
    public void aQuietRunIsSummedUpByTheFlush() {
        offer("Pausing", 0, 0);
        offer("Pausing", 50, 1);
        offer("Pausing", 100, 2);
        c.flush(600, lines::add);
        assertEquals(List.of("Pausing"), lines);
        c.flush(1100, lines::add);
        assertEquals(List.of("Pausing", "Pausing x3 over 2t"), lines);
        // said once
        c.flush(5000, lines::add);
        assertEquals(2, lines.size());
    }

    @Test
    public void theSameTextAfterAQuietRunPrintsAgain() {
        offer("Pausing", 0, 0);
        offer("Pausing", 50, 1);
        c.flush(2000, lines::add);
        offer("Pausing", 3000, 60);
        assertEquals(List.of("Pausing", "Pausing x2 over 1t", "Pausing"), lines);
    }

    @Test
    public void aFlushWithNothingToSayIsSilent() {
        c.flush(10_000, lines::add);
        offer("one", 0, 0);
        c.flush(10_000, lines::add);
        assertEquals(List.of("one"), lines);
    }

    @Test
    public void latestRemembersRepeatsToo() {
        assertNull(c.latest());
        offer("Pausing", 0, 3);
        offer("Pausing", 50, 4);
        assertEquals("Pausing", c.latest());
        assertEquals(4, c.latestTick());
    }
}
