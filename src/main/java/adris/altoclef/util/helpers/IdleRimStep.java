package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;

// standing still on a lava lip with a hoglin walking up and nothing committed. the rim steps live in the fights, and a fight
// starts on the hit tick, which is the tick a hoglin throws us into the lake (it throws through a shield too). so the idle
// brain gets one walk to ground before anybody swings. no world in here, CombatBrain does the looking
public final class IdleRimStep {
    // stood still this long: a path that is still being thought about (the sweep failing its calcs at the lip) is not
    // walking us anywhere, a path that walks is the executor's business
    public static final int STILL_TICKS = 10;
    // stood still this long with a calc on and it's not a walk getting going: one failed search times out in about two seconds
    public static final int CALC_STILL_TICKS = 50;
    // a hoglin covers this in about two seconds, which is how long a ground walk takes
    public static final double FOE_RANGE = 8;
    // same cap as the rim guards (CombatRules.RIM_GUARD_CAP_TICKS): a foe that hangs about at six blocks doesn't get to pin
    // the sweep on a safe cell for good
    public static final int HOLD_CAP_TICKS = 200;
    // after a hold the sweep gets its turn. its partial path walks straight back to the lip, and with no cooldown that is a
    // step off, a walk back, a step off, forever
    public static final int COOLDOWN_TICKS = 200;
    // nowhere to step is asked again a bit later, not every tick (groundCell is a few thousand cell lookups)
    public static final int NO_CELL_RETRY_TICKS = 40;

    public enum End { NONE, FOE_GONE, CAPPED, COMMITTED }

    private boolean holding;
    private long start;
    private long quietUntil = Long.MIN_VALUE / 2;
    private long noCellSaidAt = Long.MIN_VALUE / 2;

    // the cheap half of the trigger, everything but the cell scan. nether only (the lakes there are the kit killers), nothing
    // committed, no path held, stood still, a melee foe close, and not already swimming (the escape owns that).
    // "a path held" and not isPathing: the walk's rim shield pauses the path to stand us still, isPathing reads false the whole
    // time, and the step went looking for ground in the middle of a bridge crossing every two seconds.
    // a calc only counts for a short stand: a walk that's about to start thinks for a tick or forty, the sweep failing at the lip
    // thinks back to back for half a minute and that's exactly the stand this is for
    public static boolean gate(boolean nether, boolean idle, boolean pathHeld, boolean calculating, boolean inLava, int stillTicks, double foeDistance) {
        return nether && idle && !pathHeld && !(calculating && stillTicks < CALC_STILL_TICKS) && !inLava
                && stillTicks >= STILL_TICKS && foeDistance <= FOE_RANGE;
    }

    // why our spot is a lip, null when it isn't one. a drop into lava next to us, or lava within LavaShore.NEAR at or under our
    // feet level (the lake two down beside us is the usual one, lavaNear stops one under the feet so lavaDeepNear asks too)
    public static String why(Cells cells, double x, double y, double z) {
        if (LavaShore.lavaDropNear(cells, x, y, z)) {
            return "lava drop by our feet";
        }
        if (LavaShore.lavaNear(cells, x, y, z, LavaShore.NEAR) || LavaShore.lavaDeepNear(cells, x, y, z, LavaShore.NEAR)) {
            return "lava within " + LavaShore.NEAR;
        }
        return null;
    }

    // on the cell: same column, give or take a block of height (soul sand, a slab, a knock that left us a step up)
    public static boolean arrived(double x, double y, double z, int cx, int cy, int cz) {
        return (int) Math.floor(x) == cx && (int) Math.floor(z) == cz && Math.abs(y - cy) <= 1.0;
    }

    public boolean holding() {
        return holding;
    }

    // free to start one: not holding, not in the cooldown
    public boolean ready(long now) {
        return !holding && now >= quietUntil;
    }

    public void start(long now) {
        holding = true;
        start = now;
    }

    // true when it's worth a line: a 1-wide bridge has no cell for its whole length, and one line a retry is a wall of them
    public boolean noCell(long now) {
        quietUntil = now + NO_CELL_RETRY_TICKS;
        if (now - noCellSaidAt < COOLDOWN_TICKS) {
            return false;
        }
        noCellSaidAt = now;
        return true;
    }

    // ticks into the current hold
    public long held(long now) {
        return now - start;
    }

    // while holding: why it ends this tick, NONE to keep at it. a fight or a run taking over has its own rim rules and isn't a
    // hold the sweep needs protecting from, so that one skips the cooldown
    public End check(long now, boolean idle, double foeDistance) {
        if (!holding) {
            return End.NONE;
        }
        End end = !idle ? End.COMMITTED
                : foeDistance > FOE_RANGE ? End.FOE_GONE
                : now - start >= HOLD_CAP_TICKS ? End.CAPPED
                : End.NONE;
        if (end != End.NONE) {
            holding = false;
            if (end != End.COMMITTED) {
                quietUntil = now + COOLDOWN_TICKS;
            }
        }
        return end;
    }

    public void reset() {
        holding = false;
        start = 0;
        quietUntil = Long.MIN_VALUE / 2;
        noCellSaidAt = Long.MIN_VALUE / 2;
    }
}
