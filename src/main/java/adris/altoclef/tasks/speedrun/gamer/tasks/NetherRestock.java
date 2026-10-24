package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;

// pure: when does the sweep stop walking to dig itself some blocks? the bridges over lava eat throwaways and nothing in the
// nether hands them back, so a bag that ran dry mid-walk turns every path after it into "0 blocks closer"
public final class NetherRestock {
    // under this many the next bridge is a coin flip
    static final int LOW = 8;
    // after a waypoint was given up on the bag is the first suspect, so the bar is higher
    static final int GIVE_UP_LOW = 24;
    public static final int TARGET = 64;
    // netherrack is everywhere but the digging can still go nowhere (lava all around): stop trying after this
    static final double MAX_SECONDS = 75;
    static final double COOLDOWN_SECONDS = 120;
    // one empty tracker snapshot is not the end of the blocks, same grace as the gold dig
    static final double EXHAUSTED_SECONDS = 4;

    private boolean active;
    private int activeStopAt = TARGET;
    private double startedAt;
    private double exhaustedSince = Double.NaN;
    private double coolUntil = Double.NEGATIVE_INFINITY;
    // the last dig ended on the clock: a whole MAX_SECONDS with nothing to show for it, so even an empty bag waits
    private boolean clockEnd;
    // restocks in a row that ended because the dig had nothing left to go to (not the clock, not a full bag). the phase reads it to
    // tell "short" from "short and the band is empty"
    private int exhaustedEnds;

    public int exhaustedEnds() {
        return exhaustedEnds;
    }

    public void clearExhaustedEnds() {
        exhaustedEnds = 0;
    }

    public boolean isActive() {
        return active;
    }

    // the dig stays in a box around where it started, a few blocks either way in height and not far off sideways. on a ledge
    // everything level with the feet can be a cliff face a long way up or across, so the box reaches down a bit too: the
    // netherrack below the ledge is nearer than any of it. a bridge over lava still touches lava, and that reject (touchesLava)
    // is what keeps us from digging out the span we stand on. the one block under the feet is never fair game, it is the floor
    static final int BAND_RADIUS = 48;
    static final int BAND_BELOW = 6;
    static final int BAND_ABOVE = 4;

    public static boolean inRestockBand(BlockPos origin, BlockPos pos) {
        int dy = pos.getY() - origin.getY();
        if (dy < -BAND_BELOW || dy > BAND_ABOVE) {
            return false;
        }
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        if (dx == 0 && dz == 0 && dy == -1) {
            return false;
        }
        return dx * dx + dz * dz <= BAND_RADIUS * BAND_RADIUS;
    }

    // held = every placeable throwaway in the bag. exhausted = the dig has nothing left to go to. true = dig (until the
    // target) instead of walking
    public boolean update(int held, boolean gaveUp, boolean hasPick, boolean exhausted, double now) {
        return update(held, gaveUp ? GIVE_UP_LOW : LOW, hasPick, exhausted, now);
    }

    // startBelow = the bag size that starts a dig. the sweep goes by LOW / GIVE_UP_LOW, a caller that knows a long walk is
    // coming can ask for a full top up
    public boolean update(int held, int startBelow, boolean hasPick, boolean exhausted, double now) {
        return update(held, startBelow, TARGET, hasPick, exhausted, now);
    }

    // stopAt = the bag size that ends the dig, for a caller that only wants a short top up
    public boolean update(int held, int startBelow, int stopAt, boolean hasPick, boolean exhausted, double now) {
        if (!hasPick) {
            active = false;
            return false;
        }
        if (active) {
            // every way out cools down, a finished restock re-triggering a few blocks later is how it ran 8 times a visit
            if (!exhausted) {
                exhaustedSince = Double.NaN;
            } else if (Double.isNaN(exhaustedSince)) {
                exhaustedSince = now;
            }
            boolean dry = !Double.isNaN(exhaustedSince) && now - exhaustedSince >= EXHAUSTED_SECONDS;
            if (held >= activeStopAt || dry || now - startedAt >= MAX_SECONDS) {
                exhaustedEnds = held < activeStopAt && dry ? exhaustedEnds + 1 : 0;
                clockEnd = held < activeStopAt && !dry;
                active = false;
                exhaustedSince = Double.NaN;
                coolUntil = now + COOLDOWN_SECONDS;
            }
            return active;
        }
        // an empty bag digs again right away, the cooldown is for a bag that is merely getting low. a dig that ran out the clock
        // is the exception, the same spot would just eat another MAX_SECONDS
        if (now < coolUntil && (held >= LOW || clockEnd)) {
            return false;
        }
        if (held < startBelow) {
            active = true;
            activeStopAt = stopAt;
            startedAt = now;
            exhaustedSince = Double.NaN;
        }
        return active;
    }
}
