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

import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;

// Tungsten's idea (github.com/Hackerokuz/Tungsten, after KaptainWutax): forget blocks, search over the keys themselves. every
// node is the player one tick on, every edge is one tick of keys, and the physics is the whole truth of where that lands
// you. A* over that finds jumps no grid of block moves has a name for.
//
// what's kept from it: the per tick key search with a physics sim as the move generator, a closed set over a rounded state,
// a heuristic in ticks off the straight line distance. what's different: the physics is Soprano's PlayerSim (26.3, and it
// refuses terrain it can't do instead of guessing), the sim is one cursor that states are loaded into (no copy of the player
// per node), the ground keys are 19 yaws of run or jump instead of 512 key combinations, the air gets real steering (Tungsten
// held W at whatever yaw it took off with), the velocity is part of what makes two states the same, falls that hurt are cut,
// and the goal is standing still on the landing (rolled out by JumpPolicy's settle), not a point in the air near it
public final class TungstenSearch {

    // the plan: keys[i] is pressed on tick i, after which the player should be at states[i + 1] (states[0] is the start)
    public record Plan(List<PlayerSim.Controls> keys, List<SimState> states, int nodes, long simTicks) {
        public int ticks() {
            return keys.size();
        }
    }

    // where the search may go. outside the box, or below floorY, a branch is dropped
    public record Region(double minX, double minZ, double maxX, double maxZ, double floorY) {
        public static Region around(double x0, double z0, double x1, double z1, double floorY, double margin) {
            return new Region(Math.min(x0, x1) - margin, Math.min(z0, z1) - margin, Math.max(x0, x1) + margin, Math.max(z0, z1) + margin, floorY);
        }

        boolean contains(SimState s) {
            return s.x >= minX && s.x <= maxX && s.z >= minZ && s.z <= maxZ && s.y >= floorY;
        }
    }

    // a sprint jump chain tops out around 0.36 blocks a tick on the flat, nothing in here goes faster, so distance / this is
    // never more ticks than it really takes
    private static final double TOP_SPEED = 0.36;
    // weighted A*: a bit greedy, which is the difference between tens of milliseconds and seconds, for plans a few ticks long
    private static final double GREED = 1.6;

    public static boolean STRAFE = false;
    private static final float[] AIR_SPREAD = {0F, 15F, -15F, 40F, -40F, 90F, -90F};
    // from a node on the ground this close to the landing, try JumpPolicy straight to the end before expanding it tick by tick.
    // one rollout of the rule is cheaper than the two or three levels of the tree it takes the search to find the same jump,
    // and it turns the search into "get somewhere the rule works from", which is most of what it's for
    private static final double SHORTCUT_REACH = 6.5;

    private static final class Node implements Comparable<Node> {
        final SimState s;
        final Node parent;
        final PlayerSim.Controls keys;
        final int g;
        final double f;
        // highest feet y since we left the ground (only meaningful in the air)
        final double peakY;

