package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;

// fighting next to lava, no world: cells come in through a lookup so the whole thing can be tested without minecraft.
//
// the nether deaths were never the mob. a magma cube bites for two hearts, the lava finishes the rest. a hit throws us about
// two blocks when we stand still and next to nothing when we lean into it, and walking up to a foe on a shore either puts us
// in the lava ourselves or leaves us on the rim for the next hit. so: no approach walks near lava, and when the way behind us
// is a drop we press toward the foe instead of standing there to be thrown
public final class LavaShore {
    // how close lava has to be (horizontally) for a spot to count as a shore
    static final int NEAR = 2;
    // a knock is about two blocks, three is the margin
    static final double BACK_LENGTH = 3.0;
    // half the player's width, the swept box
    static final double HALF_WIDTH = 0.3;
    static final double STEP = 0.5;
    // the lava check around the feet after a landing
    static final int LANDING_RADIUS = 3;

    private LavaShore() {
    }

    // lava in the columns within r of (x, z), at the feet cell and the one under it
    public static boolean lavaNear(Cells cells, double x, double y, double z, int r) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (cells.at(cx + dx, cy, cz + dz) == Cell.LAVA || cells.at(cx + dx, cy - 1, cz + dz) == Cell.LAVA) {
                    return true;
                }
            }
        }
        return false;
    }

    // the straight walk from a to b crosses a lava cell (feet level or the one under it, the y slides from one end to the other)
    public static boolean lineHitsLava(Cells cells, double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(len / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int cx = (int) Math.floor(ax + dx * t);
            int cz = (int) Math.floor(az + dz * t);
            int cy = (int) Math.floor(ay + (by - ay) * t);
            if (cells.at(cx, cy, cz) == Cell.LAVA || cells.at(cx, cy - 1, cz) == Cell.LAVA) {
                return true;
            }
        }
        return false;
    }

    // the approach guard: no walking at a foe that is by the lava, from a spot that is by the lava, or across it
    public static boolean approachBlocked(Cells cells, double ux, double uy, double uz, double tx, double ty, double tz) {
        return lavaNear(cells, tx, ty, tz, NEAR) || lavaNear(cells, ux, uy, uz, NEAR)
                || lineHitsLava(cells, ux, uy, uz, tx, ty, tz);
    }

    // is a spot a bad place to land: lava in it, or open air over lava or over nothing for CliffAvoidance.DEPTH blocks.
    // the box is the player's width, so a knock that clips a corner counts
    public static boolean spotUnsafe(Cells cells, double x, double y, double z) {
        int cy = (int) Math.floor(y);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int cx = (int) Math.floor(x + sx * HALF_WIDTH);
                int cz = (int) Math.floor(z + sz * HALF_WIDTH);
                if (cells.at(cx, cy, cz) == Cell.LAVA || cells.at(cx, cy - 1, cz) == Cell.LAVA
                        || CliffAvoidance.columnUnsafe(cells, cx, cy, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // the line from the foe through us, BACK_LENGTH long, as a swept box at STEP spacing: does the knock land somewhere bad
    public static boolean backUnsafe(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        double dx = ux - fx;
        double dz = uz - fz;
        double len = Math.hypot(dx, dz);
        // right on top of each other there is no "behind", the foe's own spot is the best guess for none
        if (len < 0.05) {
            return false;
        }
        dx /= len;
        dz /= len;
        for (double d = STEP; d <= BACK_LENGTH + 1e-9; d += STEP) {
            if (spotUnsafe(cells, ux + dx * d, uy, uz + dz * d)) {
                return true;
            }
        }
        return false;
    }

    // the keys to press to lean toward the foe, from where the head points. no look added: whatever the yaw is, the keys
    // are picked to walk at the foe from there. yaw is minecraft's (0 = south, +z), dx/dz is us to the foe
    public record Keys(boolean forward, boolean back, boolean left, boolean right) {
        public static final Keys NONE = new Keys(false, false, false, false);

        public boolean any() {
            return forward || back || left || right;
        }
    }

    public static Keys keysToward(float yawDegrees, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.05) {
            return Keys.NONE;
        }
        double yaw = Math.toRadians(yawDegrees);
        double fwd = (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / len;
        double rgt = (dx * -Math.cos(yaw) + dz * -Math.sin(yaw)) / len;
        // sin(22.5): eight directions, a key is down when the foe is more than that far off the other axis
        double edge = 0.3827;
        return new Keys(fwd > edge, fwd < -edge, rgt < -edge, rgt > edge);
    }

    // the whole hold call. nether only. on the ground that is standing (no path) with an unsafe back, in the air it is a fresh
    // hit (hurtTime) with an unsafe back or a path that was going somewhere. never when the cell ahead is itself bad
    public static boolean hold(boolean nether, boolean onGround, boolean freshHit, boolean pathLive, boolean backUnsafe, boolean aheadUnsafe) {
        if (!nether || aheadUnsafe) {
            return false;
        }
        if (onGround) {
            return backUnsafe && !pathLive;
        }
        return freshHit && (backUnsafe || pathLive);
    }

    // landed after a hit that came with a path: the executor would walk the old dest across whatever lava the knock put
    // between us, so throw the path away when we are off it and lava is close. the replan is next tick
    public static boolean cancelOnLanding(boolean nether, boolean armed, boolean onGround, boolean feetInValid, boolean lavaClose) {
        return nether && armed && onGround && !feetInValid && lavaClose;
    }

    public static boolean lavaClose(Cells cells, double x, double y, double z) {
        return lavaNear(cells, x, y, z, LANDING_RADIUS);
    }
}
