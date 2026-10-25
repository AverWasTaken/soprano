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
import baritone.pathing.sim.CollisionProvider;
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
    // jumps go in buckets by direction (sectors), distance (rings) and height (bands: about level or up, and down two or more),
    // the nearest that works in each: a drop onto the floor below a course mustn't be what fills the bucket the jump along
    // the course is in
    public static final int SECTORS = 8, RINGS = 2, BANDS = 2, STEPS = SECTORS * BANDS, BUCKETS = SECTORS * RINGS * BANDS + STEPS;
    // (and after those, the cells next door: STEPS more buckets, one per direction and band)
    private static final double RING_SPLIT = 3.6;
    // furthest the rule has landed from a standing start on a single block, middle to middle, by dy (see ReachProbe), with a
    // little to spare
    static final double[] REACH = {4.9 /* -3 */, 3.5 /* -2 */, 6.3 /* -1 */, 6.2 /* 0 */, 4.7 /* +1 */};
    static final int MAX_UP = 1, MAX_DOWN = 3, RANGE = 9;
    // where falls don't hurt (maxFallHeightNoWater over 3: a parkour server with fall damage off), a jump's peak is free to come
    // down a long way, and the further down the landing the further the jump goes: -8 to +1
    static final double[] FREE_REACH = {7.9, 7.8, 7.6, 7.4, 7.2, 7.0, 6.8, 6.5, 6.2, 4.7};
    static final int FREE_DOWN = 8;

    // how far a jump dy up (or down) can go at most, and how far down to look at all
    static double reach(CalculationContext context, int dy) {
        dy = Math.min(dy, MAX_UP); // (one more cell up onto a pot or a head is no more than MAX_UP really, see upTooFar)
        return context.maxFallHeightNoWater > JumpPolicy.SAFE_FALL ? FREE_REACH[Math.max(dy, -FREE_DOWN) + FREE_DOWN]
                : REACH[Math.max(dy, -MAX_DOWN) + MAX_DOWN];
    }

    static int maxDown(CalculationContext context) {
        return context.maxFallHeightNoWater > JumpPolicy.SAFE_FALL ? Math.min(FREE_DOWN, context.maxFallHeightNoWater) : MAX_DOWN;
    }
    // with something bouncy (slime) under the way across, a jump comes down on it and goes up again: further than any
    // standing start, never higher than it started (a ladder is caught on the way up, that can be). how far the rule has
    // made one of those go, middle to middle
    static final double BOUNCE_REACH = 9.5;
    // how far under the line to look for it
    static final int BOUNCE_DEPTH = 10;

    // what a bucket offers: a landing and what getting there costs
    // plan is the full search's answer when the rule had none (a ladder neo), null otherwise
    // carry: the sector (of CARRY_SECTORS) the jump into src came from, for a jump that only works carrying that one's speed on
    // through src (jumping again the tick it touches down there: a pot, a post, a head with no room to run up on). -1 for a jump
    // from standing still
    public record Jump(BetterBlockPos src, BetterBlockPos dest, double cost, JumpPolicy.Variant variant, List<SimState> trace,
                       TungstenSearch.Plan plan, int carry) {
        public Jump(BetterBlockPos src, BetterBlockPos dest, double cost, JumpPolicy.Variant variant, List<SimState> trace,
                    TungstenSearch.Plan plan) {
            this(src, dest, cost, variant, trace, plan, -1);
        }
    }

    // carrying the speed of the last jump on through a small floor: the directions it can have come from, how fast it's
    // taken to be going at the touchdown (a sprint jump's air speed, a bit under), how far off the line on it can go on,
    // what it costs over a jump from standing (it only works if the last jump hands it over at speed), how much further it goes
    static final int CARRY_SECTORS = 16;
    static final double CARRY_SPEED = 0.24, CARRY_COS = 0.3, CARRY_COST = 1.4, CARRY_EXTRA = 1.5;
    // the jump has to go within this many ticks of the touchdown, or it's a jump from standing after all
    static final int CARRY_JUMP_BY = 4;

    private static final Jump NONE = new Jump(null, null, ActionCosts.COST_INF, null, List.of(), null);
    // no answer yet, it's being worked out in the background (NeoSearches): not kept, the next ask may have it
    private static final Jump PENDING = new Jump(null, null, ActionCosts.COST_INF, null, List.of(), null);
    // the full search's budget for a ladder neo the rule can't do, in sim ticks. the 1 thick wall with a runway behind it is
    // a couple of thousand
    static final long NEO_SEARCH_BUDGET = 40_000;
    static final double NEO_COST = 1.5, COMBO_COST = 3.0;
    // a jump that only lands with the aim within a degree of right (JumpPolicy.TIGHT)
    static final double TIGHT_COST = 1.3;
    static final boolean DEBUG_SIM = System.getenv("TUNGSTEN_SIM_DEBUG") != null;

    private TungstenJumps() {}

    // where the planner's sim ticks go, by whether the jump came good and by dy (-8 .. +3): TUNGSTEN_PROFILE=1
    public static final boolean PROFILE = System.getenv("TUNGSTEN_PROFILE") != null;
    public static final long[][] PROFILE_TICKS = new long[2][12], PROFILE_CALLS = new long[2][12];
    // rim walk, rule ok, rule failed, rule ok with the neo variants, rule failed with them
    public static final long[] PROFILE_PARTS = new long[5], PROFILE_PARTS_N = new long[5];

    public static int bucket(int dx, int dz) {
        return bucket(dx, 0, dz);
    }

    public static int bucket(int dx, int dy, int dz) {
        double angle = Math.atan2(dz, dx);
        int sector = Math.floorMod((int) Math.round(angle / (Math.PI / 4)), SECTORS);
        int band = dy <= -2 ? 1 : 0;
        if (Math.max(Math.abs(dx), Math.abs(dz)) <= 1) {
            return SECTORS * RINGS * BANDS + band * SECTORS + sector;
        }
        int ring = Math.sqrt(dx * dx + dz * dz) <= RING_SPLIT ? 0 : 1;
        return (band * RINGS + ring) * SECTORS + sector;
    }

    // a cell in the middle of bucket b, for the chunk check the A* loop does with a move's offset
    public static int offsetX(int b) {
        return (int) Math.round(Math.cos((b % SECTORS) * Math.PI / 4) * (b >= SECTORS * RINGS * BANDS ? 1 : (b / SECTORS) % RINGS == 0 ? 2 : 4));
    }

    public static int offsetZ(int b) {
        return (int) Math.round(Math.sin((b % SECTORS) * Math.PI / 4) * (b >= SECTORS * RINGS * BANDS ? 1 : (b / SECTORS) % RINGS == 0 ? 2 : 4));
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
        return between(context, src, dest, -1);
    }

    // the same for a jump carrying the last one's speed (carry is its sector, see Jump)
    public static Jump between(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, int carry) {
        if (carry >= 0) {
            if (!context.allowTungstenJumps || !carryCandidate(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z, carry)) {
                return null;
            }
            return context.tungsten().carryJump(context, src, dest, carry, true);
        }
        if (!context.allowTungstenJumps || !candidate(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z, true)) {
            return null;
        }
        return context.tungsten().jump(context, src, dest, true);
    }

    // a leap along the remembered way (RouteMemory): from a cell on the guide to one a few cells on, further than any jump
    // A* asks about, in one go by the full search (a run along a block and straight off its far edge into the next jump,
    // the speed carried, the way the player went: Hell's 1443 -> 1447 -> 1453). the furthest along one that works. null for
    // none (or none yet: worked out in the background, PendingTables says so)
    public static Jump trailLeap(CalculationContext context, int x, int y, int z) {
        baritone.behavior.RouteMemory.Guide g = context.routeGuide;
        if (g == null || !context.allowTungstenJumps) {
            return null;
        }
        int a = g.alongNear(x, y, z);
        if (a < 0) {
            return null;
        }
        if (NeoSearches.LOG) {
            System.out.println("[trail leap] from " + x + " " + y + " " + z + " (" + a + " of " + g.length() + ")");
        }
        BetterBlockPos src = new BetterBlockPos(x, y, z);
        for (int k = Math.min(g.length(), a + TRAIL_LEAP); k >= a + 2; k--) {
            long c = g.cell(k);
            if (c == Long.MIN_VALUE) {
                continue;
            }
            int tx = net.minecraft.core.BlockPos.getX(c), ty = net.minecraft.core.BlockPos.getY(c), tz = net.minecraft.core.BlockPos.getZ(c);
            int dx = tx - x, dz = tz - z;
            if (dx * dx + dz * dz <= TRAIL_LEAP_MIN * TRAIL_LEAP_MIN || dx * dx + dz * dz > TRAIL_LEAP_MAX * TRAIL_LEAP_MAX
                    || ty - y > 1 || y - ty > context.maxFallHeightNoWater || !destOk(context, tx, ty, tz)) {
                continue; // (near enough for the jumps A* asks about anyway, or more than a leap)
            }
            Jump j = context.tungsten().leap(context, src, new BetterBlockPos(tx, ty, tz));
            if (j != null) {
                return j;
            }
        }
        return null;
    }

    // the same leap from src to dest, if there is one (MovementTungsten.calculateCost, for a movement that's one)
    public static Jump leapBetween(CalculationContext context, BetterBlockPos src, BetterBlockPos dest) {
        if (context.routeGuide == null || context.routeGuide.alongNear(src.x, src.y, src.z) < 0 || context.routeGuide.along(dest.x, dest.y, dest.z) < 0) {
            return null;
        }
        return context.tungsten().leap(context, src, dest);
    }

    // how many cells on along the way a leap may go, and how far (across) it has to be to be one
    static final int TRAIL_LEAP = 6;
    static final double TRAIL_LEAP_MIN = 4.5, TRAIL_LEAP_MAX = 12;
    static final double LEAP_COST = 1.3;

    // the sector of CARRY_SECTORS a jump from (fx, fz) to (x, z) heads in, or -1 if that's no jump (next door, or nothing)
    public static int carrySector(int fx, int fz, int x, int z) {
        int dx = x - fx, dz = z - fz;
        if (dx * dx + dz * dz < 4) {
            return -1;
        }
        return sectorOf(dx, dz);
    }

    // the same for an arrival A* knows the cells of: no carry out of a walk round a post to the other side of a pillar
    // (RimWalk), that ends standing still
    public static int carrySector(CalculationContext context, int fx, int fy, int fz, int x, int y, int z) {
        if (samePillar(context, fx, fy, fz, x - fx, y - fy, z - fz)) {
            return -1;
        }
        return carrySector(fx, fz, x, z);
    }

    private static int sectorOf(int dx, int dz) {
        return Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (2 * Math.PI / CARRY_SECTORS)), CARRY_SECTORS);
    }

    static double carryX(int sector) {
        return Math.cos(sector * 2 * Math.PI / CARRY_SECTORS);
    }

    static double carryZ(int sector) {
        return Math.sin(sector * 2 * Math.PI / CARRY_SECTORS);
    }

    // worth a carried jump at all: on about the same line as the jump in, past what a standing start reaches (or the plain
    // jump would do), a takeoff with a hole to jump over
    static boolean carryCandidate(CalculationContext context, int x, int y, int z, int dx, int dy, int dz, int carry) {
        if (Math.max(Math.abs(dx), Math.abs(dz)) < 2 || upTooFar(context, x, y, z, dx, dy, dz) || dy < -maxDown(context) || -dy > context.maxFallHeightNoWater) {
            return false;
        }
        double dist = Math.sqrt(dx * dx + dz * dz);
        if ((dx * carryX(carry) + dz * carryZ(carry)) / dist < CARRY_COS || dist > reach(context, dy) + CARRY_EXTRA) {
            return false;
        }
        int tx = x + dx, ty = y + dy, tz = z + dz;
        if (!context.isLoaded(tx, tz) || grabCell(context, tx, ty, tz)) {
            return false;
        }
        double ux = dx / dist, uz = dz / dist;
        if (!hole(context, Mth.floor(x + 0.5 + ux * 0.85), y, Mth.floor(z + 0.5 + uz * 0.85))) {
            return false;
        }
        return destOk(context, tx, ty, tz);
    }

    // touching down on src going at CARRY_SPEED along the carry sector, and jumping again straight away
    private static Jump simulateCarry(CalculationContext context, SafeCollision world, BetterBlockPos src, BetterBlockPos dest, int carry) {
        double[] at = standAt(world, src.x, src.y, src.z);
        if (Double.isNaN(at[2])) {
            return null;
        }
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = src.x + at[0];
        sim.y = at[2];
        sim.z = src.z + at[1];
        sim.vx = carryX(carry) * CARRY_SPEED;
        sim.vz = carryZ(carry) * CARRY_SPEED;
        sim.vy = -0.0784;
        sim.onGround = true;
        sim.sprinting = true;
        Landing land = landing(context, dest);
        JumpPolicy.Rollout r = JumpPolicy.forgiving(sim, land, new JumpPolicy.Variant(0, 0F));
        if (r == null || r.ticks() == 0) {
            return null;
        }
        int up = -1;
        for (int i = 0; i < r.states().size() && i <= CARRY_JUMP_BY + 1; i++) {
            if (!r.states().get(i).onGround) {
                up = i;
                break;
            }
        }
        if (up < 0 || up > CARRY_JUMP_BY + 1) {
            return null;
        }
        double dx = dest.x - src.x, dz = dest.z - src.z;
        double straight = Math.sqrt(dx * dx + dz * dz) * ActionCosts.SPRINT_ONE_BLOCK_COST;
        double cost = context.biasJump(Math.max(r.ticks(), straight) * CARRY_COST + context.jumpPenalty);
        return new Jump(src, dest, cost, r.variant(), List.copyOf(r.states()), null, carry);
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
        private long searchStart = System.currentTimeMillis();
        private boolean warned;
        private SafeCollision world;
        // per column (x z, and the y it was asked from): is there anything bouncy in the BOUNCE_DEPTH cells under it
        // (keyed by the packed position, not a hash of it: one key, one column. no boxing either, A* asks this a lot)
        private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap bounceColumns = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

        synchronized boolean bounceBelow(CalculationContext context, int x, int y, int z) {
            long key = net.minecraft.core.BlockPos.asLong(x, y, z);
            byte known = bounceColumns.getOrDefault(key, (byte) -1);
            if (known < 0) {
                known = 0;
                for (int dy = 1; dy <= BOUNCE_DEPTH; dy++) {
                    if (bouncy(context.get(x, y - dy, z))) {
                        known = 1;
                        break;
                    }
                }
                if (bounceColumns.size() > 200_000) {
                    bounceColumns.clear();
                }
                bounceColumns.put(key, known);
            }
            return known == 1;
        }

        public Cache(long budget) {
            this.budget = budget;
        }

        // the sector the jump into the node being asked about came from (A* sets it before asking about a node's moves, Path
        // before rebuilding a node's movement), -1 for none: a node reached on foot, or the start
        private int arrival = -1;
        private final Map<Long, Known> carried = new HashMap<>();

        public synchronized void arriving(int sector) {
            arrival = sector;
        }

        synchronized Jump carryJump(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, int carry, boolean anyBudget) {
            long key = (BetterBlockPos.longHash(src) * 31 + BetterBlockPos.longHash(dest)) * CARRY_SECTORS + carry;
            Known known = carried.get(key);
            long now = System.currentTimeMillis();
            if (known != null && (!anyBudget || now - known.at() < RECHECK_MS)) {
                return known.jump() == NONE ? null : known.jump();
            }
            if (budget <= 0 && !anyBudget) {
                return null;
            }
            if (world == null) {
                world = new SafeCollision(context.bsi);
            }
            long before = PlayerSim.ticks();
            Jump j = simulateCarry(context, world, src, dest, carry);
            budget -= PlayerSim.ticks() - before;
            if (carried.size() > 50_000) {
                carried.clear();
            }
            carried.put(key, new Known(j == null ? NONE : j, now));
            return j;
        }

        public synchronized long budgetLeft() {
            return budget;
        }

        // a fresh budget for the next search. the answers stay, they're about blocks and those are the same blocks
        public synchronized void newSearch(long budget) {
            this.budget = budget;
            this.searchStart = System.currentTimeMillis();
            this.warned = false;
            // the last node's buckets may have come up empty for want of budget, not for want of a jump
            this.lastNode = Long.MIN_VALUE;
            this.lastBuckets = null;
        }

        synchronized Jump[] buckets(CalculationContext context, int x, int y, int z) {
            long key = BetterBlockPos.longHash(x, y, z) * (CARRY_SECTORS + 1) + arrival + 1;
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
            for (int dy = posts ? LEDGE_UP : MAX_UP + 1; dy >= -Math.min(maxDown(context), context.maxFallHeightNoWater); dy--) {
                for (int dx = -RANGE; dx <= RANGE; dx++) {
                    for (int dz = -RANGE; dz <= RANGE; dz++) {
                        if (!belowTheFloor(context, x, y, z, dx, dy, dz) && candidate(context, x, y, z, dx, dy, dz, false)) {
                            todo.add(new int[]{dx, dy, dz, order(dx, dy, dz)});
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
                int b = bucket(c[0], c[1], c[2]);
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
            if (arrival >= 0 && smallFloor(context, x, y, z) && !rimNode(context, x, y, z)) {
                // on a floor too small to run up on, come in at speed: the jumps that only carrying that speed on make, in
                // the buckets nothing from standing filled
                List<int[]> carry = new ArrayList<>();
                for (int dy = MAX_UP + 1; dy >= -Math.min(maxDown(context), context.maxFallHeightNoWater); dy--) {
                    for (int dx = -RANGE; dx <= RANGE; dx++) {
                        for (int dz = -RANGE; dz <= RANGE; dz++) {
                            if (out[bucket(dx, dy, dz)] == NONE && !belowTheFloor(context, x, y, z, dx, dy, dz) && carryCandidate(context, x, y, z, dx, dy, dz, arrival)) {
                                carry.add(new int[]{dx, dy, dz, order(dx, dy, dz)});
                            }
                        }
                    }
                }
                carry.sort((a, b) -> Integer.compare(a[3], b[3]));
                for (int[] c : carry) {
                    int b = bucket(c[0], c[1], c[2]);
                    if (out[b] != NONE) {
                        continue;
                    }
                    Jump j = carryJump(context, src, new BetterBlockPos(x + c[0], y + c[1], z + c[2]), arrival, false);
                    if (j != null) {
                        out[b] = j;
                    }
                }
            }
            return out;
        }

        // anyBudget is the executor rechecking a movement it already has (calculateCost, every tick): never refused for the
        // budget, and never answered from more than RECHECK_MS ago, because a block placed in the way since is exactly what
        // that recheck is for
        // a leap along the trail (trailLeap): the full search, in the background, standing in src
        synchronized Jump leap(CalculationContext context, BetterBlockPos src, BetterBlockPos dest) {
            if (world == null) {
                world = new SafeCollision(context.bsi);
            }
            double[] at = standAt(world, src.x, src.y, src.z);
            if (Double.isNaN(at[2]) || !rimNode(context, src.x, src.y, src.z) && !destOk(context, src.x, src.y, src.z)) {
                return null; // (on a floor, or on the rim of a block with a post on it: the full search starts wherever standAt says)
            }
            Landing land = landing(context, dest);
            if (land.grab() || land.water()) {
                return null;
            }
            TungstenSearch.Plan plan = NeoSearches.lookup(world, src, at[2], land, null, NeoSearches.LEAP, true);
            if (plan == null || plan == NeoSearches.WAIT || plan.ticks() == 0) {
                return null;
            }
            double dx = dest.x - src.x, dz = dest.z - src.z;
            double straight = Math.sqrt(dx * dx + dz * dz) * ActionCosts.SPRINT_ONE_BLOCK_COST;
            double cost = context.biasJump(Math.max(plan.ticks(), straight) * LEAP_COST + context.jumpPenalty);
            return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(plan.states()), plan);
        }

        synchronized Jump jump(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, boolean anyBudget) {
            Map<Long, Known> from = pairs.get(BetterBlockPos.longHash(src));
            Known known = from == null ? null : from.get(BetterBlockPos.longHash(dest));
            long now = System.currentTimeMillis();
            if (known != null && (!anyBudget || now - known.at() < RECHECK_MS)) {
                return known.jump() == NONE ? null : known.jump();
            }
            // (a ledge chain is only ever looked up here, worked out in the background: no budget of the planner's to spend)
            // and whatever the sim ticks say, never more than SEARCH_MS of a search's time on these: one node's jumps (dozens
            // of them, the rule's neos looking ahead every tick) can otherwise hold A* past every one of its timeouts
            if (!anyBudget && System.currentTimeMillis() - searchStart > SEARCH_MS) {
                budget = 0;
            }
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
            // what the jump net makes of it: sure it won't work, the sim isn't asked (JumpNetStore); and whatever the sim
            // says, the net learns from
            int[] feat = null;
            float guess = -1;
            if (JumpNetStore.learning()) {
                feat = JumpNet.features(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z);
                guess = JumpNetStore.net().predict(feat);
                if (!anyBudget && JumpNetStore.skip(guess)) {
                    return null; // (not kept: asked again, it's asked of the net again, and now and then of the sim)
                }
            }
            long before = PlayerSim.ticks();
            Jump j = simulate(context, world, src, dest, !anyBudget);
            budget -= PlayerSim.ticks() - before;
            if (feat != null && j != PENDING) {
                JumpNetStore.learn(feat, guess, j != null);
            }
            if (PROFILE) {
                int dy = dest.y - src.y, k = j == null || j == PENDING ? 0 : 1;
                PROFILE_TICKS[k][Math.max(0, Math.min(11, dy + 8))] += PlayerSim.ticks() - before;
                PROFILE_CALLS[k][Math.max(0, Math.min(11, dy + 8))]++;
            }
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

    // the most of one search's time the jumps may take (see Cache.jump)
    static final long SEARCH_MS = 8_000;

    private static final long RECHECK_MS = 1000;

    // a node a jump can start from: standing on something, room to jump, not in water, and a drop right next to it
    public static boolean takeoff(CalculationContext context, int x, int y, int z) {
        if (!context.canSprint || !context.isLoaded(x, z)) {
            return false;
        }
        if (waterCell(context, x, y, z)) {
            return true; // swim up it and jump out of the top
        }
        // no rule about the block over our head: a ceiling there is a head hitter, and the sim knows how far one of those goes.
        // standing up on a fence or a wall puts the head in it though
        if (!jumpFloor(context, x, y - 1, z) && !rimNode(context, x, y, z) || !MovementHelper.fullyPassable(context, x, y, z)
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

    // a step between next door cells that the grid moves don't do: off or onto a floor they won't stand on (bars, a pane, a
    // fence's or a wall's top, a ladder's top, a pot or a head), or a rim
    static boolean oddStep(CalculationContext context, int x, int y, int z, int tx, int ty, int tz) {
        return odd(context, x, y, z) || odd(context, tx, ty, tz);
    }

    private static boolean odd(CalculationContext context, int x, int y, int z) {
        BlockState under = context.get(x, y - 1, z);
        return !MovementHelper.canWalkOn(context, x, y - 1, z, under) || MovementHelper.isClimbable(under.getBlock())
                || baritone.api.utils.IPlayerContext.smallFloor(under.getBlock()) || tall(context, x, y - 1, z);
    }

    // which candidate a bucket tries first: the nearest, and of the drops the shallowest (a drop to the floor under a course is
    // often nearer across than the next block of the course a few down, and it mustn't be what fills the bucket)
    static int order(int dx, int dy, int dz) {
        return dx * dx + dz * dz + (dy < -1 ? -dy * 40 : 0);
    }

    // down where the planner won't go anyway (Settings.avoidBelowY, FallFloor): no use taking up a bucket
    static boolean belowTheFloor(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        return dy < 0 && (y + dy < context.avoidBelowY
                || baritone.behavior.FallFloor.deadly(context.get(x + dx, y + dy - 1, z + dz).getBlock(), x + dx, y + dy, z + dz));
    }

    // somewhere with no room to run up: a floor less than a block across (a post, a pot, a head, a ladder's top, a rim), or a
    // single block with drops all round it
    static boolean smallFloor(CalculationContext context, int x, int y, int z) {
        if (!MovementHelper.canWalkOn(context, x, y - 1, z) || MovementHelper.isClimbable(context.get(x, y - 1, z).getBlock())
                || MovementHelper.isHalfFloor(context.get(x, y - 1, z)) && !(context.get(x, y - 1, z).getBlock() instanceof net.minecraft.world.level.block.SlabBlock)) {
            return true;
        }
        for (int d = 0; d < 4; d++) {
            if (!hole(context, x + DIR_X[d], y, z + DIR_Z[d])) {
                return false;
            }
        }
        return true;
    }

    // room to fly through and nothing to stand on
    private static boolean hole(CalculationContext context, int x, int y, int z) {
        return MovementHelper.fullyPassable(context, x, y, z) && MovementHelper.fullyPassable(context, x, y + 1, z)
                && !MovementHelper.canWalkOn(context, x, y - 1, z);
    }

    // from one rim of a pillar round its post to another (RimWalk): two rim nodes on the same level, both next to the same
    // block with a post on it
    static boolean samePillar(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        if (dy != 0 || Math.abs(dx) > 2 || Math.abs(dz) > 2 || Math.abs(dx) + Math.abs(dz) > 2 || dx == 0 && dz == 0) {
            return false;
        }
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            // round a corner of it: the pillar's one of the two cells next to both
            if (MovementHelper.fullyPassable(context, x + dx, y, z) == MovementHelper.fullyPassable(context, x, y, z + dz)) {
                return false;
            }
        } else if (MovementHelper.fullyPassable(context, x + dx / 2, y, z + dz / 2)) {
            return false; // straight across it: the post's right between
        }
        return rimNode(context, x, y, z) && rimNode(context, x + dx, y, z + dz);
    }

    // is dest (src plus dx dy dz) worth a sim at all
    public static boolean candidate(CalculationContext context, int x, int y, int z, int dx, int dy, int dz, boolean existing) {
        return plainCandidate(context, x, y, z, dx, dy, dz, existing) || samePillar(context, x, y, z, dx, dy, dz)
                || (existing || ledgesWanted(x, y, z)) && ledgeCandidate(context, x, y, z, dx, dy, dz);
    }

    // ledge chains are a last resort, and a lot of background work: they're only looked for round where a search just came
    // up empty (and for a minute after), not everywhere a path goes past a wall
    private record LedgeSpot(int x, int y, int z, long until) {}
    private static final List<LedgeSpot> LEDGE_SPOTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final long LEDGE_MS = 120_000;
    static final int LEDGE_AROUND = 16, MAX_LEDGE_SPOTS = 8;

    // returns whether this spot was already wanted (we've been stuck round here before)
    public static boolean wantLedges(int x, int y, int z) {
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
        return known;
    }

    // how many searches from exactly here have come up empty lately, counting this one (a search's own answer, not the peeks
    // at it while it runs)
    public static int stuckHere(int x, int y, int z) {
        long now = System.currentTimeMillis(), key = BetterBlockPos.longHash(x, y, z);
        STUCK.values().removeIf(v -> v[1] < now);
        long[] v = STUCK.computeIfAbsent(key, k -> new long[2]);
        v[0]++;
        v[1] = now + LEDGE_MS;
        return (int) v[0];
    }

    private static final Map<Long, long[]> STUCK = new java.util.concurrent.ConcurrentHashMap<>();

    // a search round here has come up empty lately
    public static boolean stuckAround(int x, int y, int z) {
        return ledgesWanted(x, y, z);
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

    // how far up the floor at dest really is from the one at src: a node counts its feet a cell up from a pot or a head (half a
    // block down in its cell, IPlayerContext.playerFeet), and a fence or a wall puts them half a block up into theirs
    static double rise(CalculationContext context, int x, int y, int z, int tx, int ty, int tz) {
        return floorOffset(context, tx, ty, tz, true) + ty - floorOffset(context, x, y, z, false) - y;
    }

    private static double floorOffset(CalculationContext context, int x, int y, int z, boolean landing) {
        BlockState under = context.get(x, y - 1, z);
        if (MovementHelper.isHalfFloor(under) || landing && stepped(under)) {
            return -0.5; // (a stair to land on: its low step, where a jump onto it can come down)
        }
        return tall(context, x, y - 1, z) ? top(context, x, y - 1, z, under) - 1 : 0;
    }

    // up more than MAX_UP cells, but the floors really only MAX_UP apart or less (a fence onto a pot on a fence two up)
    static boolean upTooFar(CalculationContext context, int x, int y, int z, int dx, int dy, int dz) {
        return dy > MAX_UP && (dy > MAX_UP + 1 || rise(context, x, y, z, x + dx, y + dy, z + dz) > MAX_UP + 0.01);
    }

    public static boolean plainCandidate(CalculationContext context, int x, int y, int z, int dx, int dy, int dz, boolean existing) {
        // out of the top of a water block a jump goes up to two (its surface is near the top of the cell, and the jump out
        // is a whole jump)
        boolean fromWater = waterCell(context, x, y, z), intoWater = waterCell(context, x + dx, y + dy, z + dz);
        boolean waterUp = fromWater && dy <= 2;
        // into a column of water, it's enough for the top of us to get into its bottom block: that's how high the jump
        // really has to go (from beside a column two high, it's a jump on the level)
        int climb = intoWater ? Math.min(dy, waterBottom(context, x + dx, y + dy, z + dz) - 1 - y) : dy;
        if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) {
            // next door is the grid moves' business, but for a step up onto (or off) something they won't stand on
            if ((fromWater || intoWater) && (dx != 0 || dz != 0) && dy >= -2 && (dy <= MAX_UP || waterUp) && context.isLoaded(x + dx, z + dz)) {
                return intoWater || destOk(context, x + dx, y + dy, z + dz);
            }
            return !NO_STEPS && (dx != 0 || dz != 0) && dy >= -1 && !upTooFar(context, x, y, z, dx, dy, dz) && oddStep(context, x, y, z, x + dx, y + dy, z + dz)
                    && context.isLoaded(x + dx, z + dz) && destOk(context, x + dx, y + dy, z + dz);
        }
        if ((climb > 0 ? upTooFar(context, x, y, z, dx, climb, dz) : climb != dy ? false : upTooFar(context, x, y, z, dx, dy, dz)) && !waterUp
                || dy < -maxDown(context) || -dy > context.maxFallHeightNoWater && !intoWater) {
            return false;
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
        boolean grab = grabCell(context, tx, ty, tz) || intoWater;
        // a ladder only has to be touched, not stood on: a bit further than a landing (water the same, it catches us)
        // (up a cell, but onto a floor no more than half a block higher: a stair's low step, a slab, as far as one on the level)
        int reachDy = intoWater ? climb : dy > 0 && rise(context, x, y, z, tx, ty, tz) <= 0.5 ? 0 : dy;
        double reach = reach(context, reachDy) + (grab ? GRAB_EXTRA : 0);
        // a bounce goes up as high as it came down from, so a ladder (caught on the way up) can be a block over src too
        if (dist > reach && (dy > 0 && !grab || dist > BOUNCE_REACH || !bounceOnTheWay(context, x, y, z, dx, dz, dist))
                && !(dy <= 0 && iceRunway(context, x, y, z, dx, dz, dist) >= ICE_RUNWAY)) {
            return false;
        }
        // the line out has to leave src over a hole, or it's a jump from some other node (or no jump at all). a ladder round
        // the end of a wall (a ladder neo) has the wall right there instead
        double ux = dx / dist, uz = dz / dist;
        int ox = Mth.floor(x + 0.5 + ux * 0.85), oz = Mth.floor(z + 0.5 + uz * 0.85);
        if (!fromWater && !hole(context, ox, y, oz) && !(grab && !MovementHelper.fullyPassable(context, ox, y + 1, oz))
                && neoSide(context, x, y, z, dx, dy, dz) == null && cornerVia(context, x, y, z, dx, dy, dz) == null) {
            return false;
        }
        if (grab) {
            return true;
        }
        return destOk(context, tx, ty, tz);
    }

    // the lowest block of the column of water whose top is tx ty tz
    static int waterBottom(CalculationContext context, int tx, int ty, int tz) {
        int by = ty;
        while (by > ty - 8 && water(context.get(tx, by - 1, tz))) {
            by--;
        }
        return by;
    }

    // somewhere to stand in tx ty tz
    public static boolean destOk(CalculationContext context, int tx, int ty, int tz) {
        return (jumpFloor(context, tx, ty - 1, tz) || rimNode(context, tx, ty, tz))
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

    // the top of a column of water (a lone water block, the single blocks of a water ladder): somewhere to jump into, swim up
    // and jump out of the top of. room over it for the head, and not a pool (water next to it all round is swimming, not this)
    public static boolean waterCell(CalculationContext context, int x, int y, int z) {
        if (NO_WATER || !water(context.get(x, y, z)) || water(context.get(x, y + 1, z)) || !MovementHelper.fullyPassable(context, x, y + 1, z)) {
            return false;
        }
        int wet = 0;
        for (int d = 0; d < 4; d++) {
            if (water(context.get(x + DIR_X[d], y, z + DIR_Z[d]))) {
                wet++;
            }
        }
        return wet < 2;
    }

    static final boolean NO_WATER = System.getenv("SOPRANO_NO_WATER") != null;
    // how far up the water cell a jump out of it starts (where a fall into one ends up, give or take)
    static final double WATER_START = 0.2;

    private static boolean water(BlockState state) {
        return baritone.pathing.sim.WorldCollision.isWaterState(state);
    }

    // the landing a node is: a ladder to grab, or a block to stand on
    public static Landing landing(CalculationContext context, BetterBlockPos dest) {
        if (grabCell(context, dest.x, dest.y, dest.z)) {
            return Landing.grab(dest.x, dest.y, dest.z);
        }
        if (waterCell(context, dest.x, dest.y, dest.z)) {
            return Landing.swim(dest.x, dest.y, dest.z, water(context.get(dest.x, dest.y - 1, dest.z)));
        }
        BlockState floor = context.get(dest.x, dest.y - 1, dest.z);
        Landing land = MovementHelper.isHalfFloor(floor) ? Landing.slab(dest.x, dest.y, dest.z)
                : stepped(floor) ? Landing.step(dest.x, dest.y, dest.z) : new Landing(dest.x, dest.y, dest.z);
        // a floor along one side of the cell only (the top of a ladder), or none but the rim of a block next door: come down
        // on that, not on the air in the middle
        if (MovementHelper.isClimbable(floor.getBlock()) || !(floor.getBlock() instanceof net.minecraft.world.level.block.StairBlock)
                && !MovementHelper.canWalkOn(context, dest.x, dest.y - 1, dest.z, floor)) {
            double[] at = standAt(context, dest.x, dest.y, dest.z);
            if (at[0] != 0.5 || at[1] != 0.5) {
                land = land.aimed(at[0], at[1]);
            }
        }
        return land;
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
            // the top of a ladder with no more ladder over it: a plate along one side of the cell to stand on (parkour maps
            // hang single ladders in the air for that). a vine has nothing to stand on
            return top(context, x, y, z, state) > 0.75 && !MovementHelper.isClimbable(context.get(x, y + 1, z).getBlock());
        }
        if (baritone.api.utils.IPlayerContext.smallFloor(state.getBlock())) {
            return true; // a head, a flower pot..., stood on like a bottom slab (see MovementHelper.isHalfFloor)
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
        Landing land = landing(context, dest);
        if (waterCell(context, src.x, src.y, src.z)) {
            // floating in the water block, about where a fall into it ends up: up it and out of the top, the rule's job
            sim.x = src.x + 0.5;
            sim.y = src.y + WATER_START;
            sim.z = src.z + 0.5;
            JumpPolicy.Rollout r = JumpPolicy.forgiving(sim, land);
            if (r == null || r.ticks() == 0) {
                return null;
            }
            double ddx = dest.x - src.x, ddz = dest.z - src.z;
            double cost = context.biasJump(Math.max(r.ticks(), Math.sqrt(ddx * ddx + ddz * ddz) * ActionCosts.SPRINT_ONE_BLOCK_COST) + context.jumpPenalty);
            return new Jump(src, dest, cost, r.variant(), List.copyOf(r.states()), null);
        }
        double[] at = standAt(world, src.x, src.y, src.z);
        double floor = at[2];
        if (Double.isNaN(floor)) {
            return null;
        }
        sim.x = src.x + at[0];
        sim.y = floor;
        sim.z = src.z + at[1];
        sim.vy = -0.0784; // what gravity leaves someone standing on the floor
        sim.onGround = true;
        double dx = dest.x - src.x, dz = dest.z - src.z;
        if (samePillar(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z)) {
            // round the post to the other side of the pillar, on foot
            TungstenSearch.Plan walk = RimWalk.plan(world, src, floor, land);
            if (walk != null) {
                return new Jump(src, dest, context.biasJump(walk.ticks()), JumpPolicy.Variant.EDGE, List.copyOf(walk.states()), walk);
            }
        }
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
            long rw0 = PlayerSim.ticks();
            TungstenSearch.Plan walk = RimWalk.plan(world, src, floor, land);
            if (PROFILE) { PROFILE_PARTS[0] += PlayerSim.ticks() - rw0; PROFILE_PARTS_N[0]++; }
            if (walk != null) {
                double cost = context.biasJump(Math.max(walk.ticks(), straight));
                return new Jump(src, dest, cost, JumpPolicy.Variant.EDGE, List.copyOf(walk.states()), walk);
            }
        }
        long fg0 = PlayerSim.ticks();
        boolean ruleOnly = at[3] != 0 || land.ax() != 0.5 || land.az() != 0.5;
        // (behind a wall, not even then: the rule's neos look ahead every tick, a hundred times the work of a jump, and
        // a walled map has rims round every post. a search spent minutes in them)
        // (unless somebody's made this one: a jump on a remembered trail is worth the rule's look, wall or no wall, and the
        // rule's last resort too, a jump that only just lands: the player made it)
        boolean onTrail = baritone.behavior.RouteMemory.trailJump(src.x, src.y, src.z, dest.x, dest.y, dest.z);
        JumpPolicy.Rollout r = behindWall && !onTrail ? null : onTrail ? JumpPolicy.forgivingOrRisky(sim, land) : JumpPolicy.forgiving(sim, land);
        if (PROFILE) {
            boolean neoV = JumpPolicy.variants(sim, land) == JumpPolicy.NEO_VARIANTS;
            int part = (r != null ? 1 : 2) + (neoV ? 2 : 0);
            PROFILE_PARTS[part] += PlayerSim.ticks() - fg0;
            PROFILE_PARTS_N[part]++;
        }
        if (r != null) {
            // a neo of the rule's (aimed off the line round a wall) is the last resort, behind MovementNeo's tables when it
            // has one: it's the touchiest thing the rule does
            double aimed = (r.variant().aim() != 0F ? NEO_COST : 1) * (JumpPolicy.TIGHT.equals(r.why()) ? TIGHT_COST : 1)
                    * (JumpPolicy.RISKY.equals(r.why()) ? RISKY_COST : 1);
            double cost = context.biasJump(Math.max(r.ticks(), straight) * aimed + context.jumpPenalty);
            return new Jump(src, dest, cost, r.variant(), List.copyOf(r.states()), null);
        }
        if (ruleOnly) {
            // to or from a rim, or a floor along one side of its cell: the rule's jumps only. the background searches (neos,
            // round corners, ledge chains) would be asked about every rim round every post on a walled map, and there are
            // hundreds of those, the queue would never get to the ones the path is waiting on
            return null;
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
    // a jump that only lands on the edge, or only with the aim spot on (JumpPolicy.forgivingOrRisky): the way when there's
    // no other
    static final double RISKY_COST = 3;

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
    static double floorTop(CollisionProvider world, int x, int y, int z) {
        return stand(world::boxes, x, y, z)[2];
    }

    // where in cell x y z to stand, as offsets in the cell: {ax, az}
    public static double[] standAt(CollisionProvider world, int x, int y, int z) {
        return stand(world::boxes, x, y, z);
    }

    // the same from the planner's blocks (any thread: no collision cache of anyone's)
    static double[] standAt(CalculationContext context, int x, int y, int z) {
        return stand(cells(context), x, y, z);
    }

    // a node with nothing under it to stand on, but the rim of the block next door: a block with a post on it (a fence, a
    // wall, bars) and nothing else, a pillar on a parkour map. you stand on the strip of its top outside the post, the middle
    // of you over the air in this cell, which is the cell Baritone counts you in (IPlayerContext.playerFeet). such a node
    // only joins Tungsten jumps (and walks round the post to another of its rims, RimWalk), nothing on the grid steps off it
    static final boolean NO_RIMS = System.getenv("SOPRANO_NO_RIMS") != null, NO_STEPS = System.getenv("SOPRANO_NO_STEPS") != null;

    public static boolean rimNode(CalculationContext context, int x, int y, int z) {
        if (NO_RIMS || !context.allowTungstenJumps || MovementHelper.canWalkOn(context, x, y - 1, z) || jumpFloor(context, x, y - 1, z)
                || !MovementHelper.fullyPassable(context, x, y, z) || !MovementHelper.fullyPassable(context, x, y + 1, z)
                || !context.get(x, y, z).getFluidState().isEmpty()) {
            return false;
        }
        // (a quick look first: a post right next door, it's air nearly everywhere this gets asked)
        boolean any = false;
        for (int d = 0; d < 4 && !any; d++) {
            any = !MovementHelper.fullyPassable(context, x + DIR_X[d], y, z + DIR_Z[d]) && MovementHelper.canWalkOn(context, x + DIR_X[d], y - 1, z + DIR_Z[d]);
        }
        return any && stand(cells(context), x, y, z)[3] != 0;
    }

    static final int[] DIR_X = {1, -1, 0, 0}, DIR_Z = {0, 0, 1, -1};

    interface Cells {
        void boxes(int x, int y, int z, BoxList out);
    }

    static Cells cells(CalculationContext context) {
        return (cx, cy, cz, out) -> {
            BlockState state = context.get(cx, cy, cz);
            if (state.isAir()) {
                return;
            }
            VoxelShape shape;
            synchronized (SHAPE_POS) {
                shape = state.getCollisionShape(context.bsi.access, SHAPE_POS.set(cx, cy, cz));
            }
            for (net.minecraft.world.phys.AABB a : shape.toAabbs()) {
                out.add(a.minX + cx, a.minY + cy, a.minZ + cz, a.maxX + cx, a.maxY + cy, a.maxZ + cz);
            }
        };
    }

    // how far in from the cell's sides a stand point stays (Landing.MARGIN, and a bit)
    static final double STAND_IN = 0.08;
    // a rim narrower than this isn't one (the player's half width over the edge, and this much clear of the post)
    static final double MIN_RIM = 0.15;

    // standing in cell x y z: {ax, az, floor height (NaN: nowhere), rim (1: on the rim of the block next door)}. the middle
    // of the cell over whatever's under it; or when the floor doesn't reach the middle (the top of a ladder, a plate along one
    // side; a head on a wall) the middle of what floor there is; or with nothing under the cell at all, the rim of a block next
    // door with a post on it
    static double[] stand(Cells world, int x, int y, int z) {
        BoxList boxes = new BoxList();
        world.boxes(x, y - 2, z, boxes);
        world.boxes(x, y - 1, z, boxes);
        world.boxes(x, y, z, boxes);
        double top = Double.NaN;
        for (int i = 0; i < boxes.size(); i++) {
            double t = boxes.get(i, 4);
            if (t <= y + 0.5 && (Double.isNaN(top) || t > top)) {
                top = t;
            }
        }
        // (down to a candle's top, three eighths up its cell: standing on one counts as the cell over it, IPlayerContext)
        if (!Double.isNaN(top) && top > y - 0.65) {
            double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int i = 0; i < boxes.size(); i++) {
                if (Math.abs(boxes.get(i, 4) - top) > 0.01) {
                    continue;
                }
                if (boxes.get(i, 0) <= x + 0.5 && boxes.get(i, 3) >= x + 0.5 && boxes.get(i, 2) <= z + 0.5 && boxes.get(i, 5) >= z + 0.5) {
                    return new double[]{0.5, 0.5, top, 0};
                }
                x0 = Math.min(x0, boxes.get(i, 0));
                z0 = Math.min(z0, boxes.get(i, 2));
                x1 = Math.max(x1, boxes.get(i, 3));
                z1 = Math.max(z1, boxes.get(i, 5));
            }
            return new double[]{Mth.clamp((x0 + x1) / 2 - x, STAND_IN, 1 - STAND_IN), Mth.clamp((z0 + z1) / 2 - z, STAND_IN, 1 - STAND_IN), top, 0};
        }
        // nothing under us: the rim of a block next door, the side of it facing us. its top has to be right at y and reach
        // the side it shares with us, and whatever stands on it (the post) has to leave MIN_RIM of it clear on that side, all
        // the way up a player's height
        double[] best = null;
        double bestRoom = MIN_RIM;
        for (int d = 0; d < 4; d++) {
            int px = x + DIR_X[d], pz = z + DIR_Z[d];
            BoxList under = new BoxList(), post = new BoxList();
            world.boxes(px, y - 1, pz, under);
            boolean floor = false;
            for (int i = 0; i < under.size(); i++) {
                // reaching our side, and the middle of it along that side
                boolean reaches = DIR_X[d] == 1 ? under.get(i, 0) <= px + 0.01 : DIR_X[d] == -1 ? under.get(i, 3) >= px + 0.99
                        : DIR_Z[d] == 1 ? under.get(i, 2) <= pz + 0.01 : under.get(i, 5) >= pz + 0.99;
                boolean along = DIR_X[d] != 0 ? under.get(i, 2) <= pz + 0.3 && under.get(i, 5) >= pz + 0.7 : under.get(i, 0) <= px + 0.3 && under.get(i, 3) >= px + 0.7;
                if (reaches && along && Math.abs(under.get(i, 4) - y) < 0.01) {
                    floor = true;
                }
            }
            if (!floor) {
                continue;
            }
            world.boxes(px, y, pz, post);
            world.boxes(px, y + 1, pz, post);
            if (post.size() == 0) {
                continue; // nothing on it: that's a node of its own, not a rim
            }
            double room = 1;
            for (int i = 0; i < post.size(); i++) {
                if (post.get(i, 1) >= y + 1.8) {
                    continue;
                }
                double r = DIR_X[d] == 1 ? post.get(i, 0) - px : DIR_X[d] == -1 ? px + 1 - post.get(i, 3)
                        : DIR_Z[d] == 1 ? post.get(i, 2) - pz : pz + 1 - post.get(i, 5);
                room = Math.min(room, r);
            }
            if (room >= 1 || room < bestRoom) {
                continue; // nothing in the way after all (a node of its own), or not enough rim
            }
            bestRoom = room;
            // the middle of us between over the edge by our half width and up against the post, a bit off the post
            double in = 1 - 0.3 + Math.min(room, 0.3) / 2;
            double ax = DIR_X[d] == 1 ? in : DIR_X[d] == -1 ? 1 - in : 0.5;
            double az = DIR_Z[d] == 1 ? in : DIR_Z[d] == -1 ? 1 - in : 0.5;
            best = new double[]{ax, az, y, 1};
        }
        return best != null ? best : new double[]{0.5, 0.5, Double.NaN, 0};
    }
}
