/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.tungsten;

import baritone.pathing.sim.CollisionProvider;
import baritone.pathing.sim.PlayerSim;

/**
 * A neo off a run of bunny hops, never stopping: the triple neo from right behind the wall, which no standing start or plain
 * run up gets round. How a player does one:
 * <ul>
 * <li>hop along the runway out towards its open side edge, every touchdown straight back up: a hop carries more speed than
 * running does (the jump's push on top of what the last flight kept);</li>
 * <li>time the hops so the last one comes down right at the end of the runway, the box just out past the wall's side, and
 * jump straight off the very edge ("hold jump on the edge");</li>
 * <li>in the air, keep pressed into the side of the wall (sliding along it costs nothing, and it holds the line exactly on
 * the wall's edge), and once past its end, turn in onto the block behind it.</li>
 * </ul>
 * The margins are thin (a few tenths of a block of reach, the side line to a few hundredths), so:
 * <ul>
 * <li>where on the runway to start from, and how many ticks to run before the first hop, are worked out beforehand
 * ({@link #plan}): that's what puts the last touchdown at the edge;</li>
 * <li>and it's played closed loop: every tick the rest of the run is rolled out in the sim for a handful of turn outs (where
 * the last hop swings out over the wall's side line, and how hard) and the best of them is pressed.</li>
 * </ul>
 * The frame: u along the run from the middle of the takeoff block (the last block of the runway, the wall's face at
 * u = 0.5), v sideways towards the open side (the wall's side face at v = 0.5, the runway's side edge too).
 */
public final class RunNeo {

    final CollisionProvider world;
    final double ox, oz, floorY;
    final int dx, dz, sx, sz;
    // where the wall ends (its far face), in u
    final double endU;
    // the lane the hops go along before the turn out
    static final double LANE = 0.68;
    // the line the box has to be past to clear the wall's side (and on the runway, inside it to still be stood on it)
    static final double CLEAR = 0.8;
    // the furthest on a takeoff can be: any further and the box is into the wall's face
    static final double EDGE = 0.19;
    // a takeoff past here is the last one
    static final double LAST_FROM = -0.45;

    // the choices tried each tick: how far inside the wall's side line the last touchdown starts, and how hard it turns
    // out over it on the touchdown tick
    static final double[] TURN_AT = {0.003, 0.008, 0.015, 0.025, 0.04};
    static final double[] TURN = {8, 16, 28, 45};
    // pressed into the wall along it, and the turn in past its end
    static final double HUG = 8, IN = 25;

    // the plan: where the run starts (u), and the ticks before the first hop
    public double startU = Double.NaN;
    public int waitTicks;
    // ticks since the run started
    int t;
    boolean launched;
    public String lastWhy = "";
    // what the best rollout had to spare last tick (negative: none of them lands)
    public double spare;

    public RunNeo(CollisionProvider world, int srcX, int srcZ, double floorY, int dx, int dz, int sx, int sz, int wallLength) {
        this.world = world;
        this.ox = srcX + 0.5;
        this.oz = srcZ + 0.5;
        this.floorY = floorY;
        this.dx = dx;
        this.dz = dz;
        this.sx = sx;
        this.sz = sz;
        this.endU = 0.5 + wallLength;
    }

    public double u(double x, double z) {
        return (x - ox) * dx + (z - oz) * dz;
    }

    public double v(double x, double z) {
        return (x - ox) * sx + (z - oz) * sz;
    }

    double u(PlayerSim s) {
        return u(s.x, s.z);
    }

    double v(PlayerSim s) {
        return v(s.x, s.z);
    }

    public double worldX(double u, double v) {
        return ox + dx * u + sx * v;
    }

    public double worldZ(double u, double v) {
        return oz + dz * u + sz * v;
    }

    public static double lane() {
        return LANE;
    }

    // the yaw for a heading `deg` degrees off the run, towards the open side
    public float heading(double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return Keys.yaw(dx * c + sx * s, dz * c + sz * s);
    }

    PlayerSim standingAt(double u) {
        PlayerSim s = new PlayerSim(PlayerSim.Params.vanilla(), world);
        s.x = worldX(u, LANE);
        s.z = worldZ(u, LANE);
        s.y = floorY;
        s.vy = -0.0784;
        s.onGround = true;
        return s;
    }

