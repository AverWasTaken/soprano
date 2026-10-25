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
import baritone.pathing.movement.movements.PendingTables;
import baritone.pathing.sim.CollisionProvider;
import baritone.pathing.sim.PlayerSim;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

// the neos the rule can't do: round a wall three thick (one down, the only way there's air time for it), round a two thick
// one off a one block runway, two of them back to back with a one block pad between (no stopping on the pad, the speed off
// the first is what makes the second). the full search finds those, but it takes seconds, so it runs in the background:
// the planner gets "don't know yet" (PendingTables) and the answer the next time it asks.
//
// the search has no idea how wobbly the aim will be, it finds one exact flight, often with a hundredth of a block to spare.
// so it searches a world with the walls fattened and the floors thinned (InflatedCollision), and whatever it finds gets
// flown here a few times over with the aim jittering the way LookBehavior jitters it, tracked the way the executor tracks
// it (JumpPilot). a flight that doesn't land every time isn't offered
public final class NeoSearches {

    // the margins tried, in the order they most often turn up something that flies, and whether the search is told to go by
    // way of the row beside the wall: much quicker when it works, but the flight it finds is sometimes not one that flies
    // every time, and then the plain search often has one that does
    // never 0, and a couple of hundredths when it can: a flight that brushes a wall in the plan is one the real game pushes
    // off it differently (the sprint after a bump goes by the camera, see JumpPolicy.bumpsAWall), and the real player is
    // never exactly where the plan said
    static final double[] MARGINS = {0.02, 0.01, 0.01, 0.02};
    static final boolean[] VIA = {true, true, false, false};
    // sim ticks per search, a second or two each: the search by way of the side is quick when it works at all, the plain
    // one needs more room
    static final long[] BUDGETS = {1_000_000, 1_000_000, 1_500_000, 1_500_000};
    static final int FLIGHTS = 16;
    // what the jitter flights call a wobbly aim: LookBehavior's randomLooking113 is up to a degree either side
    static final double JITTER = 2.0;

    record Key(long src, long dest, long hash, int kind) {}

    // what a job is: a neo (the full search), two neos in one flight, or a chain of walks and jumps by way of ledges
    // (or one by way of slime beside the line, the full search too: BOUNCE)
    static final int NEO = 0, COMBO = 1, LEDGES = 2, LEAP = 3, BOUNCE = 4;

    static final boolean LOG = System.getenv("SOPRANO_NEO_LOG") != null;
    static final boolean FLY_DEBUG = System.getenv("SOPRANO_FLY_DEBUG") != null;

    private static final Object PENDING = new Object(), NONE = new Object();
    private static final Map<Key, Object> RESULTS = new ConcurrentHashMap<>();
    private static final List<Job> QUEUE = new ArrayList<>();
    private static int workers;
    // one thread, at the lowest priority: these are seconds of work each, and the game needs the cores more
    private static final int MAX_WORKERS = 1;
    // a job further than this from the player when its turn comes is dropped (asked again if a path gets near it again):
    // a long #goto asks about every wall along the way, and working them all out up front is minutes of a busy core
    private static final double MAX_JOB_DISTANCE = 48;
    // the blocks' hash per src and dest, worked out at most this often: A* asks about the same pair over and over
    private static final long HASH_MS = 3000;
    private record Hashed(long hash, long at) {}
    private static final Map<Long, Hashed> HASHES = new ConcurrentHashMap<>();
    // where the player is: the jobs nearest it go first, they're the ones a path is about to want
    private static volatile double focusX = Double.NaN, focusY = Double.NaN, focusZ = Double.NaN;

    private record Job(Key key, SnapshotCollision world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Region region,
                       double[] via, int kind) {
        boolean combo() {
            return kind == COMBO;
        }
    }

    // how long the searches to each landing (its long hash) have spent coming up with nothing lately: a landing no way to
    // has been found to from one spot is usually one there's no way to from the spots next to it either, and each of those
    // is seconds of search. so the more time gone on it the further back its jobs go, and the others (a jump further on
    // that the path is waiting for) get a turn
    private static final Map<Long, Long> FAILED_MS = new ConcurrentHashMap<>();
    // ms of fruitless search that count as a block further from the player (squared, the way pick measures)
    private static final double FAILED_MS_PER_SQ_BLOCK = 10;

    private NeoSearches() {}

