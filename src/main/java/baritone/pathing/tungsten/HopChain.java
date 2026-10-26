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

package baritone.pathing.tungsten;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.sim.CollisionProvider;
import baritone.pathing.sim.PlayerSim;

import java.util.ArrayList;
import java.util.List;

// a run at the landing that hops again the moment it touches down on the way (a pillar top between, a block's edge): the
// jump off the pad goes at full speed from wherever the first one came down, further than any jump from standing on it.
// a dam's pillar to the wool four down and seven across is two of those, and nothing else gets there. what the sim makes of
// running straight at the landing from src with W, sprint and jump held, after a run up of a few ticks
public final class HopChain {

    // the most run up ticks tried before the first jump, and the most times it may touch down on the way
    static final int MAX_RUN = 6, MAX_HOPS = 2, MAX_TICKS = 80;
    // aimed at the landing's middle, or this far to either side of it
    static final double[] SIDES = {0, 0.2, -0.2};
    // and it has to make it with the aim this many degrees off either way too (LookBehavior's wobble)
    static final float JITTER = 1.0F;
    // ticks of backing off first (S held, facing the landing), as far as the block goes
    static final int[] BACKS = {0, 6, 12};
    // the near corner aimed at: this far in from the block's sides
    static final double NEAR_INSET = 0.25;
    static final double[] NEAR_FOLLOW = {0.25, 0.12, 0.0};

    private HopChain() {}

    static final boolean LOG = System.getenv("SOPRANO_HOP_LOG") != null;
    static final String TRACE = System.getenv("SOPRANO_HOP_TRACE");

