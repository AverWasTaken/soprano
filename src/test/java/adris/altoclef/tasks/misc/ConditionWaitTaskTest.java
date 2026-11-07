package adris.altoclef.tasks.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import adris.altoclef.RoutineRunner.Result;
import org.junit.Test;

public class ConditionWaitTaskTest {

    private Result answer;

    private ConditionWaitTask task() {
        return new ConditionWaitTask(() -> answer, 60, "night");
    }

    @Test
    public void itWaitsUntilTheConditionComesTrue() {
        ConditionWaitTask t = task();
        t.step(false);
        assertNull(t.result());
        answer = Result.OK;
        t.step(false);
        assertEquals(Result.OK, t.result());
    }

    @Test
    public void timingOutIsItsOwnResult() {
        ConditionWaitTask t = task();
        t.step(true);
        assertEquals(Result.TIMEOUT, t.result());
    }

    @Test
    public void theConditionGetsTheLastWordOnTheTickTimeRunsOut() {
        answer = Result.OK;
        ConditionWaitTask t = task();
        t.step(true);
        assertEquals(Result.OK, t.result());
    }

    @Test
    public void anUnreadableConditionEndsTheWait() {
        answer = Result.failed("no idea");
        ConditionWaitTask t = task();
        t.step(false);
        assertEquals(Result.Kind.FAILED, t.result().kind());
    }

    @Test
    public void onceItsOverItStaysOver() {
        ConditionWaitTask t = task();
        t.step(true);
        answer = Result.OK;
        t.step(false);
        assertEquals(Result.TIMEOUT, t.result());
    }
}
