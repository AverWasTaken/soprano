package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;
import java.util.Set;

// the planner is recomputed every tick and KitRunner follows whatever is first, so two needs that trade places (a worn
// pick, a food count wobbling at the line) made the bot drop a task it had just started and pick another up, then
// swap back. this is the hysteresis: the need we are running stays the head for a short dwell. pure, so the rules have tests
public final class HeadLatch {
    // about 5 s. long enough that a need which just started finishes its first step, short enough that a real change of
    // plan (the ingots came back, the pick is craftable) is only a moment late
    public static final long DWELL_TICKS = 100;
    // "the running need is in the plan", for missingSince
    public static final long NEVER = -1;
    // the needs that are a plain "hold this many" of a bag count, the ones keepWhileGone may hold on to
    private static final Set<String> BAG_COUNTED = Set.of("log", "planks", KitPlanner.COBBLE);

    private HeadLatch() {
    }

    // the need to run this tick. `running` started at `since` (game ticks), needs.get(0) is what the planner wants now.
    // `missingSince` is the tick the running need dropped out of the plan (NEVER while it is in). the running one is kept only
    // while all of these hold, any one of them failing hands over to the planner:
    //   - it is still owed, exactly as it was asked (resized means a different job). a log, planks or cobble need gone from the
    //     list is only believed once we hold its count, or it has stayed gone for a whole dwell: one planner input wobbling (a table that counts at 15
    //     blocks and not at 22) dropped the log need for a moment, and that moment used to hand the head straight over
    //   - the dwell is not up (a cook with its station has no dwell, see below)
    //   - the planner's head is not a pickaxe while we hold none at all: nothing to mine with is not something to wait out
    // side jobs and chains (golem, mob defense, food chain) sit above the runner and never come through here
    public static KitNeed pick(KitNeed running, long since, long missingSince, long now, List<KitNeed> needs, GamerFacts f) {
        KitNeed head = needs.get(0);
        if (running == null || running.equals(head)) {
            return head;
        }
        if (toolGone(head, f)) {
            return head;
        }
        if (sameJob(needs, running) == null) {
            return keepWhileGone(running, missingSince, now, f) ? running : head;
        }
        if (!needs.contains(running)) {
            return head;
        }
        // a cook that has its station is not on the dwell clock: 5 s is the time it takes to walk up and put the meat in, and
        // the iron took the head the moment that ran out. it is the cook's own give up that ends it
        if (KitNeed.isCookName(running.catalogueName()) && f.cookStation() != null) {
            return running;
        }
        if (now < since || now - since >= DWELL_TICKS) {
            return head;
        }
        return running;
    }

    // the plan's entry for the same thing as `running` (same name, any count), null when the plan has none
    public static KitNeed sameJob(List<KitNeed> needs, KitNeed running) {
        if (running == null) {
            return null;
        }
        for (KitNeed n : needs) {
            if (n.catalogueName().equals(running.catalogueName())) {
                return n;
            }
        }
        return null;
    }

    // a running need the plan just dropped. held: we really are done, let go now. not held: keep at it until it has been gone a
    // whole dwell, a real change of plan stays gone and a flicker comes back first. only the plain gathers get this: their count is
    // the bag and nothing else, so "not held" means not done. the rest drop out for reasons the bag can't see (iron cooking, a worn
    // pick replaced, a craft moved behind the smelt) and holding those sent the bot mining for iron already in the furnace
    static boolean keepWhileGone(KitNeed running, long missingSince, long now, GamerFacts f) {
        if (!BAG_COUNTED.contains(running.catalogueName()) || satisfied(running, f)) {
            return false;
        }
        if (missingSince == NEVER) {
            // nobody started the clock, so it went this very tick
            return true;
        }
        // a clock that went backwards does not hold for ever (same as the dwell)
        return now >= missingSince && now - missingSince < DWELL_TICKS;
    }

    // the bag holds what the need asked for: a need is "hold this many in total", the same count the planner checks
    static boolean satisfied(KitNeed need, GamerFacts f) {
        return KitPlanner.have(f, need.catalogueName()) >= need.count();
    }

    // a pick to make and no pick to make it with (or mine anything with). a merely worn one does not count as gone
    static boolean toolGone(KitNeed head, GamerFacts f) {
        return head.catalogueName().endsWith("_pickaxe") && !KitPlanner.holdsAnyPick(f);
    }
}
