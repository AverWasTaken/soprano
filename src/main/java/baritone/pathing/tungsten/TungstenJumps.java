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

import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.PendingTables;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import baritone.pathing.sim.BoxList;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the planner's side of Tungsten jumps: from a node on the edge of a drop, which blocks in any direction (not just the four
// the grid moves know) the jump rule can land on, and what it costs. A* asks once per node and direction bucket, so the work
// for a node is done on the first ask and kept for the other fifteen.
//
// a sim per candidate is a few hundred microseconds where a grid move is a few hundred nanoseconds, so this is careful about
// what it even tries: only nodes with a drop right next to them, only landings past that drop (the line out has to leave
// over the hole, or the jump really starts from some other node), only as far as the rule has ever reached at that height,
// and one landing per bucket (the nearest that works). and a budget of sim ticks per search, after which it stops offering
public final class TungstenJumps {

    // 8 directions (45 degrees each, east first, counter clockwise in x z) times near and far
    public static final int SECTORS = 8, RINGS = 2, BUCKETS = SECTORS * RINGS;
    private static final double RING_SPLIT = 3.6;
    // furthest the rule has landed from a standing start on a single block, middle to middle, by dy (see ReachProbe), with a
    // little to spare
    static final double[] REACH = {4.9 /* -3 */, 3.5 /* -2 */, 6.3 /* -1 */, 6.2 /* 0 */, 4.7 /* +1 */};
    static final int MAX_UP = 1, MAX_DOWN = 3, RANGE = 9;
    // with something bouncy (slime) under the way across, a jump comes down on it and goes up again: further than any
    // standing start, never higher than it started (a ladder is caught on the way up, that can be). how far the rule has
    // made one of those go, middle to middle
    static final double BOUNCE_REACH = 9.5;
    // how far under the line to look for it
    static final int BOUNCE_DEPTH = 10;

    // what a bucket offers: a landing and what getting there costs
    // plan is the full search's answer when the rule had none (a ladder neo), null otherwise
    public record Jump(BetterBlockPos src, BetterBlockPos dest, double cost, JumpPolicy.Variant variant, List<SimState> trace,
                       TungstenSearch.Plan plan) {}

    private static final Jump NONE = new Jump(null, null, ActionCosts.COST_INF, null, List.of(), null);
    // no answer yet, it's being worked out in the background (NeoSearches): not kept, the next ask may have it
    private static final Jump PENDING = new Jump(null, null, ActionCosts.COST_INF, null, List.of(), null);
    // the full search's budget for a ladder neo the rule can't do, in sim ticks. the 1 thick wall with a runway behind it is
    // a couple of thousand
    static final long NEO_SEARCH_BUDGET = 40_000;
    static final double NEO_COST = 1.5, COMBO_COST = 3.0;
    static final boolean DEBUG_SIM = System.getenv("TUNGSTEN_SIM_DEBUG") != null;

    private TungstenJumps() {}

    public static int bucket(int dx, int dz) {
        double angle = Math.atan2(dz, dx);
        int sector = Math.floorMod((int) Math.round(angle / (Math.PI / 4)), SECTORS);
        int ring = Math.sqrt(dx * dx + dz * dz) <= RING_SPLIT ? 0 : 1;
        return ring * SECTORS + sector;
    }

    // a cell in the middle of bucket b, for the chunk check the A* loop does with a move's offset
    public static int offsetX(int b) {
        return (int) Math.round(Math.cos((b % SECTORS) * Math.PI / 4) * (b < SECTORS ? 2 : 4));
    }

    public static int offsetZ(int b) {
        return (int) Math.round(Math.sin((b % SECTORS) * Math.PI / 4) * (b < SECTORS ? 2 : 4));
    }

    // the jump bucket b offers from x y z, or null
    public static Jump get(CalculationContext context, int x, int y, int z, int b) {
        if (!context.allowTungstenJumps) {
            return null;
        }
        Jump j = context.tungsten().buckets(context, x, y, z)[b];
        return j == NONE ? null : j;
    }

    // the jump from src to dest, if the rule makes it (calculateCost asks this for a movement that already exists)
    public static Jump between(CalculationContext context, BetterBlockPos src, BetterBlockPos dest) {
        if (!context.allowTungstenJumps || !candidate(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z, true)) {
            return null;
        }
        return context.tungsten().jump(context, src, dest, true);
    }

    // per context: the node we last answered for, the jumps we've worked out, the sim budget
    public static final class Cache {
        private long lastNode = Long.MIN_VALUE;
        // whether the last jump() came back "still being worked out"
        private boolean lastPending;
        private Jump[] lastBuckets;
        private final Map<Long, Map<Long, Known>> pairs = new HashMap<>();
        private int pairCount;
        private long budget;
        private boolean warned;
        private SafeCollision world;
        // per column (x z, and the y it was asked from): is there anything bouncy in the BOUNCE_DEPTH cells under it
        private final Map<Long, Boolean> bounceColumns = new HashMap<>();

