package adris.altoclef.chains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// a finish, a cancel and a replace used to all look the same to whoever waited on a user task (or a replace looked like
// nothing at all). the ";" lines still have to see exactly what they always saw
public class UserTaskOutcomeTest {

    @Test
    public void anOwedIdleCommandOnlyRunsWhenNothingElseGotIn() {
        assertEquals(true, UserTaskChain.idleStillWanted(false, true));
        assertEquals(false, UserTaskChain.idleStillWanted(true, true));
        assertEquals(false, UserTaskChain.idleStillWanted(false, false));
    }

    private static class Fake extends Task {
        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected Task onTick(AltoClef mod) {
            return null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "fake";
        }
    }

    @Test
    public void aTaskThatGotThereOnItsOwnFinished() {
        assertEquals(UserTaskChain.Outcome.FINISHED, UserTaskChain.outcomeOf(false, new Fake()));
    }

    @Test
    public void aCancelIsNeverAFinish() {
        assertEquals(UserTaskChain.Outcome.CANCELLED, UserTaskChain.outcomeOf(true, new Fake()));
        // cancel stops the chain first, which already dropped the task
        assertEquals(UserTaskChain.Outcome.CANCELLED, UserTaskChain.outcomeOf(false, null));
    }

    @Test
    public void aStoppedRootIsNotAFinish() {
        SingleTaskChain chain = new SingleTaskChain(new TaskRunner(null)) {
            @Override
            protected void onTaskFinish(AltoClef mod) {
            }

            @Override
            public float getPriority(AltoClef mod) {
                return 0;
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        Fake t = new Fake();
        t.tick(null, chain);
        t.stop(null);
        assertEquals(UserTaskChain.Outcome.CANCELLED, UserTaskChain.outcomeOf(false, t));
    }

    @Test
    public void theOldCallbacksRunOnFinishAndCancelButNotOnReplace() {
        List<String> ran = new ArrayList<>();
        UserTaskChain.Listener l = UserTaskChain.legacy(() -> ran.add("x"));
        l.ended(UserTaskChain.Outcome.FINISHED);
        l.ended(UserTaskChain.Outcome.CANCELLED);
        // a replaced "a;b" must not go on to b over whatever replaced it, same as before there were outcomes
        l.ended(UserTaskChain.Outcome.REPLACED);
        assertEquals(List.of("x", "x"), ran);
        assertNull(UserTaskChain.legacy(null));
    }

    @Test
    public void aWaitingCallbackThatGetsPushedOutIsTheOneToTell() {
        UserTaskChain.Listener a = o -> {
        };
        UserTaskChain.Listener b = o -> {
        };
        assertSame(a, UserTaskChain.pushedOut(a, b));
        // nothing waiting (it ran already and was let go of), nothing to tell
        assertNull(UserTaskChain.pushedOut(null, b));
        assertNull(UserTaskChain.pushedOut(a, a));
    }

    @Test
    public void theIdleFlagOnlyLastsForTheIdleCommand() {
        UserTaskChain chain = new UserTaskChain(new TaskRunner(null));
        assertEquals(false, chain.nextTaskIsIdle());
        boolean[] during = {false};
        chain.runMarkedIdle(() -> during[0] = chain.nextTaskIsIdle());
        assertEquals("the task the command starts is the idle one", true, during[0]);
        assertEquals("a command that started nothing leaves nothing for the next task", false, chain.nextTaskIsIdle());
    }
}
