package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.WalkCost;

import java.util.List;
import java.util.function.Predicate;

// the crafting tables (and furnaces, same rules) this run put down itself. the table pickup in PrepSupport used to take the nearest crafting table
// in the world, which in a village is somebody's table and on a bad day is the one the village is built around. pure
// (positions are the RunState.Pos the state file already uses) so the rules are testable without a game
public final class OwnTables {
    // a block that shows up further away than we can place from did not come from us (another player, a chunk update)
    public static final double PLACE_REACH = 8.0;
    // more than this and the oldest is forgotten, a run places a handful
    public static final int CAP = 16;

    private OwnTables() {
    }

    // blocks in the world (not ours) can pop into existence near us too, so "near" is the only evidence we have of who
    // placed it. measured from the player to the middle of the block
    public static boolean placedByUs(double px, double py, double pz, RunState.Pos block) {
        double dx = block.x + 0.5 - px;
        double dy = block.y + 0.5 - py;
        double dz = block.z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz <= PLACE_REACH * PLACE_REACH;
    }

    // true when it was new. a table that was placed, picked up and placed on the same spot is one entry
    public static boolean record(List<RunState.Pos> tables, RunState.Pos pos) {
        if (tables.contains(pos)) {
            return false;
        }
        tables.add(pos);
        while (tables.size() > CAP) {
            tables.remove(0);
        }
        return true;
    }

    // "never happened" for the tick stamps below (game time starts at 0, so -1 is safely before everything)
    public static final long NEVER = -1;

    // may a station pickup START right now. the pickup preempts the kit task, and it used to run on a 30 second "not used
    // lately" cooldown, which is how the bot walked off to mine and left the table behind (out of range when it expired).
    // the need boundary rules below are the real decision now. at a boundary the last use is not a reason to wait (the
    // craft is over, that is what a boundary is), only a placement within PLACE_GUARD_SECONDS is. the use debounce used to
    // run off the last time the menu was open and the bot sprinted after a pig for the whole of it, 130 blocks of
    // table left behind. away from a boundary the debounce still applies. after one successful pickup of a kind we leave
    // that kind alone for a few seconds, a floor and nothing more: the real loop breaker for tables is usedSincePlaced (no
    // craft since it went down, no pickup), the 120 s this used to be left tables behind all over the map. the floor is
    // for the loop we did not think of. stamps are game ticks, NEVER = none yet
    public static Start startRecovery(long now, long lastUse, long lastPlace, long lastRecovered, boolean menuOpen, boolean atBoundary,
                                      double useCooldownSeconds, double placeGuardSeconds, double recoverCooldownSeconds) {
        // an open menu is NO and not HOLD: standing still in front of it would never close it. the recover floor is a few
        // seconds of "not now", not something to stand still for. only the short guards below may hold us
        if (menuOpen) {
            return Start.NO;
        }
        if (lastRecovered != NEVER && now - lastRecovered < recoverCooldownSeconds * 20) {
            return Start.NO;
        }
        if (lastPlace != NEVER && now - lastPlace < placeGuardSeconds * 20) {
            return Start.HOLD;
        }
        if (!atBoundary && lastUse != NEVER && now - lastUse < useCooldownSeconds * 20) {
            return Start.HOLD;
        }
        return Start.GO;
    }

    // a placement gets this long before a pickup may start (the next craft's first tick, the hook that records it)
    public static final double PLACE_GUARD_SECONDS = 1.0;

    // what a station pickup may do this tick. GO = start it, HOLD = it is owed but a short guard is still running, so
    // stand still instead of letting the next need walk us out of range, NO = nothing to do (or a reason that must never
    // hold us)
    public enum Start {
        GO, HOLD, NO
    }

    // the run moved on from the need that used the station (or has no needs left, or we do not know which one used it).
    // "after N seconds of not touching it" was the old rule and the bot was always out of range by then, the need boundary
    // is the one moment we are still standing next to it and know the next job does not want it. need names are the kit
    // catalogue's, null = nothing left to do
    public static boolean atNeedBoundary(String usedByNeed, String currentNeed) {
        return currentNeed == null || usedByNeed == null || !usedByNeed.equals(currentNeed);
    }

    // crafting table: take it at a boundary unless the next need is a craft, which would just place it again
    public static boolean wantsTableBack(String usedByNeed, String currentNeed) {
        return atNeedBoundary(usedByNeed, currentNeed) && !KitNeed.isCraftName(currentNeed);
    }

    // furnace: same boundary, but a craft does not use it (crafts use the table), so the only thing that keeps it is a
    // smelt coming up right now. busy ones (lit, or a background job owns it) never get here, the caller filters those
    public static boolean wantsFurnaceBack(String usedByNeed, String currentNeed, boolean smeltsSoon) {
        return atNeedBoundary(usedByNeed, currentNeed) && !smeltsSoon;
    }