        synchronized boolean bounceBelow(CalculationContext context, int x, int y, int z) {
            long key = BetterBlockPos.longHash(x, y, z);
            Boolean known = bounceColumns.get(key);
            if (known == null) {
                known = false;
                for (int dy = 1; dy <= BOUNCE_DEPTH; dy++) {
                    if (bouncy(context.get(x, y - dy, z))) {
                        known = true;
                        break;
                    }
                }
                if (bounceColumns.size() > 200_000) {
                    bounceColumns.clear();
                }
                bounceColumns.put(key, known);
            }
            return known;
        }

        public Cache(long budget) {
            this.budget = budget;
        }

        public synchronized long budgetLeft() {
            return budget;
        }

        // a fresh budget for the next search. the answers stay, they're about blocks and those are the same blocks
        public synchronized void newSearch(long budget) {
            this.budget = budget;
            this.warned = false;
            // the last node's buckets may have come up empty for want of budget, not for want of a jump
            this.lastNode = Long.MIN_VALUE;
            this.lastBuckets = null;
        }

        synchronized Jump[] buckets(CalculationContext context, int x, int y, int z) {
            long key = BetterBlockPos.longHash(x, y, z);
            if (key == lastNode && lastBuckets != null) {
                return lastBuckets;
            }
            Jump[] out = new Jump[BUCKETS];
            java.util.Arrays.fill(out, NONE);
            lastNode = key;
            lastBuckets = out;
            if (!takeoff(context, x, y, z)) {
                return out;
            }
            // nearest first in each bucket: the first that works is the one it offers
            List<int[]> todo = new ArrayList<>();
            boolean posts = ledgesWanted(x, y, z);
            for (int dy = posts ? LEDGE_UP : MAX_UP; dy >= -Math.min(MAX_DOWN, context.maxFallHeightNoWater); dy--) {
                for (int dx = -RANGE; dx <= RANGE; dx++) {
                    for (int dz = -RANGE; dz <= RANGE; dz++) {
                        if (candidate(context, x, y, z, dx, dy, dz, false)) {
                            todo.add(new int[]{dx, dy, dz, dx * dx + dz * dz});
                        }
                    }
                }
            }
            todo.sort((a, b) -> Integer.compare(a[3], b[3]));
            BetterBlockPos src = new BetterBlockPos(x, y, z);
            // a bucket gets one ledge chain worked out in the background at a time (they're a second or so each), the
            // nearest: the rest of its ledge pairs wait for the next time it's asked
            boolean[] ledgeAsked = new boolean[BUCKETS];
            for (int[] c : todo) {
                int b = bucket(c[0], c[2]);
                if (out[b] != NONE) {
                    continue;
                }
                boolean ledgeOnly = posts && !plainCandidate(context, x, y, z, c[0], c[1], c[2], false);
                if (ledgeOnly && ledgeAsked[b]) {
                    continue;
                }
                lastPending = false;
                Jump j = jump(context, src, new BetterBlockPos(x + c[0], y + c[1], z + c[2]), false);
                if (j != null) {
                    out[b] = j;
                } else if (ledgeOnly && lastPending) {
                    ledgeAsked[b] = true;
                }
            }
            return out;
        }

        // anyBudget is the executor rechecking a movement it already has (calculateCost, every tick): never refused for the
        // budget, and never answered from more than RECHECK_MS ago, because a block placed in the way since is exactly what
        // that recheck is for
        synchronized Jump jump(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, boolean anyBudget) {
            Map<Long, Known> from = pairs.get(BetterBlockPos.longHash(src));
            Known known = from == null ? null : from.get(BetterBlockPos.longHash(dest));
            long now = System.currentTimeMillis();
            if (known != null && (!anyBudget || now - known.at() < RECHECK_MS)) {
                return known.jump() == NONE ? null : known.jump();
            }
            // (a ledge chain is only ever looked up here, worked out in the background: no budget of the planner's to spend)
            if (budget <= 0 && !anyBudget && plainCandidate(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z, true)) {
                if (!warned) {
                    warned = true;
                    Helper.HELPER.logDebug("Tungsten jumps: out of sim budget for this search (tungstenPlannerBudget)");
                }
                return null;
            }
            if (world == null) {
                world = new SafeCollision(context.bsi);
            }
            long before = PlayerSim.ticks();
            Jump j = simulate(context, world, src, dest, !anyBudget);
            budget -= PlayerSim.ticks() - before;
            if (j == PENDING) {
                lastPending = true;
                return null;
            }
            if (pairCount > 50_000) {
                pairs.clear();
                pairCount = 0;
            }
            pairs.computeIfAbsent(BetterBlockPos.longHash(src), k -> new HashMap<>()).put(BetterBlockPos.longHash(dest), new Known(j == null ? NONE : j, now));
            pairCount++;
            return j;
        }
    }