    public static void focus(double x, double y, double z) {
        double dx = x - focusX, dz = z - focusZ;
        boolean moved = Double.isNaN(dx + dz) || dx * dx + dz * dz > MAX_JOB_DISTANCE * MAX_JOB_DISTANCE;
        focusX = x;
        focusY = y;
        focusZ = z;
        if (moved) {
            FAILED_MS.clear();
            // somewhere else altogether (a teleport, a new level): what was waiting round the old place would hold up the
            // queue for minutes. dropped, and asked again if a path ever comes back there
            synchronized (QUEUE) {
                QUEUE.removeIf(job -> {
                    double jx = job.src().x + 0.5 - x, jz = job.src().z + 0.5 - z;
                    boolean far = jx * jx + jz * jz > MAX_JOB_DISTANCE * MAX_JOB_DISTANCE;
                    if (far) {
                        RESULTS.remove(job.key());
                    }
                    return far;
                });
            }
        }
    }

    // the searched plan from src to land, standing still at src's middle with the feet at floorY. null when there's none,
    // WAIT when there's none yet and it's being worked out (only when schedule, which is what queues it)
    // via: the point the swing round the wall goes through (see TungstenSearch's heuristic)
    static TungstenSearch.Plan lookup(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, double[] via,
                                      boolean combo, boolean schedule) {
        return lookup(world, src, floorY, land, via, combo ? COMBO : NEO, schedule);
    }

    static TungstenSearch.Plan lookup(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, double[] via,
                                      int kind, boolean schedule) {
        // a ledge chain looks at everything round src (Ledges keeps that for every landing it's asked about)
        TungstenSearch.Region region = kind == LEDGES
                ? TungstenSearch.Region.around(src.x + 0.5, src.z + 0.5, src.x + 0.5, src.z + 0.5, src.y - 6, Ledges.REACH + 1)
                : region(src, land);
        long srcKey = BetterBlockPos.longHash(src), destKey = BetterBlockPos.longHash(land.x(), land.y(), land.z());
        long pair = srcKey * 31 + destKey, now = System.currentTimeMillis();
        Hashed hashed = HASHES.get(pair);
        SnapshotCollision snap = null;
        if (hashed == null || now - hashed.at() > HASH_MS) {
            // (just the hash: a whole snapshot each time only to hash it was most of what A* threw away)
            hashed = new Hashed(snapshotHash(world, src, land, region), now);
            if (HASHES.size() > 20_000) {
                HASHES.clear();
            }
            HASHES.put(pair, hashed);
        }
        Key key = new Key(srcKey, destKey, hashed.hash(), kind);
        Object known = RESULTS.get(key);
        if (known instanceof TungstenSearch.Plan plan) {
            return plan;
        }
        if (!schedule || known == NONE) {
            return null;
        }
        if (known == PENDING) {
            // still being worked out: say so again, so whoever is waiting on it knows it's still worth waiting for
            PendingTables.missed(key, () -> RESULTS.get(key) != PENDING);
            return WAIT;
        }
        if (RESULTS.putIfAbsent(key, PENDING) == null) {
            PendingTables.missed(key, () -> RESULTS.get(key) != PENDING);
            if (snap == null) {
                snap = snapshot(world, src, land, region);
            }
            synchronized (QUEUE) {
                QUEUE.add(new Job(key, snap, src, floorY, land, region, via, kind));
                if (QUEUE.size() > MAX_QUEUE) {
                    // too many waiting (a long #goto asks about every wall along the way, and each holds a copy of the blocks
                    // round it): the one that would go last goes now, and gets asked again when a path comes near it
                    int worst = pick(false);
                    RESULTS.remove(QUEUE.remove(worst).key());
                }
                if (workers < MAX_WORKERS) {
                    workers++;
                    Thread t = new Thread(NeoSearches::work, "Soprano neo search");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    t.start();
                }
            }
        }
        return WAIT;
    }

    private static SnapshotCollision snapshot(CollisionProvider world, BetterBlockPos src, Landing land, TungstenSearch.Region region) {
        int minY = Math.min(src.y, land.y()) - 5, maxY = Math.max(src.y, land.y()) + 4;
        return new SnapshotCollision(world, Mth.floor(region.minX()) - 2, minY, Mth.floor(region.minZ()) - 2,
                Mth.floor(region.maxX()) + 2, maxY, Mth.floor(region.maxZ()) + 2);
    }

