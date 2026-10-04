/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import java.util.concurrent.ForkJoinPool;

/**
 * A neo, flown tick by tick the way LivingEntity does it. Pure math so the planner can ask it things off thread
 */
final class NeoJump {

    // straight out of 1.21.4 LivingEntity, same numbers SprintJump uses. aiStep scales move input by 0.98
    private static final double GROUND_FRICTION = 0.6 * 0.91, AIR_FRICTION = 0.91, JUMP = 0.42, GRAVITY = 0.08, BOOST = 0.2;
    // sprinting only. walking never made a single one of these, the jump boost is doing all the work
    static final double SPRINT_GROUND = 0.13 * 0.98;
    private static final double AIR_ACCEL = 0.026 * 0.98;
    private static final double HALF = 0.3;
    // nothing past this matters, and nothing past it gets checked either (see clearance)
    private static final double CAP = 0.2;
    static final int MAX_DIST = 3, MAX_RUNWAY = 3;
    private static final int MAX_TICKS = 20, MAX_SWITCH = 12;
    // two policies, we fly whichever lands better. both start the same: run up holding some sideways spot, then jump.
    // hold: keep v out past the end of the wall, then at some tick settle back in toward the landing.
    // curve: jump a bit out, then turn in a little more every tick. a 2 thick wall is all curve. a hill climb over
    // every tick's heading found 0.04 of room (jump 15 out, then -9, -13, -14, ... -80) where hold found none
    private static final double[] RUN = {0.5, 0.7, 0.76}, HOLD = {0.88, 0.95, 1.02, 1.1}, GAIN = {0.4, 1.0}, SETTLE = {0.3, 0.55, 0.75};
    // the hill climb's curve turns in gently and then harder and harder, so the turn rate gets to grow too (bend)
    private static final double[] CURVE_JUMP = {0, 5, 10, 15, 20, 25, 30}, CURVE_START = {5, 0, -5, -10, -20}, CURVE_RATE = {0, 2, 4, 7, 10, 15}, CURVE_BEND = {0, 0.5, 1, 1.5};
    private static final int HOLD_POLICY = 0, CURVE_POLICY = 1;
    // how good a standing start has to look before A* is allowed to plan one, and before we commit to the jump
    static final double PLAN_MARGIN = 0.03, JUMP_MARGIN = 0.02;
    // where we stand before the run up, relative to the back of the runway
    // 0.76 is right on the edge of the bridge (0.04 of floor left under us), which is where the good runs start. every
    // bit further out we start is that much more room past the wall, a 2 thick wall goes 0.025 -> 0.04 between 0.74 and 0.76
    private static final double[][] STAGING = {{0.4, 0.5}, {0.4, 0.7}, {0.4, 0.76}, {0.55, 0.7}, {0.55, 0.76}};
    // at full speed we cover 0.28 a tick and a 2 thick wall's takeoff window is 0.06 wide, so where the run started
    // decides whether we ever get a shot (a run that goes -0.41 then -0.13 never does). angling off the line for a tick
    // costs a bit of forward speed, which is how runTick lines a later tick up with the spot. degrees off the run heading
    private static final double[] PHASE = {0, 20, -20, 45, -45};
    private static final double[] NONE = {}, PENDING = {};
    private static final double[][] TABLE = new double[(MAX_RUNWAY + 1) * (MAX_DIST + 1) * (1 << (MAX_DIST - 1)) * 2][];

    /**
     * Where we're going this tick. heading is in the local frame: u down the line, v out toward the open side
     */
    record Plan(double margin, double score, int runup, double headingU, double headingV,
                int policy, double a, double b, int at, double d, boolean born, int age) {}

    /**
     * Where to stand, which sideways spot to hold on the run up, and how many ticks of running before the jump
     */
    record Staging(double u, double v, double runTarget, int runup, double takeoff) {}

    // local frame: origin at the center of the block we jump from, u along the jump, v toward the side we swing out on.
    // block (k, j) covers u in [k - 0.5, k + 0.5] and v in [j - 0.5, j + 0.5]. on the line (j = 0) we run up on k = -1,
    // take off from 0 and land on dist, bit k - 1 of walls says whether k is in the way. we fly through j = 1 from -1
    // to dist. the two blocks past the landing (j = 0 and 1) are open if openBeyond says so, everything else is a wall
    // as far as we're concerned. opening up j = 2 as well changed exactly nothing, we never get that far out
    final int dist, walls, runway;
    private final boolean openBeyond;
    private final double groundAccel;
    // what the last fly/best came out with: worst clearance, and the heading for the tick we're on
    private double margin, headingU, headingV;
    static final double RUN_GAIN = 0.6;

