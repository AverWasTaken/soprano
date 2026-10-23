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

    // sim ticks per search, a few seconds of it. most of these are found well inside it or not at all
    static final long BUDGET = 1_500_000;
    // the margins tried, in the order they most often turn up something that flies, and whether the search is told to go by
    // way of the row beside the wall: much quicker when it works, but the flight it finds is sometimes not one that flies
    // every time, and then the plain search often has one that does
    // never 0, and a couple of hundredths when it can: a flight that brushes a wall in the plan is one the real game pushes
    // off it differently (the sprint after a bump goes by the camera, see JumpPolicy.bumpsAWall), and the real player is
    // never exactly where the plan said
    static final double[] MARGINS = {0.02, 0.02, 0.01, 0.01};
    static final boolean[] VIA = {true, false, true, false};
    static final int FLIGHTS = 16;
    // what the jitter flights call a wobbly aim: LookBehavior's randomLooking113 is up to a degree either side
    static final double JITTER = 2.0;

    record Key(long src, long dest, long hash) {}

    static final boolean LOG = System.getenv("SOPRANO_NEO_LOG") != null;

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
                       double[] via, boolean combo) {}

    private NeoSearches() {}

    public static void focus(double x, double y, double z) {
        focusX = x;
        focusY = y;
        focusZ = z;
    }

    // the searched plan from src to land, standing still at src's middle with the feet at floorY. null when there's none,
    // WAIT when there's none yet and it's being worked out (only when schedule, which is what queues it)
    // via: the point the swing round the wall goes through (see TungstenSearch's heuristic)
    static TungstenSearch.Plan lookup(CollisionProvider world, BetterBlockPos src, double floorY, Landing land, double[] via,
                                      boolean combo, boolean schedule) {
        TungstenSearch.Region region = region(src, land);
        long srcKey = BetterBlockPos.longHash(src), destKey = BetterBlockPos.longHash(land.x(), land.y(), land.z());
        long pair = srcKey * 31 + destKey, now = System.currentTimeMillis();
        Hashed hashed = HASHES.get(pair);
        SnapshotCollision snap = null;
        if (hashed == null || now - hashed.at() > HASH_MS) {
            snap = snapshot(world, src, land, region);
            hashed = new Hashed(snap.hash, now);
            if (HASHES.size() > 20_000) {
                HASHES.clear();
            }
            HASHES.put(pair, hashed);
        }
        Key key = new Key(srcKey, destKey, hashed.hash());
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
                QUEUE.add(new Job(key, snap, src, floorY, land, region, via, combo));
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

    // what lookup says for "being worked out"
    static final TungstenSearch.Plan WAIT = new TungstenSearch.Plan(List.of(), List.of(), 0, 0);

    // the middle of the row beside the line, halfway along
    public static double[] via(BetterBlockPos src, Landing land, int sideX, int sideZ) {
        return new double[]{(src.x + 0.5 + land.centerX()) / 2 + sideX, (src.z + 0.5 + land.centerZ()) / 2 + sideZ};
    }

    public static TungstenSearch.Region region(BetterBlockPos src, Landing land) {
        return TungstenSearch.Region.around(src.x + 0.5, src.z + 0.5, land.centerX(), land.centerZ(), Math.min(src.y, land.y()) - 4, 3);
    }

    private static void work() {
        while (true) {
            Job job;
            synchronized (QUEUE) {
                if (QUEUE.isEmpty()) {
                    workers--;
                    return;
                }
                int best = 0;
                double bestD = Double.MAX_VALUE;
                for (int i = 0; i < QUEUE.size(); i++) {
                    BetterBlockPos s = QUEUE.get(i).src();
                    double dx = s.x + 0.5 - focusX, dy = s.y - focusY, dz = s.z + 0.5 - focusZ;
                    // a combo (two neos in one flight) only matters when the second one can't go from standing on the pad,
                    // so it waits until the single ones are done, and then it may not be needed at all
                    double d = (QUEUE.get(i).combo() ? 1.0E9 : 0) + (Double.isNaN(dx + dy + dz) ? i : dx * dx + dy * dy + dz * dz);
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
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
                plan = job.combo() ? solveCombo(job.world(), job.src(), job.floorY(), job.land(), job.region())
                        : solve(job.world(), job.src(), job.floorY(), job.land(), job.region(), job.via());
            } catch (Throwable t) {
                // a search that fell over is a search that found nothing
            }
            if (LOG) {
                System.out.println("[neo search] " + job.src() + " -> " + job.land() + ": " + (plan == null ? "none" : plan.ticks() + " ticks")
                        + " in " + (System.currentTimeMillis() - t0) + " ms, " + QUEUE.size() + " queued");
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
        for (int attempt = 0; attempt < MARGINS.length; attempt++) {
            double m = MARGINS[attempt];
            InflatedCollision fat = new InflatedCollision(world, m, minFeet, maxFeet);
            TungstenSearch.Plan plan = TungstenSearch.search(standing(fat, src, floorY), land, region, BUDGET, null, floorY,
                    VIA[attempt] ? via : null);
            if (plan == null || plan.ticks() == 0) {
                if (LOG) {
                    System.out.println("[neo search]   margin " + m + (VIA[attempt] && via != null ? " via" : "") + ": nothing");
                }
                continue;
            }
            int landed = 0;
            for (long seed = 0; seed < FLIGHTS; seed++) {
                if (fly(standing(world, src, floorY), land, plan, src.x + 0.5, src.z + 0.5, seed, JITTER) == null) {
                    landed++;
                } else {
                    break;
                }
            }
            if (LOG) {
                System.out.println("[neo search]   margin " + m + (VIA[attempt] && via != null ? " via" : "") + ": " + plan.ticks() + " ticks, " + landed + "/" + FLIGHTS + " flights"
                        + (landed < FLIGHTS ? " (" + fly(standing(world, src, floorY), land, plan, src.x + 0.5, src.z + 0.5, landed, JITTER) + ")" : ""));
            }
            if (landed == FLIGHTS) {
                return plan;
            }
        }
        return null;
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
            if (fly(standing(world, src, floorY), land, plan, src.x + 0.5, src.z + 0.5, seed, JITTER) != null) {
                return null;
            }
        }
        return plan;
    }

    static PlayerSim standing(CollisionProvider world, BetterBlockPos src, double floorY) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = src.x + 0.5;
        sim.y = floorY;
        sim.z = src.z + 0.5;
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