    /**
     * where to start from and how long to run before hopping, for a runway this many blocks long behind the takeoff
     * block: the start that lands with the most to spare. false if none does
     */
    public boolean plan(int runway) {
        double best = 0;
        for (double u0 = -runway + 0.2; u0 <= Math.min(-3.0, -runway + 5); u0 += 0.02) {
            for (int w = 0; w <= 8; w++) {
                double m = Double.NEGATIVE_INFINITY;
                PlayerSim s = standingAt(u0);
                for (double at : TURN_AT) {
                    for (double tr : TURN) {
                        m = Math.max(m, rollout(s, at, tr, 0, w));
                    }
                }
                if (m > best) {
                    best = m;
                    startU = u0;
                    waitTicks = w;
                }
            }
        }
        t = 0;
        launched = false;
        spare = best;
        return best > 0;
    }

    // ticks before the one that comes down to the floor (the one whose move would take the feet under it)
    int airTicks(PlayerSim s) {
        double y = s.y, vy = s.vy;
        for (int n = 0; n < 40; n++) {
            double ny = y + vy;
            if (ny < floorY && vy < 0) {
                return n;
            }
            y = ny;
            vy = (vy - 0.08) * 0.98;
        }
        return 40;
    }

    /**
     * the heading on the way: on the lane, and on the last hop out to just inside the wall's side line (inside by
     * `inside`) for the touchdown, then out over it on the touchdown tick itself (by `over` degrees), so the box is out
     * past the wall's side when the takeoff off the edge goes
     */
    double runDeg(PlayerSim s, double inside, double over) {
        double u = u(s), v = v(s);
        if (!s.onGround) {
            int n = airTicks(s);
            // where it comes down, near enough: the speed along, a little less each tick
            double lands = u + Math.max(0, (s.vx * dx + s.vz * dz)) * n * 0.97;
            if (lands > LAST_FROM && lands < EDGE + 0.4) {
                if (n == 0) {
                    return over;
                }
                // the sideways push a tick that puts v on the target n ticks from now: position goes up by the speed
                // and the push each tick, the speed drops to 0.91 of itself after each
                double vv = s.vx * sx + s.vz * sz, p0 = 0, g = 0, w0 = vv, wg = 0;
                for (int k = 0; k < n; k++) {
                    wg += 1;
                    p0 += w0;
                    g += wg;
                    w0 *= 0.91;
                    wg *= 0.91;
                }
                double a = (CLEAR - inside - v - p0) / g;
                double sin = Math.max(-0.6, Math.min(0.6, a / AIR_PUSH));
                return Math.toDegrees(Math.asin(sin));
            }
        }
        // back onto the lane: a few degrees per tenth of a block off it, no more than 10
        return Math.max(-10, Math.min(10, (LANE - v) * 40));
    }

    // what holding forward pushes in the air a tick, sprinting
    static final double AIR_PUSH = 0.026 * 0.98;

    // the keys before the launch: a hop on every touchdown once the wait's over (and not past the edge)
    PlayerSim.Controls runKeys(PlayerSim s, double inside, double over, int tick, int wait) {
        boolean jump = s.onGround && tick >= wait && u(s) <= EDGE;
        return new PlayerSim.Controls(1, 0, jump, true, heading(runDeg(s, inside, over)));
    }

    PlayerSim.Controls airKeys(PlayerSim s) {
        return airKeys(s, IN, 0.9, HUG);
    }

    // in the air off the edge: pressed into the wall's side until past its end, then in by `in` degrees, and if it's
    // coming down further than `brake` past the wall's end, pushing back against the speed too
    PlayerSim.Controls airKeys(PlayerSim s, double in, double brake, double hug) {
        double u = u(s);
        if (u < endU + 0.3) {
            return new PlayerSim.Controls(1, 0, false, true, heading(-hug));
        }
        int n = airTicks(s);
        double lands = u + Math.max(0, s.vx * dx + s.vz * dz) * (n + 1) * 0.95;
        if (lands > endU + brake) {
            return new PlayerSim.Controls(1, 0, false, false, heading(180 + in));
        }
        return new PlayerSim.Controls(1, 0, false, true, heading(-in));
    }

    static final double[] AIR_IN = {12, 25, 45}, AIR_BRAKE = {0.6, 0.9}, AIR_HUG = {3, 8, 15};

    /**
     * the rest of the run from s (tick of the run `tick`) with this turn out: how much there is to spare (the least of the
     * reach past the wall's end and the room either side of the last touchdown over the wall's side line), negative if
     * it doesn't land
     */
    double rollout(PlayerSim live, double turnAt, double turn, int tick, int wait) {
        return rollout(live, turnAt, turn, tick, wait, false, IN, 0.9, HUG);
    }