    private static long snapshotHash(CollisionProvider world, BetterBlockPos src, Landing land, TungstenSearch.Region region) {
        int minY = Math.min(src.y, land.y()) - 5, maxY = Math.max(src.y, land.y()) + 4;
        return SnapshotCollision.hashOf(world, Mth.floor(region.minX()) - 2, minY, Mth.floor(region.minZ()) - 2,
                Mth.floor(region.maxX()) + 2, maxY, Mth.floor(region.maxZ()) + 2);
    }

    // at most this many worked out searches kept
    private static final int MAX_RESULTS = 4000;

    // at most this many jobs waiting
    private static final int MAX_QUEUE = 200;

    // what lookup says for "being worked out"
    static final TungstenSearch.Plan WAIT = new TungstenSearch.Plan(List.of(), List.of(), 0, 0);

    // the middle of the row beside the line, halfway along
    public static double[] via(BetterBlockPos src, Landing land, int sideX, int sideZ) {
        return new double[]{(src.x + 0.5 + land.centerX()) / 2 + sideX, (src.z + 0.5 + land.centerZ()) / 2 + sideZ};
    }

    public static TungstenSearch.Region region(BetterBlockPos src, Landing land) {
        return TungstenSearch.Region.around(src.x + 0.5, src.z + 0.5, land.centerX(), land.centerZ(), Math.min(src.y, land.y()) - 4, 3);
    }

