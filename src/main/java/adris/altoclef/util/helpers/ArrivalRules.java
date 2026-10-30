package adris.altoclef.util.helpers;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

// what to do in the first seconds after a dimension change: wait for the ground to exist, and back out of a portal that
// dumped us in lava. pure
public final class ArrivalRules {

    // the client has no chunks right after a dimension change, and the planner reads missing chunks as air. three seconds
    // is more than the server ever needs, the cap is only so a chunk that never comes can't freeze us
    public static final int HOLD_CAP_TICKS = 60;
    // how long after arriving a lava dunk still counts as the portal's doing
    public static final int STEP_BACK_TICKS = 200;
    // a step back that started keeps the wheel this long after arriving, whatever the start window said (the teleport takes ~4 s)
    public static final int STEP_BACK_LATCH_TICKS = 400;
    // a portal that touched us right after a teleport holds its cooldown up while we stand in it, so a stand that goes
    // nowhere this long steps out for a moment and comes back
    public static final int PORTAL_WAIT_TICKS = 60;
    public static final int PORTAL_BACKOFF_TICKS = 15;
    // a second lava arrival at the portal within this long gets a line in the log
    public static final long REPEAT_MS = 5 * 60 * 1000;
    // the box around the feet that has to be loaded, in blocks
    public static final int LOOK_RADIUS = 2;
    public static final int PORTAL_REACH = 2;

    private ArrivalRules() {
    }

    public static boolean hold(int ticksSinceArrival, boolean boxLoaded) {
        return !boxLoaded && ticksSinceArrival < HOLD_CAP_TICKS;
    }

    // `latched` = a step back already started, it keeps going while we stand in the portal waiting to be sent home
    // (the teleport takes ~4 s, long after the lava is behind us), so only the start has the short window
    public static boolean stepBack(boolean nether, int ticksSinceArrival, boolean inLava, boolean latched, boolean portalNear) {
        if (!nether || !portalNear) return false;
        return latched ? ticksSinceArrival <= STEP_BACK_LATCH_TICKS : inLava && ticksSinceArrival <= STEP_BACK_TICKS;
    }

    // the closest portal cell within reach of the feet, a little more up than down (the frame sits a block or two over us)
    public static BlockPos nearestPortal(BlockPos at, Predicate<BlockPos> isPortal) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -PORTAL_REACH; dx <= PORTAL_REACH; dx++) {
            for (int dy = -1; dy <= PORTAL_REACH; dy++) {
                for (int dz = -PORTAL_REACH; dz <= PORTAL_REACH; dz++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestDist && isPortal.test(p)) {
                        best = p;
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }
}