    double rollout(PlayerSim live, double turnAt, double turn, int tick, int wait, boolean launchedNow, double in, double brake, double hug) {
        PlayerSim s = live.copy();
        boolean launched = launchedNow;
        double lastHighU = Double.NaN, sideRoom = Double.NaN, touchRoom = Double.NaN;
        for (int i = 0; i < 150; i++) {
            double u0 = u(s), v0 = v(s);
            boolean ground0 = s.onGround;
            PlayerSim.Controls k = launched ? airKeys(s, in, brake, hug) : runKeys(s, turnAt, turn, tick + i, wait);
            if (!Keys.step(s, k)) {
                return -9;
            }
            double u = u(s), v = v(s);
            if (!launched) {
                if (s.horizontalCollision) {
                    return -6 - Math.max(0, EDGE + 0.3 - u);
                }
                if (ground0 && k.jump() && !s.onGround && u0 > LAST_FROM) {
                    // the last takeoff: over the wall's side line by the end of it, or into its face
                    if (v < CLEAR) {
                        return -5 - (CLEAR - v);
                    }
                    launched = true;
                    // (stood off the runway's side for good from a standing start, touchRoom never got set: no room)
                    sideRoom = Double.isNaN(touchRoom) ? 0 : touchRoom;
                } else if (!ground0 && s.onGround) {
                    if (u > EDGE) {
                        return -4 - (u - EDGE); // came down past the edge: the next takeoff is into the wall's face
                    }
                    // the room either side of the wall's side line on the touchdown: inside it when it started (or the floor
                    // isn't under us), out past it by the end (or the takeoff goes into the wall's face)
                    touchRoom = Math.min(CLEAR - v0, v - CLEAR);
                }
                if (s.y < floorY - 0.6) {
                    return -8; // off the runway's side
                }
                continue;
            }
            if (s.y >= floorY) {
                lastHighU = u;
            }
            if (s.onGround) {
                boolean on = Math.abs(s.y - floorY) < 1.0E-6 && u > endU && u < endU + 1.3 && Math.abs(v) < CLEAR;
                // to spare: the reach past the wall's end, the side room on the last touchdown, and the box's overlap of the
                // landing block (past its far side, past the side line)
                // (in a common unit: the side room is a few hundredths at best, the reach a few tenths)
                double reach = (lastHighU - (endU + 0.3)) * 0.1;
                double room = launchedNow ? reach : Math.min(reach, sideRoom);
                return on ? Math.min(room, Math.min(endU + 1.3 - u, CLEAR - Math.abs(v)) * 0.2) : -3;
            }
            if (s.y < floorY - 0.6) {
                return -2 - Math.max(0, endU + 0.3 - lastHighU);
            }
        }
        return -10;
    }

    /**
     * this tick's keys, from the start spot on
     */
    public PlayerSim.Controls decide(PlayerSim live) {
        if (!launched && !live.onGround && u(live) > EDGE + 0.05) {
            launched = true;
        }
        int tick = t++;
        if (launched) {
            double best = Double.NEGATIVE_INFINITY, bIn = IN, bBrake = 0.9, bHug = HUG;
            for (double in : AIR_IN) {
                for (double br : AIR_BRAKE) {
                    for (double hg : AIR_HUG) {
                        double m = rollout(live, 0, 0, tick, 0, true, in, br, hg);
                        if (m > best) {
                            best = m;
                            bIn = in;
                            bBrake = br;
                            bHug = hg;
                        }
                    }
                }
            }
            spare = best;
            lastWhy = String.format("air, hug %.0f in %.0f brake %.1f, spare %.3f", bHug, bIn, bBrake, best);
            return airKeys(live, bIn, bBrake, bHug);
        }
        double best = Double.NEGATIVE_INFINITY;
        double bTurnAt = TURN_AT[0], bTurn = TURN[0];
        for (double at : TURN_AT) {
            for (double tr : TURN) {
                double m = rollout(live, at, tr, tick, waitTicks);
                if (m > best) {
                    best = m;
                    bTurnAt = at;
                    bTurn = tr;
                }
            }
        }
        spare = best;
        lastWhy = String.format("inside %.3f over %.0f, spare %.3f", bTurnAt, bTurn, best);
        return runKeys(live, bTurnAt, bTurn, tick, waitTicks);
    }
}
