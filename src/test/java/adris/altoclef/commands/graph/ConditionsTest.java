package adris.altoclef.commands.graph;

import static adris.altoclef.commands.graph.GraphTestKit.hasLogs;
import static adris.altoclef.commands.graph.GraphTestKit.night;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ConditionsTest {

    private final GraphTestKit.FakeWorld w = new GraphTestKit.FakeWorld();

    private boolean eval(Cond c) {
        return Conditions.eval(c, w);
    }

    private static Cond compare(String kind, String op, int value) {
        return Cond.leaf(kind, Map.of("op", op, "value", String.valueOf(value)));
    }

    @Test
    public void everyComparison() {
        w.hunger = 10;
        assertTrue(eval(compare("hunger", ">=", 10)));
        assertFalse(eval(compare("hunger", ">", 10)));
        assertTrue(eval(compare("hunger", "<=", 10)));
        assertFalse(eval(compare("hunger", "<", 10)));
        assertTrue(eval(compare("hunger", "=", 10)));
    }

    @Test
    public void healthIsNotAlwaysWhole() {
        w.health = 9.5;
        assertTrue(eval(compare("health", "<", 10)));
        assertFalse(eval(compare("health", "=", 10)));
    }

    @Test
    public void theLeaves() {
        w.logs = 5;
        assertTrue(eval(hasLogs(5)));
        assertFalse(eval(hasLogs(6)));
        w.day = false;
        assertTrue(eval(night()));
        w.day = true;
        assertFalse(eval(night()));
        w.dimension = "nether";
        assertTrue(eval(Cond.leaf("dimension", Map.of("value", "nether"))));
        assertFalse(eval(Cond.leaf("dimension", Map.of("value", "end"))));
        w.freeSlots = 0;
        assertTrue(eval(compare("free_slots", "=", 0)));
    }

    @Test
    public void nearPosIsASphereUnlessThereIsNoY() {
        w.x = 3;
        w.y = 100;
        w.z = 4;
        assertTrue(eval(Cond.leaf("near_pos", Map.of("x", "0", "z", "0", "r", "5"))));
        assertFalse(eval(Cond.leaf("near_pos", Map.of("x", "0", "z", "0", "r", "4"))));
        assertFalse(eval(Cond.leaf("near_pos", Map.of("x", "0", "y", "64", "z", "0", "r", "5"))));
        assertTrue(eval(Cond.leaf("near_pos", Map.of("x", "3", "y", "98", "z", "4", "r", "2"))));
    }

    @Test
    public void nearAsksTheWorldWithTheRadius() {
        w.mobNear = true;
        assertTrue(eval(Cond.leaf("near_mob", Map.of("mob", "zombie", "r", "32"))));
        assertEquals(32, w.lastRadius);
        assertFalse(eval(Cond.leaf("near_block", Map.of("block", "stone", "r", "8"))));
        assertEquals(8, w.lastRadius);
    }

    @Test
    public void theTree() {
        w.logs = 5;
        assertTrue(eval(Cond.all(hasLogs(1), hasLogs(5))));
        assertFalse(eval(Cond.all(hasLogs(1), hasLogs(6))));
        assertTrue(eval(Cond.any(hasLogs(6), hasLogs(5))));
        assertFalse(eval(Cond.any(hasLogs(6), hasLogs(7))));
        assertTrue(eval(Cond.not(hasLogs(6))));
        assertTrue(eval(Cond.all(Cond.not(hasLogs(6)), Cond.any(hasLogs(1), hasLogs(9)))));
    }

    @Test
    public void unreadableBubblesUpInsteadOfBeingAnAnswer() {
        w.unreadable = new Conditions.Unreadable("there's no day or night here");
        assertThrows(Conditions.Unreadable.class, () -> eval(night()));
        // not doesn't turn "don't know" into "yes"
        assertThrows(Conditions.Unreadable.class, () -> eval(Cond.not(night())));
        // a number that isn't one, from a hand edited file that got past nobody
        assertThrows(Conditions.Unreadable.class, () -> eval(Cond.leaf("near_mob", Map.of("mob", "zombie", "r", "lots"))));
    }

    @Test
    public void dayAndNightGoByTheClock() {
        assertTrue(Conditions.isDayTime(0));
        assertTrue(Conditions.isDayTime(6000));
        assertTrue(Conditions.isDayTime(12999));
        assertFalse(Conditions.isDayTime(13000));
        assertFalse(Conditions.isDayTime(18000));
        assertFalse(Conditions.isDayTime(22999));
        assertTrue(Conditions.isDayTime(23000));
        // the clock doesn't wrap by itself
        assertFalse(Conditions.isDayTime(24000 * 5 + 18000));
        assertTrue(Conditions.isDayTime(-18000));
    }

    @Test
    public void aStrayPaddedValueReadsTheSameAsTheValidatorSawIt() {
        w.hunger = 20;
        assertFalse(eval(Cond.leaf("hunger", Map.of("op", " <= ", "value", " 10 "))));
        w.day = false;
        assertTrue(eval(Cond.leaf("time", Map.of("value", " night "))));
    }

    @Test
    public void theSentences() {
        assertEquals("has oak_log >= 3", Conditions.text(hasLogs(3)));
        assertEquals("has oak_log >= 3 or night", Conditions.text(Cond.any(hasLogs(3), night())));
        assertEquals("not (night)", Conditions.text(Cond.not(night())));
        assertEquals("no condition yet", Conditions.text(null));
        assertEquals("at 1 ~ 2 within 5", Conditions.text(Cond.leaf("near_pos", Map.of("x", "1", "z", "2", "r", "5"))));
    }

    @Test
    public void nearBlockOnlyScansEveryTenTicks() {
        NearBlockCache cache = new NearBlockCache();
        AtomicInteger scans = new AtomicInteger();
        for (long t = 100; t < 110; t++) {
            assertTrue(cache.ask(1, "stone", 8, t, () -> {
                scans.incrementAndGet();
                return true;
            }));
        }
        assertEquals(1, scans.get());
        cache.ask(1, "stone", 8, 110, () -> {
            scans.incrementAndGet();
            return false;
        });
        assertEquals(2, scans.get());
        // another world, another radius or another block is another question
        cache.ask(2, "stone", 8, 110, () -> {
            scans.incrementAndGet();
            return false;
        });
        cache.ask(1, "stone", 9, 110, () -> {
            scans.incrementAndGet();
            return false;
        });
        cache.ask(1, "dirt", 8, 110, () -> {
            scans.incrementAndGet();
            return false;
        });
        assertEquals(5, scans.get());
        // a clock that went backwards isn't "still fresh"
        cache.ask(1, "stone", 8, 50, () -> {
            scans.incrementAndGet();
            return false;
        });
        assertEquals(6, scans.get());
    }

    private static Conditions.RunFacts facts(boolean failed, int laps) {
        return new Conditions.RunFacts() {
            @Override
            public boolean lastActionFailed() {
                return failed;
            }

            @Override
            public int laps() {
                return laps;
            }
        };
    }

    @Test
    public void lastActionFailedAsksTheRun() {
        Cond c = GraphTestKit.lastFailed();
        assertTrue(Conditions.eval(c, w, facts(true, 0)));
        assertFalse(Conditions.eval(c, w, facts(false, 0)));
        assertFalse("nothing ran yet, nothing failed", Conditions.eval(c, w));
        assertTrue(Conditions.eval(Cond.not(c), w, facts(false, 0)));
    }

    @Test
    public void theLoopCounterCompares() {
        assertTrue(Conditions.eval(GraphTestKit.laps(">=", 3), w, facts(false, 3)));
        assertFalse(Conditions.eval(GraphTestKit.laps(">", 3), w, facts(false, 3)));
        assertThrows(Conditions.Unreadable.class, () -> Conditions.eval(GraphTestKit.laps(">=", 3), w));
    }

    @Test
    public void theNewConditionsReadLikeASentence() {
        assertEquals("last action failed", Conditions.text(GraphTestKit.lastFailed()));
        assertEquals("loop counter >= 3", Conditions.text(GraphTestKit.laps(">=", 3)));
        assertEquals("inventory full", Conditions.text(Cond.leaf("free_slots", Map.of("op", "=", "value", "0"))));
        assertEquals("free slots >= 2", Conditions.text(Cond.leaf("free_slots", Map.of("op", ">=", "value", "2"))));
    }

    @Test
    public void usesLooksAllTheWayDown() {
        assertTrue(Conditions.uses(Cond.not(Cond.any(night(), GraphTestKit.laps(">=", 1))), "loop_counter"));
        assertFalse(Conditions.uses(night(), "loop_counter"));
        assertFalse(Conditions.uses(null, "loop_counter"));
    }
}
