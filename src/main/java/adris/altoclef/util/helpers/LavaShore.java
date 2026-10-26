package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;

// fighting next to lava, no world: cells come in through a lookup so the whole thing can be tested without minecraft.
//
// a hit throws us about two blocks when we stand still and next to nothing when we lean into it, and walking up to a foe
// on a shore puts us in the lava or leaves us on the rim for the next hit. so no approach walks near lava, and when the way
// behind us is a drop we press toward the foe instead of standing there to be thrown
public final class LavaShore {
    // how close lava has to be (horizontally) for a spot to count as a shore
    static final int NEAR = 2;
    // a knock is about two blocks, three is the margin
    static final double BACK_LENGTH = 3.0;
    // half the player's width, the swept box
    static final double HALF_WIDTH = 0.3;
    static final double STEP = 0.5;
    // how close a drop has to be, in any direction, to count as an edge we are standing on
    static final double EDGE_RADIUS = 1.5;
    // finer than STEP: a drop a block away has to land in a sample, 0.5 jumps over the corner of a cell
    static final double EDGE_STEP = 0.25;
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

    // how long a foe by the lava gets to walk up to us before we stop waiting on it: 8 s. a wait with no end
    // is a bot standing still at a pool until something else gives
    // (was 240 once. CombatCommit.STALL_TICKS is 200 and ends the fight first, so the ban never landed and the same cube got
    // picked again with its count still running. it has to be under the stall to ever matter)
    public static final int WAIT_CAP_TICKS = 160;

    // approachBlocked with the kind of target in it. a cow does not knock us in and baritone paths around lava on its own,
    // so the only thing that matters for a passive one is the drop burning when it dies by the pool. a hostile still gets the
    // whole check (our spot, the line)
    public static boolean blockedFor(boolean passive, Cells cells, double ux, double uy, double uz, double tx, double ty, double tz) {
        return passive ? lavaNear(cells, tx, ty, tz, NEAR) : approachBlocked(cells, ux, uy, uz, tx, ty, tz);
    }

