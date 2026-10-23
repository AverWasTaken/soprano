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
    private double startedAt;
    private double exhaustedSince = Double.NaN;
    private double coolUntil = Double.NEGATIVE_INFINITY;
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

    // the dig stays in a box around where it started: nothing under the starting feet (that is how it ended up in a pit),
    // nothing far off (it once went for basalt 600 blocks away). the wall at feet level is fair game
    static final int BAND_RADIUS = 32;

    public static boolean inRestockBand(BlockPos origin, BlockPos pos) {
        // feet level and up only. the floor layer looked like a harmless ditch until it turned out to be the 1 wide bridge over
        // a lava lake we were standing on, dug out on both sides of us
        if (pos.getY() < origin.getY()) {
            return false;
        }
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        return dx * dx + dz * dz <= BAND_RADIUS * BAND_RADIUS;
    }

    // held = every placeable throwaway in the bag. exhausted = the dig has nothing left to go to. true = dig (until the
    // target) instead of walking
    public boolean update(int held, boolean gaveUp, boolean hasPick, boolean exhausted, double now) {
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
            if (held >= TARGET || dry || now - startedAt >= MAX_SECONDS) {
                exhaustedEnds = held < TARGET && dry ? exhaustedEnds + 1 : 0;
                active = false;
                exhaustedSince = Double.NaN;
                coolUntil = now + COOLDOWN_SECONDS;
            }
            return active;
        }
        if (now < coolUntil) {
            return false;
        }
        if (held < LOW || (gaveUp && held < GIVE_UP_LOW)) {
            active = true;
            startedAt = now;
            exhaustedSince = Double.NaN;
        }
        return active;
    }
}
