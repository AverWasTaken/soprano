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
