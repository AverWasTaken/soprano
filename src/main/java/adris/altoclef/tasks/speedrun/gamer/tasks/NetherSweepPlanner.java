package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.world.NetherComplexGrid;
import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

// the brain of the fortress search: which structure cell, which waypoint, what to do with what we saw. pure (no game, no
// minecraft types) so a fake "seen" oracle can drive it in a test. FindNetherStructureTask only walks where it says.
// everything durable goes into RunState (visited cells, bastions, fortress), so a relog does not walk the same cell twice
public final class NetherSweepPlanner {
    public enum Goal {
        FORTRESS,
        // a warped forest is where the endermen are, the sweep doubles as the search for one
        WARPED
    }

    public enum Sight {
        FORTRESS,
        BASTION,
        WARPED
    }

    public enum Kind {
        GOTO,
        FOUND,
        FAIL
    }

    public record Action(Kind kind, int x, int z, String why) {
    }

    // the two grid calls, behind an interface so tests do not depend on the real cell layout
    public interface Grid {
        List<Point> waypoints(Cell cell, int spacingChunks, int playerX, int playerZ);

        Optional<Cell> nextCell(int playerX, int playerZ, Set<String> visited);
    }

    public static final Grid REAL_GRID = new Grid() {
        @Override
        public List<Point> waypoints(Cell cell, int spacingChunks, int playerX, int playerZ) {
            return NetherComplexGrid.sweepWaypoints(cell, spacingChunks, playerX, playerZ);
        }

        @Override
        public Optional<Cell> nextCell(int playerX, int playerZ, Set<String> visited) {
            return NetherComplexGrid.nextCell(playerX, playerZ, visited);
        }
    };

    // what the player has actually SEEN (block tracker + SeenFilter in the game, a set in tests)
    public interface SightSource {
        Optional<RunState.Pos> nearestSeen(Sight sight);
    }

    public enum Look {
        // its chunks are not all in yet, we cannot tell: walk at the bare waypoint and ask again
        UNLOADED,
        FINE,
        // over lava, the nearest column with a floor is (x, z) of the spot
        SHIFTED,
        NOWHERE
    }

    public record Spot(Look look, int x, int z) {
    }

    // what is under a waypoint. the world half is NetherGround, tests hand in a fake
    public interface Ground {
        Spot look(int x, int z);
    }

    public static final Ground BLIND = (x, z) -> new Spot(Look.UNLOADED, x, z);

    // a second block of the same structure this close to one we know is the same structure
    private static final int SAME_STRUCTURE_BLOCKS = 64;
    // fortresses are big and spill over cell borders, a given up one covers about this much
    private static final int EXHAUSTED_BLOCKS = 192;
    // not asked for this long = something else had the wheel
    private static final double IDLE_SECONDS = 5;
    private static final int MAX_GAP_RESETS = 2;

    private final RunState state;
    private final NetherConfig cfg;
    private final Grid grid;
    private final int maxCells;

    private Cell cell;
    private List<Point> waypoints = List.of();
    private int index;
    private double waypointSince;
    // what we are walking at: the waypoint, or where it was moved to once its chunks loaded (decided once, then it sticks)
    private Point aim;
    private boolean judged;
    // best distance to the aim so far and the walking seconds since it last got 'waypointNoProgressBlocks' better
    private double bestDist = Double.NaN;
    private double stuckFor;
    private int gaps;
    // seconds between this step and the last one, 0 when something else had the wheel in between
    private double stepSeconds;
    // waypoints given up on or skipped, for the life of this planner: a cell picked up again never hands them back
    private final Set<Long> dead = new HashSet<>();
    private Ground ground = BLIND;
    private Consumer<String> log = line -> {
    };
    // cells started per goal: a fortress that took four cells must not eat the warped forest search's share
    private final int[] cellsStarted = new int[Goal.values().length];
    private double lastStepAt = Double.NaN;

    // read by the path predicate on the pathing threads, so snapshots and volatiles only
    private volatile int[] avoidXZ = new int[0];
    private volatile int playerX;
    private volatile int playerZ;

