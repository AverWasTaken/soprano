package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FallTraceTest {

    private static void push(FallTrace t, double y) {
        t.push(0, y, 0, 0, -0.5, 0, false, 0, (byte) 0, "lava", null, null, false, -1, 0, 0, 0);
    }

    @Test
    public void firesOncePerFall() {
        FallTrace t = new FallTrace();
        assertFalse(t.falling(true, 3));
        assertTrue(t.falling(true, 4.5f));
        for (int i = 0; i < 40; i++) {
            assertFalse(t.falling(true, 10 + i));
        }
    }

    @Test
    public void rearmsOnceFallDistanceResets() {
        FallTrace t = new FallTrace();
        assertTrue(t.falling(true, 5));
        for (int i = 0; i < 5; i++) {
            assertFalse(t.falling(true, 0));
        }
        assertTrue(t.falling(true, 5));
    }

    @Test
    public void quietOutsideTheNether() {
        FallTrace t = new FallTrace();
        assertFalse(t.falling(false, 30));
    }

    @Test
    public void keepsTheLastSixtyOldestFirst() {
        FallTrace t = new FallTrace();
        for (int i = 0; i < 100; i++) {
            push(t, i);
        }
        assertEquals(FallTrace.SIZE, t.size());
        assertTrue(t.line(0, o -> "m").contains("t-59 pos 0.00 40.00"));
        assertTrue(t.line(59, o -> "m").contains("t-0 pos 0.00 99.00"));
    }

    @Test
    public void hurtLineNamesTheAttacker() {
        FallTrace t = new FallTrace();
        t.push(1, 2, 3, 0, 0, 0, true, 0, (byte) (FallTrace.FORWARD | FallTrace.SPRINT), "netherrack", "mv", "goal", true, 77, 4, 5, 6);
        String line = t.line(0, o -> "ascend");
        assertTrue(line.startsWith("[falltrace]"));
        assertTrue(line.contains("keys WR"));
        assertTrue(line.contains("move ascend"));
        assertTrue(line.contains("HURT by #77 at 4.00 5.00 6.00"));
    }
}
