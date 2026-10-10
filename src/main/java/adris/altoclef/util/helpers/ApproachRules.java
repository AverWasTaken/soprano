package adris.altoclef.util.helpers;

// who owns the walk to a mob while we are close to it. no world, just answers, same deal as EntityBlockerRules.
//
// the approach child (GetToEntityTask) cuts the path when it starts and when it stops, so every start and stop is a
// walking step thrown away. the kill task used to stop it for any tick where a swing was not allowed yet (baritone
// halfway through a step, the cooldown, a hop) and start it again the moment the mob was a hair past the back-off
// line, about once a second. the rule is: out of reach we walk, in reach we stand, and a child that is already
// walking finishes its step instead of being cut off mid-stride. in reach with a clear shot there is nothing for a
// fresh child to do, so it is never started there
public final class ApproachRules {
    public enum Plan {
        // swing (or crit) now, the child is not wanted, stopping it is what lets go of the path
        INTERACT,
        // the child that is walking keeps walking, the swing has to wait for a safe moment
        KEEP_CHILD,
        // in reach and nothing to walk: stand still, no child
        WAIT,
        // out of reach (or no clear shot): the approach, the same child as last tick if there was one
        APPROACH,
        // too close, no clear shot, nothing mid-step: shuffle
        WANDER
    }

    // 5 s. an unsafe step lasts a handful of ticks, an eating pause a couple of seconds, anything longer is not a hiccup
    public static final int WAIT_TICKS = 100;

    private ApproachRules() {
    }

    // childLive: the child was part of the tree last tick. inRange: within the reach setting. shot: the ray at the mob hits it.
    // ready: every gate on swinging except the path (eating, falling, the bucket, on the ground or hopping).
    // safeToCancel: baritone is not halfway through a step. tooClose: inside the back-off line. waitedLong: we have stood
    // WAIT_TICKS in a row already. a gate that stays shut (swimming, a ladder) must not freeze us in place for good, so
    // after that the old rules get a go again: walk if there is room, shuffle if not
    public static Plan plan(boolean childLive, boolean inRange, boolean shot, boolean ready, boolean safeToCancel, boolean tooClose, boolean waitedLong) {
        if (inRange && shot) {
            if (ready && safeToCancel) {
                return Plan.INTERACT;
            }
            if (childLive) {
                return Plan.KEEP_CHILD;
            }
            if (!waitedLong) {
                return Plan.WAIT;
            }
            if (!tooClose) {
                return Plan.APPROACH;
            }
            return safeToCancel ? Plan.WANDER : Plan.WAIT;
        }
        if (!tooClose) {
            return Plan.APPROACH;
        }
        if (!safeToCancel) {
            return childLive ? Plan.KEEP_CHILD : Plan.WAIT;
        }
        return Plan.WANDER;
    }
}
