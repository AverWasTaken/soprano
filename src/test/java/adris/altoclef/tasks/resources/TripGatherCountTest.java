package adris.altoclef.tasks.resources;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

// the "12 / 24" on the collecting toast. the task's target is held + the deficit, so what it started with doesn't count
public class TripGatherCountTest {

    @Test
    public void countsOnlyWhatCameInSinceTheStart() {
        // held 10, asked for 24 more, target 34
        assertEquals(0, TripGatherTask.collected(10, 34, 24));
        assertEquals(12, TripGatherTask.collected(22, 34, 24));
        assertEquals(24, TripGatherTask.collected(34, 34, 24));
    }

    @Test
    public void staysInsideZeroToNeed() {
        // placed a few while collecting, or a stack fell in
        assertEquals(0, TripGatherTask.collected(7, 34, 24));
        assertEquals(24, TripGatherTask.collected(64, 34, 24));
        // a pickaxe only trip has nothing to count
        assertEquals(0, TripGatherTask.collected(5, 0, 0));
    }
}