    NeoJump(int dist, int walls, int runway, boolean openBeyond, double groundAccel) {
        this.dist = dist;
        this.walls = walls;
        this.runway = runway;
        this.openBeyond = openBeyond;
        this.groundAccel = groundAccel;
    }

    /**
     * @return where to stand before the run up, null if no run up we know makes it. works it out right now if nobody
     * has yet, which is a few hundred ms
     */
    static Staging staging(int dist, int walls, int runway, boolean openBeyond) {
        int key = key(dist, walls, runway, openBeyond);
        if (key < 0) {
            return null;
        }
        double[] spot = TABLE[key];
        if (spot == null || spot == PENDING) {
            synchronized (TABLE) {
                spot = TABLE[key];
                if (spot == null || spot == PENDING) {
                    spot = TABLE[key] = pick(new NeoJump(dist, walls, runway, openBeyond, SPRINT_GROUND));
                }
            }
        }
        return unpack(spot);
    }

    /**
     * Same, except a shape nobody has worked out yet gets worked out in the background and we say no for now. A* asks
     * this, a 400 ms stall the first time it sees a 2 thick wall would eat the whole search. the next path gets it
     */
    static Staging stagingIfKnown(int dist, int walls, int runway, boolean openBeyond) {
        int key = key(dist, walls, runway, openBeyond);
        if (key < 0) {
            return null;
        }
        double[] spot = TABLE[key];
        if (spot == null) {
            synchronized (TABLE) {
                if (TABLE[key] == null) {
                    TABLE[key] = PENDING;
                    ForkJoinPool.commonPool().execute(() -> staging(dist, walls, runway, openBeyond));
                }
            }
            return null;
        }
        return spot == PENDING ? null : unpack(spot);
    }

    private static int key(int dist, int walls, int runway, boolean openBeyond) {
        if (dist < 2 || dist > MAX_DIST || walls <= 0 || walls >= 1 << (dist - 1) || runway < 1 || runway > MAX_RUNWAY) {
            return -1;
        }
        return ((runway * (MAX_DIST + 1) + dist) * (1 << (MAX_DIST - 1)) + walls) * 2 + (openBeyond ? 1 : 0);
    }

    private static Staging unpack(double[] spot) {
        return spot == NONE ? null : new Staging(spot[0], spot[1], spot[2], (int) spot[3], spot[4]);
    }

    private static double[] pick(NeoJump neo) {
        double[] spot = NONE;
        double best = Double.NEGATIVE_INFINITY;
        for (double run : RUN) {
            double takeoff = neo.takeoff(run);
            if (Double.isNaN(takeoff)) {
                continue;
            }
            for (double[] candidate : STAGING) {
                double u = candidate[0] - neo.runway - 0.5;
                double margin = neo.rollout(u, candidate[1], run, takeoff);
                if (margin >= PLAN_MARGIN && margin > best) {
                    best = margin;
                    spot = new double[]{u, candidate[1], run, neo.rolloutRunup, takeoff};
                }
            }
        }
        return spot;
    }

    /**
     * The best spot along the line to jump from at full speed holding v = run, NaN if there isn't one
     */
    double takeoff(double run) {
        // full sprint on the ground settles at v = a * 0.546 / (1 - 0.546), 0.153 for plain sprinting
        double full = groundAccel * GROUND_FRICTION / (1 - GROUND_FRICTION);
        double spot = Double.NaN, best = 0;
        for (double u = EARLIEST; u <= 0.3; u += 0.02) {
            Plan plan = plan(u, run, full, 0, 0, 0, true, 0, null);
            if (plan != null && plan.margin() > best) {
                best = plan.margin();
                spot = u;
            }
        }
        return spot;
    }

    /**
     * Heading for one tick of run up (into headingU/V): hold v at run, and angle off a bit if that makes some later tick
     * land closer to the takeoff spot
     */
    double[] runTick(double u, double v, double vu, double vv, double run, double takeoff) {
        double base = Math.asin(side(v, vv, run, RUN_GAIN, groundAccel));
        double bestError = Double.POSITIVE_INFINITY, bestC = Math.cos(base), bestS = Math.sin(base);
        for (double off : PHASE) {
            double c = Math.cos(base + Math.toRadians(off)), s = Math.sin(base + Math.toRadians(off));
            pu = u;
            pv = v;
            pvu = vu;
            pvv = vv;
            margin = CAP;
            if (!run(c, s)) {
                continue;
            }
            // then run straight and see how close the closest tick gets
            double error = Double.POSITIVE_INFINITY;
            for (int k = 0; k < 20 && pu < takeoff + 0.3; k++) {
                error = Math.min(error, Math.abs(pu - takeoff));
                double hold = side(pv, pvv, run, RUN_GAIN, groundAccel);
                if (!run(Math.sqrt(1 - hold * hold), hold)) {
                    break;
                }
            }
            // going straight is free, the others cost a little so we don't wiggle for a hundredth
            error += 0.003 * Math.abs(off) / 45;
            if (error < bestError) {
                bestError = error;
                bestC = c;
                bestS = s;
            }
        }
        headingU = bestC;
        headingV = bestS;
        return new double[]{bestC, bestS};
    }

