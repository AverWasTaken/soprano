package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.world.NetherComplexGrid;
import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    // a second block of the same structure this close to one we know is the same structure
    private static final int SAME_STRUCTURE_BLOCKS = 64;
    // fortresses are big and spill over cell borders, a given up one covers about this much
    private static final int EXHAUSTED_BLOCKS = 192;

    private final RunState state;
    private final NetherConfig cfg;
    private final Grid grid;
    private final int maxCells;

    private Cell cell;
    private List<Point> waypoints = List.of();
    private int index;
    private double waypointSince;
    private int cellsStarted;

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

    public int cellsStarted() {
        return cellsStarted;
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
            Optional<RunState.Pos> pos = source.nearestSeen(sight);
            if (pos.isPresent()) {
                changed |= report(sight, pos.get());
            }
        }
        return changed;
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

    // the spawner ran dry (or we could not reach it): forget this fortress so the search looks for another cell.
    // its cell stays visited and in fortressCells, we do not come back here
    public void giveUpFortress() {
        state.fortressExhausted.addAll(state.fortress);
        state.fortress.clear();
        state.spawner = null;
        cell = null;
    }

    // one decision. now is any monotonic clock in seconds
    public Action step(Goal goal, int px, int pz, double now) {
        playerX = px;
        playerZ = pz;
        if (goalMet(goal)) {
            return new Action(Kind.FOUND, px, pz, "found");
        }
        // every pass of this loop either returns or finishes a cell, and a finished cell is visited, so it ends
        while (true) {
            if (cell == null) {
                Action fail = startNextCell(px, pz, now);
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
    private Action startNextCell(int px, int pz, double now) {
        if (cellsStarted >= maxCells) {
            return new Action(Kind.FAIL, px, pz, "swept " + cellsStarted + " cells and found nothing");
        }
        Optional<Cell> next = grid.nextCell(px, pz, state.visitedCells);
        if (next.isEmpty()) {
            return new Action(Kind.FAIL, px, pz, "ran out of cells");
        }
        cell = next.get();
        cellsStarted++;
        waypoints = grid.waypoints(cell, cfg.sweepSpacingChunks, px, pz);
        index = 0;
        waypointSince = now;
        return null;
    }

    // the waypoint to walk to, or null when this cell is done. arrived, timed out and avoided ones are skipped
    private Point nextWaypoint(int px, int pz, double now) {
        double arrive = cfg.waypointArriveBlocks;
        while (index < waypoints.size()) {
            Point w = waypoints.get(index);
            boolean arrived = dist2(px, pz, w.x(), w.z()) <= arrive * arrive;
            boolean timedOut = now - waypointSince >= cfg.waypointSeconds;
            if (!arrived && !timedOut && !nearBastion(w.x(), w.z())) {
                return w;
            }
            index++;
            waypointSince = now;
        }
        return null;
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