    public NetherSweepPlanner(RunState state, NetherConfig cfg, Grid grid, int maxCells) {
        this.state = state;
        this.cfg = cfg;
        this.grid = grid;
        this.maxCells = maxCells;
        refreshAvoid();
    }

    public NetherSweepPlanner(RunState state, NetherConfig cfg) {
        this(state, cfg, REAL_GRID, cfg.maxCells);
    }

    public void setGround(Ground ground) {
        this.ground = ground;
    }

    public void setLog(Consumer<String> log) {
        this.log = log;
    }

    public int cellsStarted(Goal goal) {
        return cellsStarted[goal.ordinal()];
    }

    public String currentCellKey() {
        return cell == null ? null : cell.key();
    }

    public boolean goalMet(Goal goal) {
        return goal == Goal.FORTRESS ? !state.fortress.isEmpty() : state.warpedForest != null;
    }

    // asks the source for each kind of sighting and records what it says. true = something new went into the RunState
    // (the caller saves)
    public boolean scan(SightSource source) {
        boolean changed = false;
        for (Sight sight : Sight.values()) {
            changed |= scan(source, sight);
        }
        return changed;
    }

    // one kind only, so a caller that rotates through them gives each its own slice of the seen filter's raycast budget
    public boolean scan(SightSource source, Sight sight) {
        Optional<RunState.Pos> pos = source.nearestSeen(sight);
        return pos.isPresent() && report(sight, pos.get());
    }

    public boolean report(Sight sight, RunState.Pos pos) {
        String key = NetherComplexGrid.cellOf(pos.x, pos.z).key();
        return switch (sight) {
            case FORTRESS -> reportFortress(pos, key);
            case BASTION -> reportBastion(pos, key);
            case WARPED -> reportWarped(pos);
        };
    }

    private boolean reportFortress(RunState.Pos pos, String key) {
        // a fortress we gave up on is still standing right there in view, do not fall for it again
        if (nearAny(state.fortressExhausted, pos, EXHAUSTED_BLOCKS)) {
            return false;
        }
        boolean fresh = !nearAny(state.fortress, pos, SAME_STRUCTURE_BLOCKS);
        if (fresh) {
            state.fortress.add(pos);
        }
        // the cell is spoken for either way: never sweep it again
        fresh |= state.fortressCells.add(key);
        state.visitedCells.add(key);
        dropCell(key);
        return fresh;
    }

    private boolean reportBastion(RunState.Pos pos, String key) {
        // a fortress and a bastion never share a cell, so a "bastion" block in a fortress cell is something else
        // (a ruined portal, a blackstone blob) and must not make us avoid the fortress we want
        if (state.fortressCells.contains(key)) {
            return false;
        }
        boolean fresh = !nearAny(state.bastion, pos, SAME_STRUCTURE_BLOCKS);
        if (fresh) {
            state.bastion.add(pos);
            refreshAvoid();
        }
        fresh |= state.bastionCells.add(key);
        state.visitedCells.add(key);
        dropCell(key);
        return fresh;
    }

    private boolean reportWarped(RunState.Pos pos) {
        if (state.warpedForest != null) {
            return false;
        }
        state.warpedForest = pos;
        return true;
    }

    // the spawner ran dry (or we could not reach it): forget the fortress it sits in so the search looks for another cell.
    // its cell stays visited and in fortressCells, we do not come back here. a second fortress we already saw stays known,
    // that one is the next stop. near = the spawner, null = we do not know where it was and every fortress goes
    public void giveUpFortress(RunState.Pos near) {
        if (near == null) {
            state.fortressExhausted.addAll(state.fortress);
            state.fortress.clear();
        } else {
            dropFortressesAround(near);
        }
        state.spawner = null;
        cell = null;
    }

    public void giveUpFortress() {
        giveUpFortress(null);
    }