    // should this foe be let go of right now: passive ones by the pool never get a wait, hostiles get WAIT_CAP_TICKS of it
    public static boolean giveUp(boolean passive, int waitedTicks) {
        return passive || waitedTicks >= WAIT_CAP_TICKS;
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

    // a lethal drop within EDGE_RADIUS of us in any direction, not only behind along the foe line: a split or a second foe hits from
    // wherever it likes, and the knock goes the other way. sampled on a quarter block grid (strictly inside the radius, so a drop exactly 1.5 away from a cell centre is the next cell's business), same unsafe test as the knock line. a
    // wall at the feet or head of a cell stops a knock (same rule as dropBeside), so that cell is no edge
    public static boolean edgeNear(Cells cells, double ux, double uy, double uz) {
        int cy = (int) Math.floor(uy);
        for (double dx = -EDGE_RADIUS; dx < EDGE_RADIUS; dx += EDGE_STEP) {
            for (double dz = -EDGE_RADIUS; dz < EDGE_RADIUS; dz += EDGE_STEP) {
                if (Math.hypot(dx, dz) >= EDGE_RADIUS) {
                    continue;
                }
                int cx = (int) Math.floor(ux + dx);
                int cz = (int) Math.floor(uz + dz);
                if (cells.at(cx, cy, cz) == Cell.SOLID || cells.at(cx, cy + 1, cz) == Cell.SOLID) {
                    continue;
                }
                if (cells.at(cx, cy, cz) == Cell.LAVA || cells.at(cx, cy - 1, cz) == Cell.LAVA || CliffAvoidance.columnUnsafe(cells, cx, cy, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // the stepping check: the knock line behind us, or a drop anywhere close
    public static boolean rimUnsafe(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        return backUnsafe(cells, ux, uy, uz, fx, fz) || edgeNear(cells, ux, uy, uz);
    }

    // a back-off walks away from the foe and only checks headroom, never edges, so on a rim it is not ours to start
    public static boolean backOffAllowed(boolean rim) {
        return !rim;
    }

    // on a rim a landed hit is worth more than a crit, and waiting for the ground after the first knock is how the second
    // one comes. the attack cooldown still applies downstream
    public static boolean swingReady(boolean gatesOpen, boolean onGround, boolean critInFlight, boolean backUnsafe) {
        return gatesOpen && (onGround || critInFlight || backUnsafe);
    }

    // is a cell fit to stand on while a foe is on us: floor under it, room for the body, no lava next to it, and nothing
    // bad behind it relative to the foe, and no drop within EDGE_RADIUS either (so a stair step ledge does not trade one edge for the next)
    static boolean standable(Cells cells, int x, int y, int z, double fx, double fz) {
        if (cells.at(x, y - 1, z) != Cell.SOLID || cells.at(x, y, z) != Cell.OPEN || cells.at(x, y + 1, z) != Cell.OPEN) {
            return false;
        }
        double cx = x + 0.5;
        double cz = z + 0.5;
        return !lavaNear(cells, cx, y, cz, 1) && !spotUnsafe(cells, cx, y, cz) && !backUnsafe(cells, cx, y, cz, fx, fz)
                && !edgeNear(cells, cx, y, cz) && !dropBeside(cells, x, y, z);
    }

    // a knock does not only go along the line from the foe: a hit from the side can throw us off a drop beside the cell, and
    // the line check never looked sideways. every neighbour has to be a wall (nothing gets thrown into one) or
    // have a landing (solid or water) within 3 cells down. an unloaded cell reads solid, same as everywhere else
    static boolean dropBeside(Cells cells, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (cells.at(nx, y, nz) == Cell.SOLID || cells.at(nx, y + 1, nz) == Cell.SOLID) {
                    continue;
                }
                boolean landing = false;
                for (int d = 1; d <= 3; d++) {
                    Cell c = cells.at(nx, y - d, nz);
                    if (c != Cell.OPEN) {
                        landing = c == Cell.SOLID || c == Cell.WATER;
                        break;
                    }
                }
                if (!landing) {
                    return true;
                }
            }
        }
        return false;
    }

    // standable, and not the foe's own cell
    public static boolean stepFit(Cells cells, int x, int y, int z, double fx, double fz) {
        return standable(cells, x, y, z, fx, fz) && Math.hypot(x + 0.5 - fx, z + 0.5 - fz) >= 1.0;
    }

    // the one-block step off a rim: null when our spot is fine (or nothing around is better), else {dx, dz} to a neighbour
    // cell that is standable. no lava within 2 wins over a cell that is merely not-on-fire, then the one nearest the foe, so
    // the step also closes the gap instead of backing off. a cell the foe is standing in is no step
    public static int[] safeStep(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        if (!rimUnsafe(cells, ux, uy, uz, fx, fz)) {
            return null;
        }
        int cx = (int) Math.floor(ux);
        int cy = (int) Math.floor(uy);
        int cz = (int) Math.floor(uz);
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || !stepFit(cells, cx + dx, cy, cz + dz, fx, fz)) {
                    continue;
                }
                double foeGap = Math.hypot(cx + dx + 0.5 - fx, cz + dz + 0.5 - fz);
                double score = foeGap + (lavaNear(cells, cx + dx + 0.5, cy, cz + dz + 0.5, NEAR) ? 100 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{dx, dz};
                }
            }
        }
        return best;
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

    // the whole hold call. nether only, and only with no path: a running movement wipes the keys every tick, so a knock that
    // comes mid-path is the landing cancel's job. on the ground it is standing with an unsafe back, in the air a fresh mob hit
    // with an unsafe back. never when the cell ahead is itself bad
    public static boolean hold(boolean nether, boolean onGround, boolean freshHit, boolean pathLive, boolean backUnsafe, boolean aheadUnsafe) {
        if (!nether || aheadUnsafe || pathLive || !backUnsafe) {
            return false;
        }
        return onGround || freshHit;
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
