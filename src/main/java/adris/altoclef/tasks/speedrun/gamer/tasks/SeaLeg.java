package adris.altoclef.tasks.speedrun.gamer.tasks;

// the tunnel under the sea to a ring (or a start column) way out from the shore, as a string of short goals. one long goal never
// tunnels: an iron pick pays ~36 ticks a block of rock and the heuristic and a boat both say 3.5, so every partial path the search
// keeps is the surface. a goal STEP blocks off can only be reached through the rock, so it is.
// pure, the task feeds it the clock and where we are. nothing in here remembers a segment number: every waypoint is "STEP toward the
// target from where we stand now", so an interrupt, a relog or a fight that drags us off never leaves a waypoint stranded behind us
final class SeaLeg {
    static final int STEP = 14;
    // inside this of the target the leg is done and the room's own walk (or the dig) is a short goal again
    static final double HAND_OFF = RoomRetarget.ROOM_HALF;
    // a goal of 1 on a wet or water side cell is no goal at all (COST_INF), 3 always has some rock in it
    static final int GOAL_RANGE = 3;
    static final double PROGRESS_BLOCKS = RoomRetarget.PROGRESS_BLOCKS;
    // nothing closer in this long and the current waypoint is a dud
    static final double STUCK_SECONDS = 30;
    // deeper, then off to one side, then the other. after that it's not a dud waypoint, it's a wall
    static final int MAX_RETRIES = 3;
    private static final int[][] RETRY = {{0, 0}, {-3, 0}, {-3, 5}, {-3, -5}};
    // a waypoint this much further off than when we got it was issued from somewhere else (before a fight, before a relog)
    static final double STALE_BLOCKS = 8;
    // not ticked for this long = someone else had the wheel, the stuck clock starts over. same idea as WalkProgress
    private static final double IDLE_SECONDS = 5;
    // the lava lakes live down at the bottom, and no stronghold is under this
    static final int LEG_FLOOR_Y = -50;

    enum Kind {WALK, ARRIVED, FAILED}

    // what one tick decided. fresh = the waypoint is new this tick, the rest is for the log and the stall clock
    record Step(Kind kind, int[] waypoint, boolean fresh, boolean progressed, int retry) {
    }

    private int tx;
    private int ty;
    private int tz;
    private int[] waypoint;
    private double issuedDist;
    private int retry;
    private double best = Double.MAX_VALUE;
    private double bestAt;
    private double lastAt = Double.NaN;
    private boolean failed;

    SeaLeg(int tx, int ty, int tz) {
        target(tx, ty, tz);
    }

    // the ring turned up while we were tunnelling to the start column: same tunnel, better end
    void target(int x, int y, int z) {
        tx = x;
        ty = y;
        tz = z;
        waypoint = null;
        retry = 0;
        best = Double.MAX_VALUE;
        lastAt = Double.NaN;
    }

    int tx() {
        return tx;
    }

    int ty() {
        return ty;
    }

    int tz() {
        return tz;
    }

    boolean failed() {
        return failed;
    }

    boolean arrived(double px, double pz) {
        return left(px, pz) <= HAND_OFF;
    }

    // to the middle of the target block, same as the waypoints aim
    double left(double px, double pz) {
        return Math.hypot(tx + 0.5 - px, tz + 0.5 - pz);
    }

    // noPath = baritone searched everything and there's no way to the current waypoint
    Step tick(RoomRetarget.Probe probe, double px, double py, double pz, double now, boolean noPath) {
        if (failed) {
            return new Step(Kind.FAILED, waypoint, false, false, retry);
        }
        if (arrived(px, pz)) {
            return new Step(Kind.ARRIVED, waypoint, false, false, retry);
        }
        if (Double.isNaN(lastAt) || now - lastAt > IDLE_SECONDS) {
            bestAt = now;
            if (waypoint != null && stale(px, py, pz)) {
                waypoint = null;
            }
        }
        lastAt = now;
        boolean progressed = false;
        double dist = left(px, pz);
        if (dist <= best - PROGRESS_BLOCKS) {
            best = dist;
            bestAt = now;
            progressed = true;
        }
        if (waypoint != null && reached(waypoint, px, py, pz)) {
            // got there, whatever was in the way of the last one isn't in the way of this one
            waypoint = null;
            retry = 0;
            bestAt = now;
        } else if (waypoint != null && stale(px, py, pz)) {
            waypoint = null;
        } else if (waypoint != null && (noPath || now - bestAt >= STUCK_SECONDS)) {
            retry++;
            waypoint = null;
            bestAt = now;
            if (retry > MAX_RETRIES) {
                failed = true;
                return new Step(Kind.FAILED, null, false, progressed, retry);
            }
        }
        boolean fresh = waypoint == null;
        if (fresh) {
            waypoint = waypoint(probe, px, pz, tx, ty, tz, retry);
            issuedDist = dist3(waypoint, px, py, pz);
        }
        return new Step(Kind.WALK, waypoint, fresh, progressed, retry);
    }

    private boolean stale(double px, double py, double pz) {
        return dist3(waypoint, px, py, pz) > issuedDist + STALE_BLOCKS;
    }

    private static double dist3(int[] w, double px, double py, double pz) {
        return Math.sqrt(sq(w[0] + 0.5 - px) + sq(w[1] - py) + sq(w[2] + 0.5 - pz));
    }

    // GoalNear's own test, on the block we stand in
    static boolean reached(int[] w, double px, double py, double pz) {
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        return sq(bx - w[0]) + sq(by - w[1]) + sq(bz - w[2]) <= GOAL_RANGE * GOAL_RANGE;
    }

    // STEP toward the target from here, pushed sideways on a retry, at the tunnel depth of that column
    static int[] waypoint(RoomRetarget.Probe probe, double px, double pz, int tx, int ty, int tz, int retry) {
        double dx = tx + 0.5 - px;
        double dz = tz + 0.5 - pz;
        double len = Math.hypot(dx, dz);
        double step = Math.min(STEP, len);
        double ux = len == 0 ? 0 : dx / len;
        double uz = len == 0 ? 0 : dz / len;
        int[] off = RETRY[Math.min(retry, MAX_RETRIES)];
        // sideways is the perpendicular, (-uz, ux)
        int x = (int) Math.floor(px + ux * step - uz * off[1]);
        int z = (int) Math.floor(pz + uz * step + ux * off[1]);
        return new int[]{x, legY(probe, x, z, ty, off[0]), z};
    }

    // TUNNEL_UNDER_SEABED under this column's sea floor, but no higher than just over the ring (the shaft got us about there).
    // never more than NEAR_VERTICAL under the ring either, or the hand off lands somewhere the room calls far and it retargets.
    // that one gives way to the rock though: a trench deeper than the ring is two blocks of floor over our heads, not open water
    static int legY(RoomRetarget.Probe probe, int x, int z, int ty, int deeper) {
        if (!probe.loaded(x, z)) {
            // can't see it, which within twenty blocks of us shouldn't happen. stay at the level we were sent to
            return Math.max(ty + RoomRetarget.UNDER_SEA_TURN_ABOVE + deeper, LEG_FLOOR_Y);
        }
        int floor = RoomRetarget.seaFloorY(probe, x, z);
        int y = Math.min(ty + RoomRetarget.UNDER_SEA_TURN_ABOVE, floor - RoomRetarget.TUNNEL_UNDER_SEABED) + deeper;
        y = Math.max(y, Math.min(ty - (int) RoomRetarget.NEAR_VERTICAL, floor - 2));
        return Math.max(y, LEG_FLOOR_Y);
    }

    private static double sq(double v) {
        return v * v;
    }
}