    // everything inside the exhausted radius is the same fortress. a spawner further out than that from every sighting
    // (they are spots on the edge we looked at from) still costs us the closest one, or the dud would be picked again
    private void dropFortressesAround(RunState.Pos near) {
        long radius2 = (long) EXHAUSTED_BLOCKS * EXHAUSTED_BLOCKS;
        List<RunState.Pos> gone = new ArrayList<>();
        RunState.Pos closest = null;
        long best = Long.MAX_VALUE;
        for (RunState.Pos k : state.fortress) {
            long d = dist2(k.x, k.z, near.x, near.z);
            if (d < radius2) {
                gone.add(k);
            } else if (d < best) {
                best = d;
                closest = k;
            }
        }
        if (gone.isEmpty() && closest != null) {
            gone.add(closest);
        }
        state.fortress.removeAll(gone);
        state.fortressExhausted.addAll(gone);
    }

    // one decision. now is any monotonic clock in seconds
    public Action step(Goal goal, int px, int pz, double now) {
        playerX = px;
        playerZ = pz;
        // we were busy with something else (rods, a ghast, a barter) so the waypoint's clock did not run for real:
        // without this the first step back would time the waypoint out on the spot
        stepSeconds = Double.isNaN(lastStepAt) ? 0 : now - lastStepAt;
        if (stepSeconds > IDLE_SECONDS) {
            waypointSince = now;
            stepSeconds = 0;
            // and whatever dragged us off (a fight, a ghast run) moved us, so the old best distance means nothing either. the
            // stuck seconds start over for the first couple of gaps only, or a waypoint that keeps getting interrupted would
            // never be given up on
            bestDist = Double.NaN;
            if (++gaps <= MAX_GAP_RESETS) {
                stuckFor = 0;
            }
        }
        lastStepAt = now;
        if (goalMet(goal)) {
            return new Action(Kind.FOUND, px, pz, "found");
        }
        // every pass of this loop either returns or finishes a cell, and a finished cell is visited, so it ends
        while (true) {
            if (cell == null) {
                Action fail = startNextCell(goal, px, pz, now);
                if (fail != null) {
                    return fail;
                }
            }
            Point target = nextWaypoint(px, pz, now);
            if (target != null) {
                return new Action(Kind.GOTO, target.x(), target.z(), "cell " + cell.key());
            }
            state.visitedCells.add(cell.key());
            cell = null;
        }
    }

    // null = a cell is current now
    private Action startNextCell(Goal goal, int px, int pz, double now) {
        int started = cellsStarted[goal.ordinal()];
        if (started >= maxCells) {
            return new Action(Kind.FAIL, px, pz, "swept " + started + " cells and found nothing");
        }
        Optional<Cell> next = grid.nextCell(px, pz, state.visitedCells);
        if (next.isEmpty()) {
            return new Action(Kind.FAIL, px, pz, "ran out of cells");
        }
        cell = next.get();
        cellsStarted[goal.ordinal()]++;
        waypoints = grid.waypoints(cell, cfg.sweepSpacingChunks, px, pz);
        index = 0;
        waypointSince = now;
        freshWaypoint();
        return null;
    }

    private void freshWaypoint() {
        gaps = 0;
        judged = false;
        bestDist = Double.NaN;
        stuckFor = 0;
    }

    // how close counts as there. the walk uses the same radius as its goal, so it ends by itself right where the planner
    // moves on instead of getting replaced mid-path (that cancel is what dropped us off a ledge)
    public double arriveRadius() {
        return cfg.waypointArriveBlocks;
    }

    // the waypoint to walk to, or null when this cell is done. arrived, given up on, nowhere to stand and avoided ones are
    // skipped
    private Point nextWaypoint(int px, int pz, double now) {
        double arrive = cfg.waypointArriveBlocks;
        while (index < waypoints.size()) {
            Point w = waypoints.get(index);
            Point to = aimAt(w);
            if (to != null) {
                boolean arrived = dist2(px, pz, to.x(), to.z()) <= arrive * arrive;
                boolean timedOut = now - waypointSince >= cfg.waypointSeconds;
                boolean stuck = !arrived && !timedOut && noProgress(px, pz, to);
                if (!arrived && !timedOut && !stuck && !nearBastion(to.x(), to.z())) {
                    return to;
                }
                if (timedOut && !arrived) {
                    dead.add(key(w));
                    log.accept("sweep: gave up on " + to.x() + " " + to.z() + ", not there after "
                            + (int) cfg.waypointSeconds + " s (" + away(px, pz, to) + " away)");
                } else if (stuck) {
                    dead.add(key(w));
                    log.accept("sweep: gave up on " + to.x() + " " + to.z() + ", no closer in "
                            + (int) cfg.waypointNoProgressSeconds + " s (" + away(px, pz, to) + " away)");
                }
            }
            index++;
            waypointSince = now;
            freshWaypoint();
        }
        return null;
    }

