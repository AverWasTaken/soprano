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

    private HopChain() {}

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
        for (int back : BACKS) {
            for (int run = -1; run <= MAX_RUN; run++) {
                for (double side : SIDES) {
                    double ax = land.centerX() + px * side, az = land.centerZ() + pz * side;
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
    private static boolean offTheEdge(PlayerSim sim, float yaw) {
        PlayerSim next = sim.copy();
        return Keys.step(next, Keys.run(yaw, false)) && !next.onGround;
    }

    private static TungstenSearch.Plan fly(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, int back, int run, double ax,
                                           double az, float aimError) {
        PlayerSim sim = NeoSearches.standing(world, src, floorY);
        PlayerSim probe = sim.copy();
        List<PlayerSim.Controls> keys = new ArrayList<>();
        List<SimState> states = new ArrayList<>();
        states.add(SimState.of(sim));
        for (int b = 0; b < back; b++) {
            PlayerSim.Controls c = new PlayerSim.Controls(-1, 0, false, false, Keys.yaw(ax - sim.x, az - sim.z) + aimError);
            PlayerSim next = sim.copy();
            if (!Keys.step(next, c) || !next.onGround || Math.abs(next.y - floorY) > 1.0E-6) {
                break; // (as far back as there's floor)
            }
            Keys.step(sim, c);
            keys.add(c);
            states.add(SimState.of(sim));
        }
        double low = Math.min(floorY, land.floor()) - 1.0;
        boolean airborne = false;
        int hops = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            float yaw = Keys.yaw(ax - sim.x, az - sim.z) + aimError;
            boolean jump = sim.onGround && (run >= 0 ? t >= run : airborne || hops > 0 || offTheEdge(sim, yaw));
            PlayerSim.Controls c = Keys.run(yaw, jump);
            if (!Keys.step(sim, c)) {
                return null;
            }
            keys.add(c);
            states.add(SimState.of(sim));
            if (!sim.onGround) {
                airborne = true;
                if (sim.y < low) {
                    return null;
                }
                continue;
            }
            if (!airborne) {
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
