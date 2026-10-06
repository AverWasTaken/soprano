package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// the bot took a crafting table out of a village because the pickup was "nearest table". now only what we placed is a candidate
public class OwnTablesTest {
    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    @Test
    public void aVillageTableIsNeverACandidateBecauseItWasNeverRecorded() {
        List<RunState.Pos> own = new ArrayList<>();
        // the village table sits right next to us, we have placed nothing
        assertNull(OwnTables.nearest(own, p -> true, 247.5, 63, 39.5, 10));
        // and when we did place one further away, that one is the answer even though the village one is closer
        OwnTables.record(own, pos(260, 63, 40));
        assertEquals(pos(260, 63, 40), OwnTables.nearest(own, p -> true, 247.5, 63, 39.5, 20));
    }

    @Test
    public void nearestOwnTableWins() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(10, 64, 0), pos(3, 64, 0), pos(-6, 64, 0)));
        assertEquals(pos(3, 64, 0), OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 10));
    }

    @Test
    public void usableFilterAndRadiusApply() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(3, 64, 0), pos(8, 64, 0)));
        // the close one is written off (already tried), the far one is next
        assertEquals(pos(8, 64, 0), OwnTables.nearest(own, p -> p.x != 3, 0.5, 64, 0.5, 10));
        // nothing within the radius
        assertNull(OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 2));
        // everything filtered
        assertNull(OwnTables.nearest(own, p -> false, 0.5, 64, 0.5, 10));
    }

    @Test
    public void placementsOutsideReachAreNotOurs() {
        assertTrue(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(2, 64, 1)));
        assertTrue(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(7, 65, 0)));
        // a table that appears across the street (another player, a chunk update) is not something we placed
        assertFalse(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(20, 64, 0)));
        assertFalse(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(0, 64, -30)));
    }

    @Test
    public void recordDedupesAndForgetsTheOldest() {
        List<RunState.Pos> own = new ArrayList<>();
        assertTrue(OwnTables.record(own, pos(1, 1, 1)));
        assertFalse(OwnTables.record(own, pos(1, 1, 1)));
        assertEquals(1, own.size());
        for (int i = 0; i < OwnTables.CAP + 5; i++) {
            OwnTables.record(own, pos(100 + i, 64, 0));
        }
        assertEquals(OwnTables.CAP, own.size());
        assertFalse(own.contains(pos(1, 1, 1)));
        assertTrue(own.contains(pos(100 + OwnTables.CAP + 4, 64, 0)));
    }
}