    // the job to do next (first true), or the one to do last (false). QUEUE held
    private static int pick(boolean first) {
        int best = 0;
        double bestD = first ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (int i = 0; i < QUEUE.size(); i++) {
            BetterBlockPos s = QUEUE.get(i).src();
            double dx = s.x + 0.5 - focusX, dy = s.y - focusY, dz = s.z + 0.5 - focusZ;
            // a combo (two neos in one flight) only matters when the second one can't go from standing on the pad,
            // so it waits until the single ones are done, and then it may not be needed at all
            // and the ones going away from the player (onwards) before the ones coming back towards it: a path
            // forwards needs the first kind, the second kind is A* looking behind itself
            Landing l = QUEUE.get(i).land();
            double lx = l.centerX() - focusX, lz = l.centerZ() - focusZ;
            boolean back = !Double.isNaN(dx + dz) && lx * lx + lz * lz < dx * dx + dz * dz;
            // near the player first, a ledge chain as if it were ten blocks further off (they're the way when nothing
            // else is, and a lot more of them get asked about), going back as if thirty
            boolean far = Double.isNaN(dx + dy + dz);
            Long failed = FAILED_MS.get(BetterBlockPos.longHash(l.x(), l.y(), l.z()));
            double d = (failed == null ? 0 : failed / FAILED_MS_PER_SQ_BLOCK) + (QUEUE.get(i).combo() ? 1.0E9 : 0) + (far ? i + (QUEUE.get(i).kind() == LEDGES ? 1.0E6 : 0) + (back ? 1.0E7 : 0)
                    : dx * dx + dy * dy + dz * dz + (QUEUE.get(i).kind() == LEDGES ? 100 : 0) + (back ? 900 : 0));
            if (QUEUE.get(i).kind() == LEAP && !far && !back) {
                // a leap along the remembered way (somebody went that way) near us: before the rest, it's the way on.
                // (and it's the last to go when the queue's too long). not one coming back towards us: that's A* looking
                // behind itself along the trail, and a chess level's dozens of those held up the jump on for minutes
                d -= 1.0E5;
            }
            if (first ? d < bestD : d >= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static void work() {
        while (true) {
            Job job;
            synchronized (QUEUE) {
                if (QUEUE.isEmpty()) {
                    workers--;
                    return;
                }
                int best = pick(true);
                job = QUEUE.remove(best);
            }
            double fx = job.src().x + 0.5 - focusX, fz = job.src().z + 0.5 - focusZ;
            if (!Double.isNaN(fx + fz) && fx * fx + fz * fz > MAX_JOB_DISTANCE * MAX_JOB_DISTANCE) {
                RESULTS.remove(job.key()); // not now: whoever asks again when it's nearer gets it queued again
                continue;
            }
            TungstenSearch.Plan plan = null;
            long t0 = System.currentTimeMillis();
            try {
                plan = job.kind() == COMBO ? solveCombo(job.world(), job.src(), job.floorY(), job.land(), job.region())
                        : job.kind() == LEAP ? solveLeap(job.world(), job.src(), job.floorY(), job.land(), job.region())
                        : job.kind() == LEDGES ? Ledges.plan(job.world(), job.src(), job.floorY(), job.land())
                        : solve(job.world(), job.src(), job.floorY(), job.land(), job.region(), job.via());
            } catch (Throwable t) {
                // a search that fell over is a search that found nothing
            }
            if (LOG) {
                System.out.println("[neo search] " + (job.kind() == LEDGES ? "(ledges) " : "") + job.src() + " -> " + job.land() + ": " + (plan == null ? "none" : plan.ticks() + " ticks")
                        + " in " + (System.currentTimeMillis() - t0) + " ms, " + QUEUE.size() + " queued");
            }
            if (plan == null) {
                FAILED_MS.merge(BetterBlockPos.longHash(job.land().x(), job.land().y(), job.land().z()), System.currentTimeMillis() - t0, Long::sum);
            }
            if (RESULTS.size() > MAX_RESULTS) {
                // (a long session, a big map: every plan worked out is kept, each with all its ticks. past this many they go,
                // and get worked out again if a path ever wants one again)
                RESULTS.entrySet().removeIf(e -> e.getValue() != PENDING);
            }
            RESULTS.put(job.key(), plan == null ? NONE : plan);
        }
    }

    // the search, margin by margin, and the jitter flights. public for the tests
    public static TungstenSearch.Plan solve(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Region region) {
        return solve(world, src, floorY, land, region, null);
    }

    public static TungstenSearch.Plan solve(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Region region,
                                            double[] via) {
        int minFeet = Math.min(src.y, land.y()), maxFeet = Math.max(src.y, land.y());
        // (a walk round it on the level is RimWalk's, tried before anything gets this far)
        for (int attempt = 0; attempt < MARGINS.length; attempt++) {
            double m = MARGINS[attempt];
            InflatedCollision fat = new InflatedCollision(world, m, minFeet, maxFeet);
            TungstenSearch.Plan plan = TungstenSearch.search(standing(fat, src, floorY), land, region, BUDGETS[attempt], null, floorY,
                    VIA[attempt] ? via : null);
            if (plan == null || plan.ticks() == 0) {
                if (LOG) {
                    System.out.println("[neo search]   margin " + m + (VIA[attempt] && via != null ? " via" : "") + ": nothing");
                }
                continue;
            }
            int landed = 0;
            for (long seed = 0; seed < FLIGHTS; seed++) {
                if (flyFrom(world, src, floorY, land, plan, seed, JITTER) == null) {
                    landed++;
                } else {
                    break;
                }
            }
            if (LOG) {
                PlayerSim a = standing(fat, src, floorY), b = standing(world, src, floorY);
                System.out.println("[neo search]   start fat " + a.x + " " + a.z + " real " + b.x + " " + b.z + " floorY " + floorY);
                System.out.println("[neo search]   margin " + m + (VIA[attempt] && via != null ? " via" : "") + ": " + plan.ticks() + " ticks, " + landed + "/" + FLIGHTS + " flights"
                        + (landed < FLIGHTS ? " (" + flyFrom(world, src, floorY, land, plan, landed, JITTER) + ")" : ""));
            }
            if (landed == FLIGHTS) {
                return plan;
            }
        }
        return null;
    }

    // a leap along the remembered way (TungstenJumps.trailLeap): the plain search, no margin (a start on the rim of a block
    // round a post is right up against it, and a margin has it stuck in the post), a bigger budget (a run, a touchdown on
    // the way and a second jump are a long plan), and it has to stand most of the wobbly flights, not all: somebody's made
    // this one, and a miss is a put back
    static final long LEAP_BUDGET = 3_000_000;
    static final int LEAP_LANDED = 12;

    public static TungstenSearch.Plan solveLeap(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Region region) {
        // a run at it hopping off whatever's on the way (HopChain) first: a few thousand ticks of sim, where the search is
        // millions, and the search doesn't find those
        TungstenSearch.Plan hop = HopChain.plan(world, src, floorY, land);
        if (hop != null) {
            int landed = 0;
            for (long seed = 0; seed < FLIGHTS; seed++) {
                if (flyFrom(world, src, floorY, land, hop, seed, JITTER) == null) {
                    landed++;
                }
            }
            if (LOG) {
                System.out.println("[neo search]   leap by hops: " + hop.ticks() + " ticks, " + landed + "/" + FLIGHTS + " flights");
            }
            if (landed >= LEAP_LANDED) {
                return hop;
            }
        }
        TungstenSearch.Plan plan = TungstenSearch.search(standing(world, src, floorY), land, region, LEAP_BUDGET, null);
        if (plan == null || plan.ticks() == 0) {
            if (LOG) {
                System.out.println("[neo search]   leap: nothing");
            }
            return null;
        }
        int landed = 0;
        for (long seed = 0; seed < FLIGHTS; seed++) {
            if (flyFrom(world, src, floorY, land, plan, seed, JITTER) == null) {
                landed++;
            }
        }
        if (LOG) {
            System.out.println("[neo search]   leap: " + plan.ticks() + " ticks, " + landed + "/" + FLIGHTS + " flights");
        }
        return landed >= LEAP_LANDED ? plan : null;
    }

    // two neos in one flight: only ever turned up by the plain search with no margin (and then flies fine, the pad in the middle
    // puts it back on track), so that's all it gets, it's a lot of searching for something that's usually not there
    static final long COMBO_BUDGET = 2_000_000;

    public static TungstenSearch.Plan solveCombo(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Region region) {
        TungstenSearch.Plan plan = TungstenSearch.search(standing(world, src, floorY), land, region, COMBO_BUDGET, null);
        if (plan == null || plan.ticks() == 0) {
            return null;
        }
        for (long seed = 0; seed < FLIGHTS; seed++) {
            if (flyFrom(world, src, floorY, land, plan, seed, JITTER) != null) {
                return null;
            }
        }
        return plan;
    }

    static boolean fliesEveryTime(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Plan plan) {
        return fliesEveryTime(world, src, floorY, land, plan, FLIGHTS);
    }

    static boolean fliesEveryTime(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Plan plan, int flights) {
        for (long seed = 0; seed < flights; seed++) {
            if (flyFrom(world, src, floorY, land, plan, seed, JITTER) != null) {
                return false;
            }
        }
        return true;
    }

    // fly from standing still at src
    static String flyFrom(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, TungstenSearch.Plan plan, long seed, double jitter) {
        PlayerSim sim = standing(world, src, floorY);
        return fly(sim, land, plan, sim.x, sim.z, seed, jitter);
    }

    // standing still at src: in its middle, or wherever in it the floor is (TungstenJumps.standAt)
    static PlayerSim standing(CollisionProvider world, BetterBlockPos src, double floorY) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        double[] at = TungstenJumps.standAt(world, src.x, src.y, src.z);
        sim.x = src.x + at[0];
        sim.y = floorY;
        sim.z = src.z + at[1];
        sim.vy = -0.0784;
        sim.onGround = true;
        return sim;
    }

    // the executor's pilot flying a searched plan against a player whose aim is off the way LookBehavior's is
    // (randomLooking113, up to a degree either side every tick, and the mouse's 0.15 degree steps). null when it lands
    public static String fly(PlayerSim real, Landing land, TungstenSearch.Plan plan, double startX, double startZ, long seed, double jitter) {
        Random rnd = new Random(seed);
        JumpPilot pilot = new JumpPilot(land, startX, startZ, JumpPolicy.Variant.EDGE);
        pilot.fallback(plan);
        float yaw = 0F;
        for (int t = 0; t < 400; t++) {
            JumpPilot.Decision d = pilot.tick(real);
            if (d.status() == JumpPilot.Status.DONE) {
                return null;
            }
            if (d.status() != JumpPilot.Status.PRESS) {
                return d.status() + " " + d.why() + " at " + SimState.of(real);
            }
            PlayerSim.Controls k = d.keys();
            if (FLY_DEBUG) {
                System.out.println("[fly] " + t + " " + SimState.of(real) + " " + k + " |" + pilot.lastWork + " variant " + pilot.variantNow());
            }
            double r = rnd.nextDouble() - 0.5;
            if (Math.abs(r) < 0.1) {
                r *= 4;
            }
            float desired = k.moveYaw() + (float) ((rnd.nextDouble() - 0.5) * 0.01 + r * jitter);
            yaw += Math.round(Mth.wrapDegrees(desired - yaw) / 0.15F) * 0.15F;
            if (!Keys.step(real, new PlayerSim.Controls(k.forward(), k.left(), k.jump(), k.sprint(), yaw, yaw))) {
                return "sim: " + real.failure();
            }
            if (real.y < Math.min(land.y(), floorOf(plan)) - 3) {
                return "fell at " + SimState.of(real);
            }
        }
        return "timeout";
    }

    private static double floorOf(TungstenSearch.Plan plan) {
        double low = Double.MAX_VALUE;
        for (SimState s : plan.states()) {
            low = Math.min(low, s.y);
        }
        return low;
    }
}
