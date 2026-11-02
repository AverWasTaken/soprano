package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.time.TimerReal;

// a routine's follow node: FollowPlayerTask with an end to it. that one shadows somebody until told otherwise, so a follow
// node would never get to its next wire. same clock deal as TimedWaitTask, a fight halfway through doesn't add time
public class FollowForTask extends Task {

    private final String player;
    private final int seconds;
    private final FollowPlayerTask follow;
    private TimerReal timer;
    private boolean seen;
    private boolean done;
    private String failure;

    public FollowForTask(String player, int seconds) {
        this.player = player;
        this.seconds = seconds;
        this.follow = new FollowPlayerTask(player);
    }

    // null when it followed them for the whole time
    public String failure() {
        return failure;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (timer == null) {
            timer = new TimerReal(seconds);
            timer.reset();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        setDebugState("Following " + player + " for " + seconds + " seconds");
        // asked before the child is handed back: a stopped task that gets ticked again just starts over
        step(timer.elapsed(), follow.stopped(), mod.getEntityTracker().getPlayerMostRecentPosition(player).isPresent());
        return done || failure != null ? null : follow;
    }

    // lost = it walked to where they last were and they're gone, that's how FollowPlayerTask gives up
    void step(boolean timeUp, boolean lost, boolean seenNow) {
        seen |= seenNow;
        if (lost) {
            failure = "lost " + player;
        } else if (timeUp) {
            // the whole time spent standing around waiting for somebody who never showed up is not following them
            if (seen) {
                done = true;
            } else {
                failure = "never saw " + player;
            }
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done || failure != null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Follow " + player + " for " + seconds + " seconds";
    }
}
