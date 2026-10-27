package adris.altoclef.util.helpers;

// where to swim when we are in lava and baritone has no path for us yet: the closest cell a body could stand in, dry and
// on something solid. pure, it only asks the same Terrain the pickup rules ask
public final class LavaExit {

    // a lake bigger than this around us is not something a blind swim gets out of anyway
    public static final int RADIUS = 8;
    // measured from the lava surface above us, not from our feet: jumping in lava floats us up to the surface, and from
    // there the most we climb out onto is one block. a cell on top of a 2 high wall is not a way out, however close it is
    private static final int ABOVE_SURFACE = 1;
    private static final int BELOW_SURFACE = 2;
    // how far up we follow the lava column to find its surface
    private static final int MAX_DEPTH = 6;

    public record Cell(int x, int y, int z) {
    }

    private LavaExit() {
    }

    // an exit this close is a hop, not a path: one or two cells over and at most one up from the lava cell we float in.
    // anything farther is baritone's, it can plan around walls and bridge a gap where a blind hop would just bonk
    static final int HOP_REACH = 2;

    public static boolean withinHop(Cell exit, int px, int py, int pz) {
        return exit != null
                && Math.abs(exit.x() - px) <= HOP_REACH
                && Math.abs(exit.z() - pz) <= HOP_REACH
                && exit.y() - py <= ABOVE_SURFACE;
    }

    // a rim two above the surface is a wall, and a hop can't climb a wall. a block set into the lava cell beside us at the
    // surface is a one high step to it: stand on its top and the rim is the usual one up. T is a horizontal neighbour of the
    // surface cell we float in (never our own column, pillaring in lava loses to the bobbing, see isSafeCell), lava or air,
    // with room above. the rim N beside it is solid one up from T (the wall) and standable above that, and something next to
    // T or under it is there to click against.
    // yaw breaks ties, we'd rather place where we are already looking. null when there is no such cell
    public static Cell stairCell(int px, int py, int pz, ItemPickupRules.Terrain t, ItemPickupRules.Lava lava, float yawDegrees) {
        int surface = py;
        while (surface - py < MAX_DEPTH && lava.at(px, surface + 1, pz)) {
            surface++;
        }
        double lookX = -Math.sin(Math.toRadians(yawDegrees));
        double lookZ = Math.cos(Math.toRadians(yawDegrees));
        Cell best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int[] d : SIDES) {
            int x = px + d[0];
            int z = pz + d[1];
            if (!(lava.at(x, surface, z) || t.open(x, surface, z)) || !t.open(x, surface + 1, z) || !t.open(x, surface + 2, z)) continue;
            if (!hasRim(x, surface, z, t) || !hasSupport(x, surface, z, t)) continue;
            double score = d[0] * lookX + d[1] * lookZ;
            if (score > bestScore) {
                best = new Cell(x, surface, z);
                bestScore = score;
            }
        }
        return best;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // a neighbour of T that is solid one up from it, with a body's room on top of that
    private static boolean hasRim(int x, int y, int z, ItemPickupRules.Terrain t) {
        for (int[] d : SIDES) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (t.solid(nx, y + 1, nz) && t.open(nx, y + 2, nz) && t.open(nx, y + 3, nz)) return true;
        }
        return false;
    }

    // something to click against: a wall at T's level on any side, or a floor under it
    private static boolean hasSupport(int x, int y, int z, ItemPickupRules.Terrain t) {
        if (t.solid(x, y - 1, z)) return true;
        for (int[] d : SIDES) {
            if (t.solid(x + d[0], y, z + d[1])) return true;
        }
        return false;
    }

    // null when nothing is standable in range. feet position, so for a player in lava that is the lava cell itself
    public static Cell nearest(int px, int py, int pz, ItemPickupRules.Terrain t, ItemPickupRules.Lava lava) {
        int surface = py;
        while (surface - py < MAX_DEPTH && lava.at(px, surface + 1, pz)) {
            surface++;
        }
        Cell best = null;
        long bestDist = Long.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int y = surface - BELOW_SURFACE; y <= surface + ABOVE_SURFACE; y++) {
                    int dy = y - py;
                    long dist = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (dist >= bestDist) continue;
                    int x = px + dx;
                    int z = pz + dz;
                    if (t.solid(x, y - 1, z) && t.open(x, y, z) && t.open(x, y + 1, z)) {
                        best = new Cell(x, y, z);
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }
}