        Node(SimState s, Node parent, PlayerSim.Controls keys, int g, double h, double peakY) {
            this.s = s;
            this.parent = parent;
            this.keys = keys;
            this.g = g;
            this.f = g + GREED * h;
            this.peakY = peakY;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private TungstenSearch() {}

    // search from start's state to standing still on the landing. budget is sim ticks (a few microseconds each). cancelled is
    // checked every so often, null for never. null if nothing turned up in the budget
    public static Plan search(PlayerSim start, Landing land, Region region, long budget, BooleanSupplier cancelled) {
        return search(start, land, region, budget, cancelled, start.y);
    }

    // the same, from a start that may be in the air already: startPeakY is how high this flight got before the search took it
    // over, so the fall at the end is measured from there
    public static Plan search(PlayerSim start, Landing land, Region region, long budget, BooleanSupplier cancelled, double startPeakY) {
        return search(start, land, region, budget, cancelled, startPeakY, null);
    }

    // via: a point (x z) the way to the landing goes through, round something in the way (a neo: beside the end of the wall).
    // the heuristic goes by way of it until we're level with it, so the search doesn't spend its budget pushing at the
    // wall the straight line goes through
    public static Plan search(PlayerSim start, Landing land, Region region, long budget, BooleanSupplier cancelled, double startPeakY,
                              double[] via) {
        return search(start, land, region, budget, cancelled, startPeakY, via, false);
    }

    // walkOnly: no jump keys at all, run or walk only. round a walled platform along its rim, along a ledge: a walk where
    // one goes is steadier than any jump round the same thing, so the neo searches ask for one of these first
    public static Plan search(PlayerSim start, Landing land, Region region, long budget, BooleanSupplier cancelled, double startPeakY,
                              double[] via, boolean walkOnly) {
        Heuristic h = via == null ? new Heuristic(land, Double.NaN, Double.NaN, 0, 0, 0) : Heuristic.via(start, land, via[0], via[1]);
        PlayerSim cursor = start.copy();
        PlayerSim probe = start.copy();
        long before = PlayerSim.ticks();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Set<Long> closed = new HashSet<>();
        SimState s0 = SimState.of(start);
        open.add(new Node(s0, null, null, 0, h.of(s0), s0.onGround ? s0.y : Math.max(startPeakY, s0.y)));
        int nodes = 0;
        List<PlayerSim.Controls> options = new ArrayList<>(48);
        while (!open.isEmpty()) {
            if (PlayerSim.ticks() - before > budget || (nodes & 255) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                return null;
            }
            Node n = open.poll();
            if (!closed.add(key(n.s))) {
                continue;
            }
            nodes++;
            if (!walkOnly && (n.s.onGround || n.parent == null) && land.horizontalDistance(n.s.x, n.s.z) <= SHORTCUT_REACH) {
                n.s.write(cursor);
                JumpPolicy.Rollout r = JumpPolicy.bestFrom(cursor, land, n.peakY);
                if (r != null) {
                    return plan(n, r, nodes, PlayerSim.ticks() - before);
                }
            }
            options.clear();
            if (walkOnly) {
                generateWalk(n.s, land, options);
            } else {
                generate(n.s, land, options);
            }
            for (PlayerSim.Controls c : options) {
                n.s.write(cursor);
                if (!Keys.step(cursor, c)) {
                    continue; // terrain the sim won't do (fluids, ladders, ice, hazards): not ours to plan through
                }
                SimState s = SimState.of(cursor);
                if (!region.contains(s)) {
                    continue;
                }
                double peak = s.onGround ? s.y : n.s.onGround ? Math.max(n.s.y, s.y) : Math.max(n.peakY, s.y);
                if (s.onGround && !n.s.onGround && n.peakY - s.y > JumpPolicy.safeFall()) {
                    continue; // that one hurts
                }
                if (land.grab() ? land.gripping(s) : s.onGround && JumpPolicy.onTheBlock(s, land)) {
                    // down on the landing: does it stand still there? the same settle the rule does
                    List<PlayerSim.Controls> settle = settle(cursor, probe, land);
                    if (settle != null) {
                        return plan(n, c, s, settle, cursor, nodes, PlayerSim.ticks() - before);
                    }
                    continue;
                }
                if (closed.contains(key(s))) {
                    continue;
                }
                open.add(new Node(s, n, c, n.g + 1, h.of(s), peak));
            }
        }
        return null;
    }

    // ground: run or jump at every 22.5 degrees, plus straight at the landing and a few degrees either side of it (the yaw
    // that lines a jump up is rarely a round number), plus braking and letting go. air: W at a spread of yaws round the
    // landing and back against the way we're going, or nothing
    // on the ground: run or walk (W, no sprint: slower, for the narrow bits) every 22.5 degrees and straight at the landing,
    // braking, letting go. never a jump. in the air (stepped off something) the usual steering
    private static void generateWalk(SimState s, Landing land, List<PlayerSim.Controls> out) {
        if (!s.onGround) {
            generate(s, land, out);
            return;
        }
        float toward = Keys.yaw(land.centerX() - s.x, land.centerZ() - s.z);
        for (int i = 0; i < 16; i++) {
            float yaw = Mth.wrapDegrees(i * 22.5F);
            out.add(Keys.run(yaw, false));
            out.add(Keys.push(yaw, false));
        }
        for (float d : new float[]{0F, 7F, -7F}) {
            out.add(Keys.run(toward + d, false));
            out.add(Keys.push(toward + d, false));
        }
        if (s.horizontalSpeed() > 1.0E-3) {
            out.add(Keys.push(Keys.yaw(-s.vx, -s.vz), false));
        }
        out.add(Keys.release(toward));
    }

    private static void generate(SimState s, Landing land, List<PlayerSim.Controls> out) {
        float toward = Keys.yaw(land.centerX() - s.x, land.centerZ() - s.z);
        if (s.onGround) {
            for (int i = 0; i < 16; i++) {
                float yaw = Mth.wrapDegrees(i * 22.5F);
                out.add(Keys.run(yaw, false));
                out.add(Keys.run(yaw, true));
            }
            for (float d : new float[]{0F, 7F, -7F}) {
                out.add(Keys.run(toward + d, false));
                out.add(Keys.run(toward + d, true));
            }
            if (STRAFE) {
                // W and A or D together, facing 45 degrees off the way we want to go: the input is squared off to a full
                // 1 instead of W's 0.98, two percent more push (the 45 strafe). the jump's own push goes the way we face
                for (float d : new float[]{0F, 22.5F, -22.5F, 45F, -45F}) {
                    for (int side : new int[]{1, -1}) {
                        out.add(Keys.strafe(toward + d, side, false));
                        out.add(Keys.strafe(toward + d, side, true));
                    }
                }
            }
            if (s.horizontalSpeed() > 1.0E-3) {
                out.add(Keys.push(Keys.yaw(-s.vx, -s.vz), false));
            }
            out.add(Keys.release(toward));
        } else {
            for (float d : AIR_SPREAD) {
                out.add(Keys.run(toward + d, false));
                if (STRAFE) {
                    out.add(Keys.strafe(toward + d, 1, false));
                    out.add(Keys.strafe(toward + d, -1, false));
                }
            }
            if (s.horizontalSpeed() > 1.0E-3) {
                out.add(Keys.run(Keys.yaw(-s.vx, -s.vz), false));
            }
            out.add(Keys.release(toward));
        }
    }

    // JumpPolicy's settle from where the cursor is, keys or null
    static List<PlayerSim.Controls> settle(PlayerSim cursor, PlayerSim probe, Landing land) {
        PlayerSim sim = cursor;
        SimState saved = SimState.of(cursor);
        JumpPolicy.Memo memo = new JumpPolicy.Memo();
        memo.phase = land.grab() ? JumpPolicy.Phase.GRIP : JumpPolicy.Phase.SETTLE; // on a ladder: climb or slide to its cell
        List<PlayerSim.Controls> keys = new ArrayList<>();
        try {
            for (int i = 0; i <= Math.max(JumpPolicy.MAX_SETTLE, JumpPolicy.MAX_GRIP) + 1; i++) {
                PlayerSim.Controls c = JumpPolicy.decide(sim, probe, land, JumpPolicy.Variant.EDGE, memo);
                if (memo.phase == JumpPolicy.Phase.DONE) {
                    return keys;
                }
                if (memo.phase == JumpPolicy.Phase.FAILED || !Keys.step(sim, c)) {
                    return null;
                }
                keys.add(c);
            }
            return null;
        } finally {
            saved.write(cursor);
        }
    }

    // the way to n, then the rule's rollout from there
    private static Plan plan(Node n, JumpPolicy.Rollout r, int nodes, long ticks) {
        List<PlayerSim.Controls> keys = new ArrayList<>();
        List<SimState> states = new ArrayList<>();
        for (Node at = n; at.parent != null; at = at.parent) {
            keys.add(at.keys);
            states.add(at.s);
        }
        Collections.reverse(keys);
        Collections.reverse(states);
        states.add(0, root(n).s);
        keys.addAll(r.keys());
        states.addAll(r.states().subList(1, r.states().size()));
        return new Plan(keys, states, nodes, ticks);
    }

    private static Node root(Node n) {
        while (n.parent != null) {
            n = n.parent;
        }
        return n;
    }

    private static Plan plan(Node n, PlayerSim.Controls last, SimState landed, List<PlayerSim.Controls> settle, PlayerSim cursor,
                             int nodes, long ticks) {
        List<PlayerSim.Controls> keys = new ArrayList<>();
        List<SimState> states = new ArrayList<>();
        keys.add(last);
        states.add(landed);
        for (Node at = n; at.parent != null; at = at.parent) {
            keys.add(at.keys);
            states.add(at.s);
        }
        Node root = n;
        while (root.parent != null) {
            root = root.parent;
        }
        states.add(root.s);
        Collections.reverse(keys);
        Collections.reverse(states);
        // the settle, replayed so every tick has the state it should leave
        landed.write(cursor);
        for (PlayerSim.Controls c : settle) {
            Keys.step(cursor, c);
            keys.add(c);
            states.add(SimState.of(cursor));
        }
        return new Plan(keys, states, nodes, ticks);
    }

    private record Heuristic(Landing land, double viaX, double viaZ, double ux, double uz, double viaAlong) {
        static Heuristic via(PlayerSim start, Landing land, double x, double z) {
            double dx = land.centerX() - start.x, dz = land.centerZ() - start.z, len = Math.sqrt(dx * dx + dz * dz);
            double ux = len < 1.0E-6 ? 0 : dx / len, uz = len < 1.0E-6 ? 0 : dz / len;
            return new Heuristic(land, x, z, ux, uz, x * ux + z * uz);
        }

        double of(SimState s) {
            if (!Double.isNaN(viaX) && s.x * ux + s.z * uz < viaAlong) {
                double ax = viaX - s.x, az = viaZ - s.z, bx = land.centerX() - viaX, bz = land.centerZ() - viaZ;
                return (Math.sqrt(ax * ax + az * az) + Math.sqrt(bx * bx + bz * bz)) / TOP_SPEED;
            }
            return land.horizontalDistance(s.x, s.z) / TOP_SPEED;
        }
    }

    // two states are the same if they round to the same place and speed: a sixteenth of a block, a fiftieth of a block a tick
    // sideways, a twenty fifth up and down
    private static long key(SimState s) {
        long h = Math.round(s.x * 16);
        h = h * 31 + Math.round(s.y * 16);
        h = h * 31 + Math.round(s.z * 16);
        h = h * 31 + Math.round(s.vx * 50);
        h = h * 31 + Math.round(s.vz * 50);
        h = h * 31 + Math.round(s.vy * 25);
        h = h * 4 + (s.onGround ? 2 : 0) + (s.sprinting ? 1 : 0);
        return h;
    }
}