    // where to walk for this waypoint. the column under it is looked at once, as soon as its chunks are in: until then the
    // bare waypoint, after that whatever was decided, even if the world changes under it. null = skip it
    private Point aimAt(Point w) {
        if (dead.contains(key(w))) {
            return null;
        }
        if (!judged) {
            aim = w;
            Spot spot = ground.look(w.x(), w.z());
            if (spot.look() == Look.UNLOADED) {
                return aim;
            }
            judged = true;
            if (spot.look() == Look.NOWHERE) {
                dead.add(key(w));
                log.accept("sweep: waypoint " + w.x() + " " + w.z() + " has nowhere to stand, skipping");
                return null;
            }
            if (spot.look() == Look.SHIFTED) {
                aim = new Point(spot.x(), spot.z());
                log.accept("sweep: waypoint " + w.x() + " " + w.z() + " is over lava, using " + aim.x() + " " + aim.z());
                // a new place to be, the old distances mean nothing
                bestDist = Double.NaN;
                stuckFor = 0;
            }
        }
        return aim;
    }

    // true once we have walked for waypointNoProgressSeconds without getting waypointNoProgressBlocks closer than our best.
    // only stepped seconds add up, so a fight or a ghast in the middle does not read as standing still
    private boolean noProgress(int px, int pz, Point to) {
        double d = Math.sqrt(dist2(px, pz, to.x(), to.z()));
        if (Double.isNaN(bestDist)) {
            // (first look: whoever cleared bestDist decided what happens to the stuck seconds, see step and freshWaypoint)
            bestDist = d;
            return false;
        }
        if (d <= bestDist - cfg.waypointNoProgressBlocks) {
            bestDist = d;
            stuckFor = 0;
            return false;
        }
        stuckFor += stepSeconds;
        return cfg.waypointNoProgressSeconds > 0 && stuckFor >= cfg.waypointNoProgressSeconds;
    }

    private static int away(int px, int pz, Point to) {
        return (int) Math.round(Math.sqrt(dist2(px, pz, to.x(), to.z())));
    }

    private static long key(Point p) {
        return ((long) p.x() << 32) ^ (p.z() & 0xffffffffL);
    }

    private void dropCell(String key) {
        if (cell != null && cell.key().equals(key)) {
            cell = null;
        }
    }

    // within the avoid radius of a bastion we saw
    public boolean nearBastion(int x, int z) {
        int[] xz = avoidXZ;
        double r = cfg.bastionAvoidRadius;
        for (int i = 0; i < xz.length; i += 2) {
            if (dist2(x, z, xz[i], xz[i + 1]) < r * r) {
                return true;
            }
        }
        return false;
    }

    // the path predicate: blocks near a bastion are off limits, unless we already stand inside one (a hard wall
    // around the player is a path that never exists)
    public boolean blocksPath(int x, int z) {
        return nearBastion(x, z) && !nearBastion(playerX, playerZ);
    }

    private void refreshAvoid() {
        int[] out = new int[state.bastion.size() * 2];
        int i = 0;
        for (RunState.Pos p : state.bastion) {
            out[i++] = p.x;
            out[i++] = p.z;
        }
        avoidXZ = out;
    }

    private static boolean nearAny(List<RunState.Pos> known, RunState.Pos pos, int radius) {
        for (RunState.Pos k : known) {
            if (dist2(k.x, k.z, pos.x, pos.z) < (long) radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static long dist2(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return dx * dx + dz * dz;
    }
}
