package adris.altoclef.util.helpers;

import adris.altoclef.tasksystem.TaskClock;

// "right next to the source, not walking, and the bucket still can't get at it". a source with a 15/16 block on top
// (farmland, a dirt path) reads as open, but the scoop ray hits the cover and the feet math of baritone and minecraft
// disagree by one, so the goal is met for one of them and never for the other. no wander, no timeout, no ban, forever.
// this clock doesn't care why, it just bills time spent standing there. same shape as the lid clock (lidTimedOut)
public final class ScoopStall {
    public static final double GIVE_UP_SECONDS = 12;
    // three blocks from the source's middle. standing on top of it is about 1.5
    public static final double NEAR_SQ = 3 * 3;

    private final TaskClock clock = new TaskClock();
    private long source;
    private boolean hasSource = false;

    // one tick on the source with this key (BlockPos.asLong). true means give up on it: ban it and pick another.
    // the clock is then cleared, so the next source starts from zero
    public boolean tick(long sourceKey, double distSq, boolean pathing, boolean scooping) {
        if (!hasSource || sourceKey != source) {
            source = sourceKey;
            hasSource = true;
            clock.reset();
        }
        // a scoop that fires is the thing working. whether it lands is InteractWithBlockTask's problem
        if (scooping) {
            clock.reset();
            return false;
        }
        // a live path is the walk there, that's progress and the pathing has clocks of its own
        if (!pathing && distSq <= NEAR_SQ) {
            clock.tick();
        }
        if (!clock.over(GIVE_UP_SECONDS)) {
            return false;
        }
        clock.reset();
        return true;
    }

    public double spentSeconds() {
        return clock.spentSeconds();
    }
}