    private record Known(Jump jump, long at) {}

    private static final long RECHECK_MS = 1000;

    // a node a jump can start from: standing on something, room to jump, not in water, and a drop right next to it
    public static boolean takeoff(CalculationContext context, int x, int y, int z) {
        if (!context.canSprint || !context.isLoaded(x, z)) {
            return false;
        }
        // no rule about the block over our head: a ceiling there is a head hitter, and the sim knows how far one of those goes.
        // standing up on a fence or a wall puts the head in it though
        if (!jumpFloor(context, x, y - 1, z) || !MovementHelper.fullyPassable(context, x, y, z)
                || !MovementHelper.fullyPassable(context, x, y + 1, z)
                || tall(context, x, y - 1, z) && !MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return false;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && hole(context, x + dx, y, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // room to fly through and nothing to stand on
    private static boolean hole(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z) && MovementHelper.fullyPassable(context, x, y + 1, z)
                && !MovementHelper.canWalkOn(context, x, y - 1, z);
    }

    // is dest (src plus dx dy dz) worth a sim at all
    public static boolean candidate(CalculationContext context, int x, int y, int z, int dx, int dy, int dz, boolean existing) {
        return plainCandidate(context, x, y, z, dx, dy, dz, existing)
                || (existing || ledgesWanted(x, y, z)) && ledgeCandidate(context, x, y, z, dx, dy, dz);
    }

    // ledge chains are a last resort, and a lot of background work: they're only looked for round where a search just came
    // up empty (and for a minute after), not everywhere a path goes past a wall
    private record LedgeSpot(int x, int y, int z, long until) {}
    private static final List<LedgeSpot> LEDGE_SPOTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final long LEDGE_MS = 120_000;
    static final int LEDGE_AROUND = 16, MAX_LEDGE_SPOTS = 8;

    public static void wantLedges(int x, int y, int z) {
        long now = System.currentTimeMillis();
        LEDGE_SPOTS.removeIf(s -> s.until() < now);
        boolean known = ledgesWanted(x, y, z);
        LEDGE_SPOTS.removeIf(s -> s.x() == x && s.y() == y && s.z() == z);
        LEDGE_SPOTS.add(new LedgeSpot(x, y, z, now + LEDGE_MS));
        while (LEDGE_SPOTS.size() > MAX_LEDGE_SPOTS) {
            LEDGE_SPOTS.remove(0);
        }
        if (NeoSearches.LOG) {
            System.out.println("[neo search] ledges wanted round " + x + " " + y + " " + z + (known ? "" : " (new)"));
        }
        if (!known) {
            // whoever's waiting on the search should try it again: there's more to look at now
            PendingTables.missed(new Object(), () -> true);
        }
    }

    static boolean ledgesWanted(int x, int y, int z) {
        long now = System.currentTimeMillis();
        for (LedgeSpot s : LEDGE_SPOTS) {
            if (now < s.until() && Math.abs(x - s.x()) <= LEDGE_AROUND && Math.abs(z - s.z()) <= LEDGE_AROUND && Math.abs(y - s.y()) <= LEDGE_AROUND / 2) {
                return true;
            }
        }
        return false;
    }

    // a chain of walks and jumps by way of the rims of blocks with walls or fences on them (Ledges): from a node with posts
    // round it, to a landing a few blocks off, up to two up
    static final int LEDGE_RANGE = 7, LEDGE_UP = 2, LEDGE_DOWN = 3;

    static boolean ledgeCandidate(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) < 2 || dy > LEDGE_UP || dy < -LEDGE_DOWN || -dy > context.maxFallHeightNoWater
                || dx * dx + dz * dz > LEDGE_RANGE * LEDGE_RANGE || !context.isLoaded(x + dx, z + dz)) {
            return false;
        }
        // (anywhere a search came up empty: rims of walled blocks are the usual reason, but the halves of a stair, a slab, a
        // short run up across a gap are all things the grid can't stand on either)
        return destOk(context, x + dx, y + dy, z + dz);
    }

