package adris.altoclef.tasks.speedrun.gamer;

import java.util.List;
import java.util.function.Predicate;

// the crafting tables this run put down itself. the table pickup in PrepSupport used to take the nearest crafting table
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

    // may a table pickup START right now. the pickup preempts the kit task, and some "gathering" needs craft at a table
    // halfway through (smoker, shears, iron pickaxe), so without this we took the table back before the craft could use
    // it, the craft placed a new one, repeat forever. two guards: a table in use (open, or placed a moment ago) is not
    // spare, and after one successful pickup we leave it alone for a good while, so even a loop we did not think of
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