    private int rolloutRunup;
    // no jump from further back than this lands even dist 2, so there's no point asking. it's most of the run up
    static final double EARLIEST = -1.5;

    /**
     * Play the whole thing out the way MovementNeo will: stand still at (u, v), run holding run, jump when plan says
     * jumping now beats jumping next tick, replan every tick in the air. the planner's own score is for one fixed
     * schedule, and replanning every tick does a lot better than any one schedule (a 2 thick wall goes from 0.028 to
     * whatever this says), so this is what decides if A* gets to use the shape
     *
     * @return the worst clearance along the way, NaN if it doesn't make it
     */
    double rollout(double u, double v, double run, double takeoff) {
        double ru = u, rv = v, rvu = 0, rvv = 0, ry = 0, rvy = 0, worst = CAP;
        boolean ground = true;
        Plan plan = null;
        rolloutRunup = 0;
        for (int tick = 0; tick < 4 * MAX_RUNWAY + 30; tick++) {
            plan = !ground ? plan(ru, rv, rvu, rvv, ry, rvy, false, 0, plan) : ru > EARLIEST ? plan(ru, rv, rvu, rvv, 0, 0, true, 1, null) : null;
            double c, s;
            boolean jump = false;
            if (plan != null && (!ground || plan.margin() >= JUMP_MARGIN)) {
                c = plan.headingU();
                s = plan.headingV();
                jump = ground && plan.runup() == 0;
            } else if (ground && ru < 0.2) {
                // nothing a tick out lands it yet, so keep running and line up with the takeoff spot
                runTick(ru, rv, rvu, rvv, run, takeoff);
                c = headingU;
                s = headingV;
            } else {
                return Double.NaN;
            }
            pu = ru;
            pv = rv;
            pvu = rvu;
            pvv = rvv;
            py = ry;
            pvy = rvy;
            margin = worst;
            if (ground && !jump) {
                if (!run(c, s)) {
                    return Double.NaN;
                }
                rolloutRunup++;
            } else {
                int result = air(c, s, ground);
                if (result == FAILED) {
                    return Double.NaN;
                }
                if (result == LANDED) {
                    return margin;
                }
                ground = false;
            }
            worst = margin;
            ru = pu;
            rv = pv;
            rvu = pvu;
            rvv = pvv;
            ry = py;
            rvy = pvy;
        }
        return Double.NaN;
    }

    /**
     * Best way to go from here, jumping now or (on the ground) after at most maxRunup ticks of running. null if nothing
     * we know how to do lands it. in the air, pass last tick's plan: it gets carried on one tick and kept unless
     * something beats it. without that, replanning kept trading the curve we jumped on for whatever was in the grid
     * from here, which was worse every time (0.025 promised at takeoff, 0.002 delivered)
     */
    Plan plan(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int maxRunup, Plan previous) {
        Plan best = null;
        for (int runup = 0; runup <= (onGround ? maxRunup : 0); runup++) {
            for (int run = 0; run < (runup > 0 ? RUN.length : 1); run++) {
                double score = best(u, v, vu, vv, y, vy, onGround, runup, RUN[run]);
                if (!Double.isNaN(score) && (best == null || score > best.score())) {
                    if (runup > 0) {
                        double side = side(v, vv, RUN[run], RUN_GAIN, groundAccel);
                        best = new Plan(margin, score, runup, Math.sqrt(1 - side * side), side, 0, 0, 0, 0, 0, false, 0);
                    } else {
                        best = new Plan(margin, score, 0, headingU, headingV, bestPolicy, bestA, bestB, bestAt, bestD, onGround, 0);
                    }
                }
            }
        }
        if (!onGround && previous != null && previous.runup() == 0) {
            int age = previous.age() + 1;
            double score = fly(u, v, vu, vv, y, vy, false, 0, 0, previous.policy(), previous.a(), previous.b(), previous.at(), previous.d(), previous.born(), age);
            if (!Double.isNaN(score) && (best == null || score >= best.score())) {
                best = new Plan(margin, score, 0, headingU, headingV, previous.policy(), previous.a(), previous.b(), previous.at(), previous.d(), previous.born(), age);
            }
        }
        return best;
    }