    // a wall, a fence, a pane, a post of any kind (taller than a block, or not covering its cell) within two blocks of x y z
    static boolean postsNear(CalculationContext context, int x, int y, int z) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (post(context, x + dx, y + dy, z + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean post(CalculationContext context, int x, int y, int z) {
        BlockState state = context.get(x, y, z);
        if (state.isAir() || MovementHelper.isClimbable(state.getBlock()) || !state.getFluidState().isEmpty()) {
            return false;
        }
        VoxelShape shape;
        synchronized (SHAPE_POS) {
            shape = state.getCollisionShape(context.bsi.access, SHAPE_POS.set(x, y, z));
        }
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        boolean narrow = shape.min(Direction.Axis.X) > 0.01 || shape.max(Direction.Axis.X) < 0.99
                || shape.min(Direction.Axis.Z) > 0.01 || shape.max(Direction.Axis.Z) < 0.99;
        return top > 1.0 || narrow && top > 0.5;
    }

    public static boolean plainCandidate(CalculationContext context, int x, int y, int z, int dx, int dy, int dz, boolean existing) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) < 2 || dy > MAX_UP || dy < -MAX_DOWN || -dy > context.maxFallHeightNoWater) {
            return false; // next door is the grid moves' business
        }
        int tx = x + dx, ty = y + dy, tz = z + dz;
        // a straight line MovementParkour already jumps is its, it's cheaper to plan. one it won't (under a ceiling, onto a
        // fence, a gap it thinks is too long) is fair game
        // (onto a stair it isn't: it lands at a run and the run carries on down the low step and off the end, where the
        // sim settles on it)
        if (!existing && context.allowParkour && dy >= 0 && (dx == 0 || dz == 0) && !stepped(context.get(x + dx, y + dy - 1, z + dz))) {
            MutableMoveResult r = new MutableMoveResult();
            Direction d = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
            MovementParkour.cost(context, x, y, z, d, r);
            if (r.cost < ActionCosts.COST_INF && r.x == tx && r.y == ty && r.z == tz) {
                return false;
            }
        }
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (!context.isLoaded(tx, tz)) {
            return false;
        }
        boolean grab = grabCell(context, tx, ty, tz);
        // a ladder only has to be touched, not stood on: a bit further than a landing
        double reach = REACH[dy + MAX_DOWN] + (grab ? GRAB_EXTRA : 0);
        // a bounce goes up as high as it came down from, so a ladder (caught on the way up) can be a block over src too
        if (dist > reach && (dy > 0 && !grab || dist > BOUNCE_REACH || !bounceOnTheWay(context, x, y, z, dx, dz, dist))
                && !(dy <= 0 && iceRunway(context, x, y, z, dx, dz, dist) >= ICE_RUNWAY)) {
            return false;
        }
        // the line out has to leave src over a hole, or it's a jump from some other node (or no jump at all). a ladder round
        // the end of a wall (a ladder neo) has the wall right there instead
        double ux = dx / dist, uz = dz / dist;
        int ox = Mth.floor(x + 0.5 + ux * 0.85), oz = Mth.floor(z + 0.5 + uz * 0.85);
        if (!hole(context, ox, y, oz) && !(grab && !MovementHelper.fullyPassable(context, ox, y + 1, oz))
                && neoSide(context, x, y, z, dx, dy, dz) == null && cornerVia(context, x, y, z, dx, dy, dz) == null) {
            return false;
        }
        if (grab) {
            return true;
        }
        return destOk(context, tx, ty, tz);
    }

    // somewhere to stand in tx ty tz
    static boolean destOk(CalculationContext context, int tx, int ty, int tz) {
        return jumpFloor(context, tx, ty - 1, tz)
                && MovementHelper.fullyPassable(context, tx, ty, tz) && MovementHelper.fullyPassable(context, tx, ty + 1, tz)
                && (!tall(context, tx, ty - 1, tz) || MovementHelper.fullyPassable(context, tx, ty + 2, tz))
                && context.get(tx, ty, tz).getFluidState().isEmpty();
    }

    static final double GRAB_EXTRA = 0.7;

    // the straight line out from src leaves it over a hole (the usual jump), not into a wall
    static boolean leavesOverAHole(CalculationContext context, int x, int y, int z, double dx, double dz) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-6) {
            return false;
        }
        return hole(context, Mth.floor(x + 0.5 + dx / dist * 0.85), y, Mth.floor(z + 0.5 + dz / dist * 0.85));
    }

    // a run up this long on ice, behind src and pointing at dest, and the rule's ice hops (JumpPolicy.ICE_VARIANTS) can go
    // further than any standing start: past REACH, up to RANGE
    static final int ICE_RUNWAY = 4;

    // how many blocks of ice there are to run up on, back from src the other way from dest (src's own floor counts)
    static int iceRunway(CalculationContext context, int x, int y, int z, int dx, int dz, double dist) {
        double ux = dx / dist, uz = dz / dist;
        int n = 0;
        for (int i = 0; i <= 10; i++) {
            int bx = Mth.floor(x + 0.5 - ux * i), bz = Mth.floor(z + 0.5 - uz * i);
            BlockState floor = context.get(bx, y - 1, bz);
            if (floor.getBlock().getFriction() <= 0.7F || bouncy(floor) || !MovementHelper.fullyPassable(context, bx, y, bz)) {
                break;
            }
            n++;
        }
        return n;
    }

    // a neo, the shape of one, straight along x or z. either from right behind the wall (a wall in front of src, the row
    // beside the line open at body height all the way to dest, to swing out through), and dest the first thing to land on
    // past the wall (anything further on the same line goes from there), or past a second wall with a one block pad
    // between the two: two neos in one flight, for when the second can't go from standing on the pad. or from the side
    // block: src in the row beside the wall's, at its start, and dest behind the wall in the wall's row, one over. that's
    // how the long ones (a triple neo, round a wall three thick, level) are done, with a run up along the side row.
    // null if not one, else {side x, side z (from the wall's row out to the open one), 1 if it's two in one, 1 if it goes
    // from the side block}
    public static int[] neoSide(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        if (dy > 0 || dy < -2) {
            return null;
        }
        boolean sideStart = false;
        int ux, uz, dist, rx = 0, rz = 0; // rx rz: from src's row over to the wall's row, for a side block start
        if ((dx == 0) != (dz == 0)) {
            dist = Math.abs(dx + dz);
            ux = Integer.signum(dx);
            uz = Integer.signum(dz);
        } else if (Math.abs(dx) >= 2 && Math.abs(dz) == 1) {
            sideStart = true;
            dist = Math.abs(dx);
            ux = Integer.signum(dx);
            uz = 0;
            rz = dz;
        } else if (Math.abs(dz) >= 2 && Math.abs(dx) == 1) {
            sideStart = true;
            dist = Math.abs(dz);
            ux = 0;
            uz = Integer.signum(dz);
            rx = dx;
        } else {
            return null;
        }
        if (dist < 2 || dist > 6) {
            return null;
        }
        // the wall's row: the line itself, or the row over from the side block
        int lx = x + rx, lz = z + rz;
        if (MovementHelper.fullyPassable(context, lx + ux, y, lz + uz) && MovementHelper.fullyPassable(context, lx + ux, y + 1, lz + uz)) {
            return null;
        }
        // what's on the wall's row between: walls, then nothing to stand on (or one pad and more wall: a combo)
        int pads = 0, phase = 0; // 0 first walls, 1 pad seen, 2 second walls, 3 open
        for (int i = 1; i < dist; i++) {
            int cx = lx + ux * i, cz = lz + uz * i;
            boolean wall = !MovementHelper.fullyPassable(context, cx, y, cz) || !MovementHelper.fullyPassable(context, cx, y + 1, cz);
            boolean pad = !wall && (MovementHelper.canWalkOn(context, cx, y - 1, cz) || dy < 0 && MovementHelper.canWalkOn(context, cx, y + dy - 1, cz));
            if (i == 1 && !wall) {
                return null;
            }
            if (wall) {
                if (phase == 1) {
                    phase = 2;
                } else if (phase == 3) {
                    return null; // open, then more wall: not a shape we know
                }
            } else if (pad) {
                if (phase != 0 || dy != 0 || sideStart) {
                    return null; // a second pad, or a pad on the way down: there's a nearer landing
                }
                phase = 1;
                pads++;
            } else {
                if (phase == 1) {
                    return null; // a pad then a gap: the pad is the landing
                }
                phase = 3;
            }
        }
        if (phase == 1) {
            return null;
        }
        if (sideStart) {
            // src's own row has to be open all along, it's the one the swing goes through, and nothing to stand on along it
            // past src (that would be a landing of its own, and a plain jump)
            for (int i = 1; i <= dist; i++) {
                int cx = x + ux * i, cz = z + uz * i;
                if (!MovementHelper.fullyPassable(context, cx, y, cz) || !MovementHelper.fullyPassable(context, cx, y + 1, cz)
                        || MovementHelper.canWalkOn(context, cx, y - 1, cz)) {
                    return null;
                }
            }
            return new int[]{-rx, -rz, 0, 1};
        }
        Direction along = ux > 0 ? Direction.EAST : ux < 0 ? Direction.WEST : uz > 0 ? Direction.SOUTH : Direction.NORTH;
        for (Direction side : new Direction[]{along.getClockWise(), along.getCounterClockWise()}) {
            boolean open = true;
            for (int i = 0; i <= dist && open; i++) {
                int cx = x + ux * i + side.getStepX(), cz = z + uz * i + side.getStepZ();
                open = MovementHelper.fullyPassable(context, cx, y, cz) && MovementHelper.fullyPassable(context, cx, y + 1, cz);
            }
            if (open) {
                return new int[]{side.getStepX(), side.getStepZ(), pads > 0 ? 1 : 0, 0};
            }
        }
        return null;
    }

    // a jump at an angle (not straight along x or z, those are neoSide's) whose straight line runs into something on the
    // way, a pillar or the end of a wall, with room to go round it: the point beside the first thing in the way, on the
    // side that's open, for the search to go by. null if it isn't one of those
    static final double CORNER_REACH = 4.6;

    public static double[] cornerVia(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        if (dx == 0 || dz == 0 || dy > 1 || dy < -2) {
            return null;
        }
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > CORNER_REACH) {
            return null;
        }
        double ux = dx / dist, uz = dz / dist;
        int top = Math.max(y, y + dy);
        int ox = 0, oz = 0;
        boolean hit = false;
        for (double t = 0.5; t < dist - 0.5 && !hit; t += 0.25) {
            ox = Mth.floor(x + 0.5 + ux * t);
            oz = Mth.floor(z + 0.5 + uz * t);
            if ((ox != x || oz != z) && (ox != x + dx || oz != z + dz)) {
                hit = !MovementHelper.fullyPassable(context, ox, top, oz) || !MovementHelper.fullyPassable(context, ox, top + 1, oz);
            }
        }
        if (!hit) {
            return null;
        }
        // either side of it, a block out square to the line: the side that's open (both feet and head) is the way round
        for (int sgn : new int[]{1, -1}) {
            double px = ox + 0.5 - uz * sgn, pz = oz + 0.5 + ux * sgn;
            int cx = Mth.floor(px), cz = Mth.floor(pz);
            if (MovementHelper.fullyPassable(context, cx, top, cz) && MovementHelper.fullyPassable(context, cx, top + 1, cz)
                    && MovementHelper.fullyPassable(context, cx, y, cz) && MovementHelper.fullyPassable(context, cx, y + 1, cz)) {
                return new double[]{px, pz};
            }
        }
        return null;
    }

    // MovementNeo has this one (its tables do flat neos with a runway, and are cheaper to plan than a search)
    private static boolean movementNeoDoes(CalculationContext context, BetterBlockPos src, BetterBlockPos dest) {
        if (dest.y != src.y) {
            return false;
        }
        int dx = dest.x - src.x, dz = dest.z - src.z;
        Direction d = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
        MutableMoveResult r = new MutableMoveResult();
        baritone.pathing.movement.movements.MovementNeo.cost(context, src.x, src.y, src.z, d, r);
        return r.cost < ActionCosts.COST_INF && r.x == dest.x && r.z == dest.z;
    }

    // a ladder or vine cell to catch in mid air: climbable, nothing to stand on in it (that'd be a landing), and room for
    // the head over it (more ladder, or air)
    static boolean grabCell(CalculationContext context, int x, int y, int z) {
        BlockState state = context.get(x, y, z);
        if (!MovementHelper.isClimbable(state.getBlock()) || !state.getFluidState().isEmpty()) {
            return false;
        }
        BlockState above = context.get(x, y + 1, z);
        return MovementHelper.isClimbable(above.getBlock()) || MovementHelper.fullyPassable(context, x, y + 1, z);
    }

    // the landing a node is: a ladder to grab, or a block to stand on
    public static Landing landing(CalculationContext context, BetterBlockPos dest) {
        if (grabCell(context, dest.x, dest.y, dest.z)) {
            return Landing.grab(dest.x, dest.y, dest.z);
        }
        BlockState floor = context.get(dest.x, dest.y - 1, dest.z);
        if (MovementHelper.isBottomSlab(floor)) {
            return Landing.slab(dest.x, dest.y, dest.z);
        }
        return stepped(floor) ? Landing.step(dest.x, dest.y, dest.z) : new Landing(dest.x, dest.y, dest.z);
    }

    // a block with a step in it half way down (a bottom half stair): land on the low step and the feet are in its own cell
    public static boolean stepped(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock
                && state.getValue(net.minecraft.world.level.block.StairBlock.HALF) == net.minecraft.world.level.block.state.properties.Half.BOTTOM;
    }

    // something bouncy under the line from src to dest, somewhere a jump would come down on it
    private static boolean bounceOnTheWay(CalculationContext context, int x, int y, int z, int dx, int dz, double dist) {
        if (!context.isLoaded(x + dx, z + dz)) {
            return false;
        }
        Cache cache = context.tungsten();
        double ux = dx / dist, uz = dz / dist;
        for (double t = 1.0; t <= dist - 1.0; t += 0.5) {
            if (cache.bounceBelow(context, Mth.floor(x + 0.5 + ux * t), y, Mth.floor(z + 0.5 + uz * t))) {
                return true;
            }
        }
        return false;
    }

    static boolean bouncy(BlockState state) {
        return !state.is(net.minecraft.tags.BlockTags.SUPPRESSES_BOUNCE) && state.getBlock().getBounceRestitution() > 0;
    }

    private static final BlockPos.MutableBlockPos SHAPE_POS = new BlockPos.MutableBlockPos();

    // something to jump from or land on: anything Baritone walks on (a bottom slab too, half a block down), and the odd
    // blocks parkour maps love that it won't (fences, walls, panes, bars, hoppers, trapdoors, end rods): whatever has a collision top between
    // three quarters of a block and a block and a half up from its cell. nodes like that only ever join other Tungsten
    // jumps, nothing else in the planner will step on or off them, and the sim decides whether the box really stays up there
    static boolean jumpFloor(CalculationContext context, int x, int y, int z) {
        BlockState state = context.get(x, y, z);
        if (SafeCollision.hazard(state) || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (MovementHelper.canWalkOn(context, x, y, z, state)) {
            return true;
        }
        if (MovementHelper.isClimbable(state.getBlock())) {
            return false;
        }
        double top = top(context, x, y, z, state);
        return top > 0.75 && top <= 1.5;
    }

    // a fence or a wall: you stand half a block into the cell above, so your head needs the one over that too
    private static boolean tall(CalculationContext context, int x, int y, int z) {
        return top(context, x, y, z, context.get(x, y, z)) > 1.0;
    }

    private static double top(CalculationContext context, int x, int y, int z, BlockState state) {
        synchronized (SHAPE_POS) {
            VoxelShape shape = state.getCollisionShape(context.bsi.access, SHAPE_POS.set(x, y, z));
            return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
        }
    }

    // roll the rule out from a standing start in the middle of src
    private static Jump simulate(CalculationContext context, SafeCollision world, BetterBlockPos src, BetterBlockPos dest, boolean schedule) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        double floor = floorTop(world, src.x, src.y, src.z);
        if (Double.isNaN(floor)) {
            return null;
        }
        sim.x = src.x + 0.5;
        sim.y = floor;
        sim.z = src.z + 0.5;
        sim.vy = -0.0784; // what gravity leaves someone standing on the floor
        sim.onGround = true;
        Landing land = landing(context, dest);
        double dx = dest.x - src.x, dz = dest.z - src.z;
        if (!plainCandidate(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z, true)) {
            // too far, too high or no hole to jump over: only ever by way of ledges
            return ledges(context, world, src, dest, floor, land, schedule);
        }
        int[] neo = land.grab() ? null : neoSide(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z);
        // round a wall from right behind it: the rule's aimed variants (a few thousand sim ticks of looking ahead each) all
        // but never do one of those, the background search is what does. don't spend the planner's budget finding that out
        double[] corner = land.grab() || neo != null ? null : cornerVia(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z);
        // and round a corner on the level (a walk round it is RimWalk's, below, and beats any jump the rule would make round
        // the same thing)
        boolean behindWall = (neo != null && neo[3] == 0 || corner != null)
                && (!leavesOverAHole(context, src.x, src.y, src.z, dx, dz) || corner != null && dest.y == src.y);
        // never cheaper than sprinting the straight line, which is what the heuristic assumes is the best there is
        double straight = Math.sqrt(dx * dx + dz * dz) * ActionCosts.SPRINT_ONE_BLOCK_COST;
        // something in the way on the level: walk round it if there's floor to (along the rim of a block with a wall on it,
        // round a pillar's corner, round a walled platform). quicker to find than any jump round it and nothing to miss
        if (!land.grab() && dest.y == src.y && JumpPolicy.blocked(world, sim.x, sim.y, sim.z, land.centerX(), land.y(), land.centerZ())) {
            TungstenSearch.Plan walk = RimWalk.plan(world, src, floor, land);
            if (walk != null) {
                double cost = context.biasJump(Math.max(walk.ticks(), straight));
                return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(walk.states()), walk);
            }
        }
        JumpPolicy.Rollout r = behindWall ? null : JumpPolicy.forgiving(sim, land);
        if (r != null) {
            // a neo of the rule's (aimed off the line round a wall) is the last resort, behind MovementNeo's tables when it
            // has one: it's the touchiest thing the rule does
            double aimed = r.variant().aim() != 0F ? NEO_COST : 1;
            double cost = context.biasJump(Math.max(r.ticks(), straight) * aimed + context.jumpPenalty);
            return new Jump(src, dest, cost, r.variant(), List.copyOf(r.states()), null);
        }
        if (land.grab() && dest.y == src.y && JumpPolicy.blocked(world, sim.x, sim.y, sim.z, land.centerX(), land.y(), land.centerZ())) {
            // a ladder round the end of a wall: the rule only steers straight at things, the full search swings round. it
            // can't promise a bad aim still makes it the way the rule's AIM_ERRORS do, so it costs a bit more
            TungstenSearch.Plan plan = TungstenSearch.search(sim, land, JumpPilot.region(sim, land), NEO_SEARCH_BUDGET, null);
            if (plan != null && plan.ticks() > 0) {
                double cost = context.biasJump(Math.max(plan.ticks(), straight) * 1.5 + context.jumpPenalty);
                return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(plan.states()), plan);
            }
        }
        if (DEBUG_SIM) {
            System.out.println("simulate " + src + " -> " + dest + ": neo " + java.util.Arrays.toString(neo) + " behindWall " + behindWall + " rule " + (r != null)
                    + " blocked " + JumpPolicy.blocked(world, sim.x, sim.y, sim.z, land.centerX(), land.y(), land.centerZ())
                    + " movementNeo " + (neo != null && movementNeoDoes(context, src, dest)));
        }
        if (neo != null && JumpPolicy.blocked(world, sim.x, sim.y, sim.z, land.centerX(), land.y(), land.centerZ())
                && !movementNeoDoes(context, src, dest)) {
            // round a wall the rule can't get round (three thick, two back to back): the full search, in the background
            // the swing goes through the row beside the wall, halfway along: the side row for a run from right behind the
            // wall, src's own row for one from the side block
            double[] via = neo[3] == 1
                    ? new double[]{(src.x + 0.5 + land.centerX()) / 2 + neo[0] * 0.5, (src.z + 0.5 + land.centerZ()) / 2 + neo[1] * 0.5}
                    : NeoSearches.via(src, land, neo[0], neo[1]);
            TungstenSearch.Plan plan = NeoSearches.lookup(world, src, floor, land, via, neo[2] == 1, schedule);
            if (plan == NeoSearches.WAIT) {
                return PENDING;
            }
            if (plan == null) {
                return ledges(context, world, src, dest, floor, land, schedule);
            }
            // two in one flight has the least room of anything: only when there's no way with a stop on the pad between
            double cost = context.biasJump(Math.max(plan.ticks(), straight) * (neo[2] == 1 ? COMBO_COST : NEO_COST) + context.jumpPenalty);
            return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(plan.states()), plan);
        }
        if (corner != null && JumpPolicy.blocked(world, sim.x, sim.y, sim.z, land.centerX(), land.y(), land.centerZ())) {
            // round the corner of a pillar or a wall at an angle: the same background search, by way of the corner
            TungstenSearch.Plan plan = NeoSearches.lookup(world, src, floor, land, corner, false, schedule);
            if (plan == NeoSearches.WAIT) {
                return PENDING;
            }
            if (plan == null) {
                return ledges(context, world, src, dest, floor, land, schedule);
            }
            double cost = context.biasJump(Math.max(plan.ticks(), straight) * NEO_COST + context.jumpPenalty);
            return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(plan.states()), plan);
        }
        return ledges(context, world, src, dest, floor, land, schedule);
    }

    static final double LEDGE_COST = 1.2;

    // by way of ledges (Ledges), worked out in the background, when there are posts round src to make rims
    private static Jump ledges(CalculationContext context, SafeCollision world, BetterBlockPos src, BetterBlockPos dest, double floor, Landing land,
                               boolean schedule) {
        if (land.grab() || !ledgeCandidate(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z)) {
            return null;
        }
        TungstenSearch.Plan plan = NeoSearches.lookup(world, src, floor, land, null, NeoSearches.LEDGES, schedule);
        if (plan == NeoSearches.WAIT) {
            return PENDING;
        }
        if (plan == null) {
            return null;
        }
        double dx = dest.x - src.x, dz = dest.z - src.z;
        double straight = Math.sqrt(dx * dx + dz * dz) * ActionCosts.SPRINT_ONE_BLOCK_COST;
        double cost = context.biasJump(Math.max(plan.ticks(), straight) * LEDGE_COST + context.jumpPenalty);
        return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(plan.states()), plan);
    }

    // where the feet rest standing in x y z: the top of whatever's under them, or NaN if that's not within a few
    // hundredths under y (Baritone calls the cell above a floor the place you stand, and the sim wants the real height)
    static double floorTop(SafeCollision world, int x, int y, int z) {
        BoxList boxes = new BoxList();
        world.boxes(x, y - 1, z, boxes);
        world.boxes(x, y, z, boxes);
        double top = Double.NaN;
        for (int i = 0; i < boxes.size(); i++) {
            // the column the middle of the box stands in
            if (boxes.get(i, 0) <= x + 0.5 && boxes.get(i, 3) >= x + 0.5 && boxes.get(i, 2) <= z + 0.5 && boxes.get(i, 5) >= z + 0.5) {
                double t = boxes.get(i, 4);
                if (t <= y + 0.5 && (Double.isNaN(top) || t > top)) {
                    top = t;
                }
            }
        }
        // a bottom slab under the cell: half a block down, still where Baritone says we stand
        return !Double.isNaN(top) && top > y - 0.55 ? top : Double.NaN;
    }
}
