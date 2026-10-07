package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;

// the planner is recomputed every tick and KitRunner follows whatever is first, so two needs that trade places (a worn
// pick, a food count wobbling at the line) made the bot drop a task it had just started and pick another up, then
// swap back. this is the hysteresis: the need we are running stays the head for a short dwell. pure, so the rules have tests
public final class HeadLatch {
    // about 5 s. long enough that a need which just started finishes its first step, short enough that a real change of
    // plan (the ingots came back, the pick is craftable) is only a moment late
    public static final long DWELL_TICKS = 100;

    private HeadLatch() {
    }

    // the need to run this tick. `running` started at `since` (game ticks), needs.get(0) is what the planner wants now.
    // the running one is kept only while all of these hold, any one of them failing hands over to the planner:
    //   - it is still owed, exactly as it was asked (gone or resized means it is done, or a different job)
    //   - the dwell is not up
    //   - the planner's head is not a pickaxe while we hold none at all: nothing to mine with is not something to wait out
    // side jobs and chains (golem, mob defense, food chain) sit above the runner and never come through here
    public static KitNeed pick(KitNeed running, long since, long now, List<KitNeed> needs, GamerFacts f) {
        KitNeed head = needs.get(0);
        if (running == null || running.equals(head) || now < since || now - since >= DWELL_TICKS) {
            return head;
        }
        if (!needs.contains(running) || toolGone(head, f)) {
            return head;
        }
        return running;
    }

    // a pick to make and no pick to make it with (or mine anything with). a merely worn one does not count as gone
    static boolean toolGone(KitNeed head, GamerFacts f) {
        return head.catalogueName().endsWith("_pickaxe") && !KitPlanner.holdsAnyPick(f);
    }
}