    private double best(double u, double v, double vu, double vv, double y, double vy, boolean onGround, int runup, double run) {
        best = Double.NaN;
        for (double hold : HOLD) {
            for (double gain : GAIN) {
                // switching at 0 never holds, so one hold value is plenty for that
                for (int at = hold == HOLD[0] ? 0 : 1; at <= MAX_SWITCH; at++) {
                    for (double settle : SETTLE) {
                        keep(fly(u, v, vu, vv, y, vy, onGround, runup, run, HOLD_POLICY, hold, gain, at, settle, onGround, 0), HOLD_POLICY, hold, gain, at, settle);
                    }
                }
            }
        }
        // in the air the jump angle doesn't exist, so one of those is plenty
        for (int jump = 0; jump < (onGround ? CURVE_JUMP.length : 1); jump++) {
            for (double start : CURVE_START) {
                for (double rate : CURVE_RATE) {
                    for (int bend = 0; bend < CURVE_BEND.length; bend++) {
                        // bend rides in the int slot hold uses for its switch tick, it's an index
                        keep(fly(u, v, vu, vv, y, vy, onGround, runup, run, CURVE_POLICY, CURVE_JUMP[jump], start, bend, rate, onGround, 0), CURVE_POLICY, CURVE_JUMP[jump], start, bend, rate);
                    }
                }
            }
        }
        margin = bestMargin;
        headingU = bestU;
        headingV = bestV;
        return best;
    }

    private double best, bestMargin, bestU, bestV, bestA, bestB, bestD;
    private int bestPolicy, bestAt;

    private void keep(double score, int policy, double a, double b, int at, double d) {
        if (!Double.isNaN(score) && (Double.isNaN(best) || score > best)) {
            best = score;
            bestMargin = margin;
            bestU = headingU;
            bestV = headingV;
            bestPolicy = policy;
            bestA = a;
            bestB = b;
            bestAt = at;
            bestD = d;
        }
    }

    /**
     * Sideways part of the heading that steers v toward target. asks for a sideways speed that shrinks with the offset
     * instead of aiming at the spot, same reason as SprintJump.steer: aiming ignores the drift we already have
     */
    static double side(double v, double vv, double target, double gain, double accel) {
        double want = Math.max(-0.15, Math.min(0.15, gain * (target - v)));
        return Math.max(-1, Math.min(1, (want - vv) / accel));
    }