    public static TungstenSearch.Plan plan(CollisionProvider world, BetterBlockPos src, double floorY, Landing land) {
        if (land.grab() || land.water()) {
            return null;
        }
        double dx = land.centerX() - (src.x + 0.5), dz = land.centerZ() - (src.z + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 3) {
            return null;
        }
        double px = -dz / dist, pz = dx / dist;
        // (run -1: run at it all the way to the edge and jump there, however far that is: across the block next door,
        // a corner on the way, the way somebody runs at a jump from a block back)
        // (and first backing off away from it, to the far side of the block, for more run up)
        // aimed at the middle (or a little to either side), or at the near corner or edge of the block: a jump onto the
        // corner of a block a long way off diagonally is the shortest way there, and the only one that makes it
        // (Chess (hard)'s black wool after stop 4)
        List<double[]> aims = new ArrayList<>();
        for (double side : SIDES) {
            aims.add(new double[]{land.centerX() + px * side, land.centerZ() + pz * side});
        }
        aims.add(new double[]{Math.max(land.x() + NEAR_INSET, Math.min(land.x() + 1 - NEAR_INSET, src.x + 0.5)),
                Math.max(land.z() + NEAR_INSET, Math.min(land.z() + 1 - NEAR_INSET, src.z + 0.5))});
        // (and the nearest point of it from wherever we are, every tick: runs at the corner, then turns for it in the air,
        // the way a player goes for the corner of a block off at an angle)
        for (double inset : NEAR_FOLLOW) {
            aims.add(new double[]{Double.NaN, inset});
        }
        for (int back : BACKS) {
            for (int run = -1; run <= MAX_RUN; run++) {
                for (double[] aim : aims) {
                    double ax = aim[0], az = aim[1];
                    TungstenSearch.Plan p = fly(world, src, floorY, land, back, run, ax, az, 0F);
                    if (p != null && fly(world, src, floorY, land, back, run, ax, az, JITTER) != null
                            && fly(world, src, floorY, land, back, run, ax, az, -JITTER) != null) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    // running on with no jump, the next tick is off the ground
    // (and not back on it straight after: running across the corner where two blocks meet, the box drops off for a tick
    // and lands on the next block, and a jump there comes down on that block, short)
    private static boolean offTheEdge(PlayerSim sim, float yaw) {
        PlayerSim next = sim.copy();
        if (!Keys.step(next, Keys.run(yaw, false)) || next.onGround) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (!Keys.step(next, Keys.run(yaw, false))) {
                return true;
            }
            if (next.onGround && Math.abs(next.y - sim.y) < 0.6) {
                return false;
            }
        }
        return true;
    }

    private static TungstenSearch.Plan fly(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, int back, int run, double ax,
                                           double az, float aimError) {
        PlayerSim sim = NeoSearches.standing(world, src, floorY);
        PlayerSim probe = sim.copy();
        List<PlayerSim.Controls> keys = new ArrayList<>();
        List<SimState> states = new ArrayList<>();
        states.add(SimState.of(sim));
        for (int b = 0; b < back; b++) {
            PlayerSim.Controls c = new PlayerSim.Controls(-1, 0, false, false, Keys.yaw(land.centerX() - sim.x, land.centerZ() - sim.z) + aimError);
            PlayerSim next = sim.copy();
            boolean ok = Keys.step(next, c) && next.onGround && Math.abs(next.y - floorY) < 1.0E-6;
            // (and as far as it can turn round from: going back, the first ticks of the run at it carry on backwards)
            for (int i = 0; ok && i < 4; i++) {
                ok = Keys.step(next, Keys.run(Keys.yaw(land.centerX() - next.x, land.centerZ() - next.z), false)) && next.onGround;
            }
            if (!ok) {
                break; // (as far back as there's floor)
            }
            Keys.step(sim, c);
            keys.add(c);
            states.add(SimState.of(sim));
        }
        double low = Math.min(floorY, land.floor()) - 1.0;
        boolean airborne = false, jumped = false;
        int hops = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            double tx = ax, tz = az;
            if (Double.isNaN(ax)) {
                tx = Math.max(land.x() + az, Math.min(land.x() + 1 - az, sim.x));
                tz = Math.max(land.z() + az, Math.min(land.z() + 1 - az, sim.z));
            }
            float yaw = Keys.yaw(tx - sim.x, tz - sim.z) + aimError;
            boolean jump = sim.onGround && (run >= 0 ? t >= run : hops > 0 || offTheEdge(sim, yaw));
            PlayerSim.Controls c = Keys.run(yaw, jump);
            if (jump) {
                jumped = true;
            }
            if (!Keys.step(sim, c)) {
                return null;
            }
            keys.add(c);
            states.add(SimState.of(sim));
            if (TRACE != null && TRACE.equals(back + " " + run + " " + (Double.isNaN(ax) ? "f" + az : "") ) && aimError == 0F) {
                System.out.printf("[hop chain] t%d %.3f %.3f %.3f %s%s%n", t, sim.x, sim.y, sim.z, sim.onGround ? "g" : "a", jump ? " jump" : "");
            }
            if (!sim.onGround) {
                airborne = true;
                if (sim.y < low) {
                    if (LOG) {
                        System.out.printf("[hop chain] back %d run %d aim %.2f %.2f: fell at %.3f %.3f %.3f after %d hops%n", back, run, ax, az, sim.x, sim.y, sim.z, hops);
                    }
                    return null;
                }
                continue;
            }
            if (!airborne || !jumped) {
                // (back down without a jump: the run across a corner where two blocks meet, it carries on)
                airborne = false;
                if (run >= 0 && t >= run + 1 || run < 0 && t > MAX_TICKS / 2) {
                    return null; // (held jump on the ground and didn't go up: a ceiling. or ran and ran and never got to an edge)
                }
                continue;
            }
            airborne = false;
            if (JumpPolicy.onTheBlock(SimState.of(sim), land) && Math.abs(sim.y - land.floor()) < 0.01) {
                List<PlayerSim.Controls> settle = TungstenSearch.settle(sim, probe, land);
                if (settle == null) {
                    return null;
                }
                for (PlayerSim.Controls s : settle) {
                    Keys.step(sim, s);
                    keys.add(s);
                    states.add(SimState.of(sim));
                }
                // (with no hop and no run up it's any plain jump's)
                return hops == 0 && run >= 0 ? null : new TungstenSearch.Plan(keys, states, 0, 0);
            }
            if (++hops > MAX_HOPS) {
                return null;
            }
        }
        return null;
    }
}
