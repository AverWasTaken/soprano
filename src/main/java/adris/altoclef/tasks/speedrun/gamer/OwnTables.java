package adris.altoclef.tasks.speedrun.gamer;

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
    // the need boundary rules below are the real decision now, so this is down to two small guards: a station that is open or was
    // placed a moment ago gets a few seconds (the debounce, menu closing and the next craft's first tick), and after one
    // successful pickup of a kind we leave that kind alone for a good while, so a place/pickup loop we did not think of
    // costs one pickup per cooldown instead of every tick. stamps are game ticks, NEVER = none yet
    public static boolean mayStartRecovery(long now, long lastUse, long lastRecovered, boolean menuOpen, double useCooldownSeconds, double recoverCooldownSeconds) {
        if (menuOpen) {
            return false;
        }
        if (lastUse != NEVER && now - lastUse < useCooldownSeconds * 20) {
            return false;
        }
        return lastRecovered == NEVER || now - lastRecovered >= recoverCooldownSeconds * 20;
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
        return "iron_ingot".equals(currentNeed) && rawIron > 0;
    }

    // the closest of our own tables that passes `usable` and is within `radius` of the player, null if none. nothing
    // that is not in the list can ever come back from here, that is the whole point
    public static RunState.Pos nearest(List<RunState.Pos> tables, Predicate<RunState.Pos> usable, double px, double py, double pz, double radius) {
        RunState.Pos best = null;
        double bestDist = radius * radius;
        for (RunState.Pos table : tables) {
            double dx = table.x + 0.5 - px;
            double dy = table.y + 0.5 - py;
            double dz = table.z + 0.5 - pz;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist && usable.test(table)) {
                best = table;
                bestDist = dist;
            }
        }
        return best;
    }
}