    /**
     * @return how good this went, NaN if we hit something, fell, or came down anywhere but the landing block
     */
    private double fly(double u, double v, double vu, double vv, double y, double vy, boolean onGround,
                       int runup, double run, int policy, double a, double b, int switchAt, double d, boolean born, int age) {
        margin = CAP;
        pu = u;
        pv = v;
        pvu = vu;
        pvv = vv;
        py = y;
        pvy = vy;
        for (int i = 0; i < runup; i++) {
            double runS = side(pv, pvv, run, RUN_GAIN, groundAccel);
            if (!run(Math.sqrt(1 - runS * runS), runS)) {
                return Double.NaN;
            }
        }
        boolean jumpTick = onGround;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            // age is how many ticks ago this schedule started, born is whether it started with the jump
            int now = tick + age;
            double c, s;
            if (policy == HOLD_POLICY) {
                // the jump boost goes the way we face too, so on the jump tick it counts as acceleration
                s = side(pv, pvv, now < switchAt ? a : d, b, jumpTick ? BOOST + groundAccel : AIR_ACCEL);
                c = Math.sqrt(1 - s * s);
            } else {
                // facing backwards with W held is braking, and sprint doesn't care which way we face
                int t = born ? now - 1 : now;
                double degrees = Math.max(-150, born && now == 0 ? a : b - d * t - CURVE_BEND[switchAt] * t * t);
                c = Math.cos(Math.toRadians(degrees));
                s = Math.sin(Math.toRadians(degrees));
            }
            if (tick == 0) {
                headingU = c;
                headingV = s;
            }
            int result = air(c, s, jumpTick);
            if (result == FAILED) {
                return Double.NaN;
            }
            if (result == LANDED) {
                // margin first. the slide and the run up only break ties, they used to be big enough to pick a sloppy
                // jump next tick over a clean one now, and then next tick never came
                return margin - 0.005 * slide - 0.001 * runup;
            }
            jumpTick = false;
        }
        return Double.NaN;
    }

    // where the sim is right now, so fly and rollout can share the same tick of physics
    private double pu, pv, pvu, pvv, py, pvy, slide;
    private static final int FLYING = 0, LANDED = 1, FAILED = 2;

    /**
     * One tick on the ground holding W toward (c, s). false if we hit something or ran off the edge
     */
    private boolean run(double c, double s) {
        double du = pvu + groundAccel * c;
        double dv = pvv + groundAccel * s;
        if (!sweep(pu, pv, du, dv)) {
            return false;
        }
        pu += du;
        pv += dv;
        // the runway and the takeoff block are one strip of floor from -runway - 0.5 to 0.5. we fall off at 0 overlap,
        // 0.03 is for being a hair off where we think we are
        if (pu > 0.77 || pu < -runway - 0.77 || overlap(pv, 0) < 0.03) {
            return false; // walked off the edge before jumping, nice one
        }
        pvu = du * GROUND_FRICTION;
        pvv = dv * GROUND_FRICTION;
        return true;
    }

    /**
     * One tick in the air holding W toward (c, s), or the jump tick itself
     */
    private int air(double c, double s, boolean jumpTick) {
        double accel = AIR_ACCEL;
        if (jumpTick) {
            // onGround is still true on the jump tick, so it gets ground acceleration and ground friction
            pvu += BOOST * c;
            pvv += BOOST * s;
            pvy = JUMP;
            accel = groundAccel;
        }
        double du = pvu + accel * c;
        double dv = pvv + accel * s;
        // vanilla collides y first, so whether we land is decided where we are now, not where we're about to be
        py += pvy;
        boolean landed = pvy < 0 && py <= 0;
        double supportU = overlap(pu, dist), supportV = overlap(pv, 0);
        if (landed && (supportU <= 0 || supportV <= 0)) {
            return FAILED; // nothing under us, or it's the block we started on
        }
        if (!sweep(pu, pv, du, dv)) {
            return FAILED;
        }
        pu += du;
        pv += dv;
        if (landed) {
            // where we'd slide to with W let go (v / (1 - 0.546)). coming in from the side usually leaves our feet
            // hanging off the edge of the landing block, which is fine as long as the slide doesn't take us off it
            double slideU = pu + du * AIR_FRICTION / (1 - GROUND_FRICTION);
            double slideV = pv + dv * AIR_FRICTION / (1 - GROUND_FRICTION);
            margin = Math.min(margin, Math.min(Math.min(supportU, supportV), Math.min(overlap(slideU, dist), overlap(slideV, 0))));
            slide = Math.sqrt((slideU - dist) * (slideU - dist) + slideV * slideV);
            return margin > 0 ? LANDED : FAILED;
        }
        pvu = du * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
        pvv = dv * (jumpTick ? GROUND_FRICTION : AIR_FRICTION);
        pvy = (pvy - GRAVITY) * 0.98;
        return FLYING;
    }

    /**
     * How much a box centered at c overlaps the block centered at k on one axis
     */
    private static double overlap(double c, int k) {
        return Math.min(c + HALF, k + 0.5) - Math.max(c - HALF, k - 0.5);
    }

    /**
     * vanilla moves y, then whichever of x/z is bigger, then the other (Direction.axisStepOrder). so a corner can get
     * clipped halfway through a tick even when both ends are clear, and it's the order that decides which corner
     */
    private boolean sweep(double u, double v, double du, double dv) {
        double half = Math.abs(du) > Math.abs(dv) ? clearance(u + du, v)
                : Math.abs(du) < Math.abs(dv) ? clearance(u, v + dv)
                : Math.min(clearance(u + du, v), clearance(u, v + dv)); // a tie goes x first, and we don't know which one u is
        margin = Math.min(margin, Math.min(half, clearance(u + du, v + dv)));
        return margin > 0;
    }

    private double clearance(double u, double v) {
        double min = CAP;
        int ku = (int) Math.floor(u + 0.5);
        int kv = (int) Math.floor(v + 0.5);
        // a box 0.3 from its center can't get within 0.2 of anything further than one block over
        for (int k = ku - 1; k <= ku + 1; k++) {
            for (int j = kv - 1; j <= kv + 1; j++) {
                if (blocked(k, j)) {
                    min = Math.min(min, Math.max(Math.max(k - 0.5 - u - HALF, u - HALF - k - 0.5), Math.max(j - 0.5 - v - HALF, v - HALF - j - 0.5)));
                }
            }
        }
        return min;
    }

    private boolean blocked(int k, int j) {
        if (k < -runway || k > dist + 1 || j < 0 || j > 1) {
            return true;
        }
        if (k == dist + 1) {
            return !openBeyond;
        }
        return j == 0 && k > 0 && k < dist && (walls & 1 << (k - 1)) != 0;
    }
}