    // iron_ingot with raw iron in the bag is the one need that is about to put something in a furnace
    public static boolean smeltsSoon(String currentNeed, int rawIron) {
        // and the cook is the other, the raw meat is about to go in the furnace we are standing next to
        return ("iron_ingot".equals(currentNeed) && rawIron > 0) || KitNeed.isCookName(currentNeed);
    }

    // the cheapest of our own tables to walk to that passes `usable` and costs at most `budget` (WalkCost, not a sphere: a
    // table 10 blocks straight down is a cave trip and the sphere called it close). null if none. nothing that is not in
    // the list can ever come back from here, that is the whole point
    public static RunState.Pos nearest(List<RunState.Pos> tables, Predicate<RunState.Pos> usable, double px, double py, double pz, double budget) {
        RunState.Pos best = null;
        double bestCost = budget;
        for (RunState.Pos table : tables) {
            double cost = walkCost(table, px, py, pz);
            if (cost <= bestCost && usable.test(table)) {
                best = table;
                bestCost = cost;
            }
        }
        return best;
    }

    // x/z to the middle of the block, y from our feet to the bottom of it (a table next to us is on our own level)
    public static double walkCost(RunState.Pos table, double px, double py, double pz) {
        return WalkCost.estimate(table.x + 0.5 - px, table.y - py, table.z + 0.5 - pz);
    }

    // a station we are too far from (in walking terms) is not worth the trip back, a log is cheaper. forget it so it does
    // not come back as a candidate the next time we wander past, and does not hold a phase open as "owed". `keep` is for
    // the ones somebody else owns (a smelting job). returns how many went
    public static int forgetFar(List<RunState.Pos> tables, Predicate<RunState.Pos> keep, double px, double py, double pz, double budget) {
        int before = tables.size();
        tables.removeIf(table -> walkCost(table, px, py, pz) > budget && !keep.test(table));
        return before - tables.size();
    }

    // a craft happened at the table since it was placed. this is what stops a place/pickup loop without a long cooldown: no
    // craft in between, no pickup (by the rule below). lastUse is stamped every tick the menu is open, lastPlace when it went down
    public static boolean usedSincePlaced(long lastUse, long lastPlace) {
        return lastUse != NEVER && (lastPlace == NEVER || lastUse > lastPlace);
    }

    // how long the menu has to stay shut before we call the crafting finished. CraftInTableTask closes and reopens it between
    // the steps of one chain (wooden pickaxe, then the stone one) and we must not pick the table up in the gap
    public static final double SETTLE_SECONDS = 1.0;

    // the table has done its job: it was used since it went down, the menu is shut and has been for a second, nothing in the
    // task tree is crafting at a table, and the next need is not a craft either (that one would place it again). it does not
    // wait for a need boundary, bread in the middle of a food need is done as soon as the bread is
    public static boolean finishedCrafting(long now, long lastUse, long lastPlace, boolean menuOpen, boolean craftRunning, boolean nextNeedCrafts) {
        return !menuOpen && !craftRunning && !nextNeedCrafts && usedSincePlaced(lastUse, lastPlace)
                && now - lastUse >= SETTLE_SECONDS * 20;
    }

    // how long a craft that is still ahead keeps a table that just finished one. not forever: a food need can wander off
    // hunting for minutes, and by then the table is a log we are too far away to be owed
    public static final double AHEAD_HOLD_SECONDS = 45;

    // the food need crafts inside itself (hoe, wheat, bread are not KitNeeds, the planner never sees them), so a plan with
    // no craft in it can still have one coming. 16:50 run: hoe done, table taken back at once, bread 15 s later placed a new one
    public static boolean needCraftsInside(String currentNeed) {
        return KitNeed.FOOD.equals(currentNeed);
    }

    // finishedCrafting, except a craft that is still ahead (anywhere in the plan, or inside the running need) holds the
    // table for AHEAD_HOLD_SECONDS after the last use. the old answer picked it up between the hoe and the bread
    public static boolean finishedCrafting(long now, long lastUse, long lastPlace, boolean menuOpen, boolean craftRunning,
                                           boolean nextNeedCrafts, boolean craftAhead) {
        if (!finishedCrafting(now, lastUse, lastPlace, menuOpen, craftRunning, nextNeedCrafts)) {
            return false;
        }
        return !craftAhead || now - lastUse >= AHEAD_HOLD_SECONDS * 20;
    }

    // either reason to take the table back: the crafting is over (new rule), or the run moved on to a need that does not
    // craft (old one, the fallback for a table that was placed and never opened). that fallback has one exception: a table
    // nobody has used yet with a craft still somewhere in the plan is waiting for that craft (a log trip first, the planner
    // is allowed to want wood before the menu opens), taking it would just place it again 5 seconds later
    public static boolean wantsTableNow(String usedByNeed, String currentNeed, boolean finishedCrafting, boolean usedSincePlaced,
                                        boolean craftPlanned) {
        return finishedCrafting || (wantsTableBack(usedByNeed, currentNeed) && (usedSincePlaced || !craftPlanned));
    }
}
