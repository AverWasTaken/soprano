package adris.altoclef.tasks.speedrun.gamer.end;

import adris.altoclef.tasks.speedrun.gamer.end.DragonPerch.Box;
import java.util.ArrayList;
import java.util.List;

// where the bed goes once the perched dragon's yaw is frozen (attacking, then flaming): a bed head cell under the head or
// neck, its foot cell behind it, and a stand cell we place and click from. pure, the task hands in the boxes and a terrain
// view. the bed's head half lands one cell past the foot along the way we face, so the stand is behind the foot looking
// at the head
public final class BedPairSearch {
    // a bed this good or the perch is not worth the walk (head and neck both take the full hit in 26.3)
    public static final double MIN_DRAGON_DAMAGE = 45;
    // the parts do not move while sitting, a hair of margin is plenty
    public static final double PART_INFLATE = 0.05;
    // the player box misses wing pushes and bites by this much
    public static final double STAND_MARGIN = 0.2;
    // flat distance from the stand to the cloud's middle: radius 5 plus our half width
    public static final double CLOUD_KEEP_OUT = 5.3;
    // our feet to the bed head's centre
    public static final double HEAD_KEEP_OUT = 4.5;
    // the eye to the middle of the floor face we click, under the 4.5 the server allows
    public static final double REACH = 4.4;
    // stands this far from us (flat), the chunks and the walk are fine at that
    public static final double SEARCH_RADIUS = 12;
    // the yaw at the stand picks the bed's facing, so the foot has to be clearly along one axis or the head half rounds
    // the other way. half a block also covers standing off the cell centre
    public static final double FACING_MARGIN = 0.5;
    // how far around the head a head cell is looked for. 45 damage needs the part within ~2.9
    private static final int HEAD_SPAN = 3;
    private static final int HEAD_DOWN = 4;
    private static final int HEAD_UP = 1;
    private static final int STAND_SPAN = 5;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // the least a pair can cost us: stand a step below the foot with its floor face at the reach limit, the head half one
    // further on. ~5.56 from our feet, ~23 raw. no pair the search finds does better
    public static final double BEST_SELF_DAMAGE = DragonPerch.bedDamage(Math.hypot(
            Math.sqrt(REACH * REACH - (DragonPerch.EYE_HEIGHT - 1) * (DragonPerch.EYE_HEIGHT - 1)) + 1, 1.5));

    private BedPairSearch() {
    }

    public interface Terrain {
        // air we can set a bed half in, solid floor under it
        boolean bedCell(int x, int y, int z);

        // feet and head air, solid floor under
        boolean standable(int x, int y, int z);
    }

    public record Cell(int x, int y, int z) {
        double cx() {
            return x + 0.5;
        }

        double cz() {
            return z + 0.5;
        }
    }

    // dirX/dirZ: the way from the foot to the head, which is the way we face while placing
    public record Pair(Cell head, Cell foot, Cell stand, int dirX, int dirZ, double dragonDamage, double selfDamage) {
        public double score() {
            return dragonDamage - selfDamage;
        }
    }

    // headPos / neckPos are the parts' positions (bottom centre of the box), that is what the explosion measures from.
    // cloudUp: the flame cloud exists, so the walk to the stand must not cross it
    public record Dragon(List<Box> eaters, List<Box> pushes, List<Box> biters, double headX, double headY, double headZ,
                         double neckX, double neckY, double neckZ, double cloudX, double cloudZ, boolean cloudUp) {
    }

    // the best pair, or null when no cell around here works
    public static Pair search(Dragon d, Terrain t, double botX, double botZ) {
        Pair best = null;
        double bestWalk = Double.MAX_VALUE;
        int hx0 = (int) Math.floor(d.headX());
        int hy0 = (int) Math.floor(d.headY());
        int hz0 = (int) Math.floor(d.headZ());
        for (int x = hx0 - HEAD_SPAN; x <= hx0 + HEAD_SPAN; x++) {
            for (int z = hz0 - HEAD_SPAN; z <= hz0 + HEAD_SPAN; z++) {
                for (int y = hy0 - HEAD_DOWN; y <= hy0 + HEAD_UP; y++) {
                    Cell h = new Cell(x, y, z);
                    double dmg = dragonDamage(d, h);
                    if (dmg < MIN_DRAGON_DAMAGE || !t.bedCell(x, y, z) || !cellClear(d.eaters(), h)) continue;
                    for (int[] dir : DIRS) {
                        Cell f = new Cell(x - dir[0], y, z - dir[1]);
                        if (!t.bedCell(f.x, f.y, f.z) || !cellClear(d.eaters(), f)) continue;
                        for (Pair p : stands(d, t, h, f, dir, dmg, botX, botZ)) {
                            double walk = Math.hypot(p.stand().cx() - botX, p.stand().cz() - botZ);
                            if (best == null || p.score() > best.score() + 1e-9
                                    || (Math.abs(p.score() - best.score()) <= 1e-9 && walk < bestWalk)) {
                                best = p;
                                bestWalk = walk;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    // every stand that works for this head/foot pair, as pairs
    private static List<Pair> stands(Dragon d, Terrain t, Cell h, Cell f, int[] dir, double dmg, double botX, double botZ) {
        ArrayList<Pair> out = new ArrayList<>();
        for (int x = f.x - STAND_SPAN; x <= f.x + STAND_SPAN; x++) {
            for (int z = f.z - STAND_SPAN; z <= f.z + STAND_SPAN; z++) {
                for (int y = f.y - 1; y <= f.y + 1; y++) {
                    Cell s = new Cell(x, y, z);
                    if (standOk(d, t, h, f, dir, s, botX, botZ)) {
                        out.add(new Pair(h, f, s, dir[0], dir[1], dmg, selfDamage(s, h)));
                    }
                }
            }
        }
        return out;
    }

    // one stand against every rule: facing, reach, keep outs, terrain, the dragon's boxes, the walk past the cloud
    public static boolean standOk(Dragon d, Terrain t, Cell h, Cell f, int[] dir, Cell s, double botX, double botZ) {
        if (!facesAlong(s, f, dir) || !inReach(s, f)) return false;
        if (Math.hypot(s.cx() - d.cloudX(), s.cz() - d.cloudZ()) < CLOUD_KEEP_OUT) return false;
        if (headDistance(s, h) < HEAD_KEEP_OUT) return false;
        if (Math.hypot(s.cx() - botX, s.cz() - botZ) > SEARCH_RADIUS) return false;
        if (!t.standable(s.x, s.y, s.z)) return false;
        if (!DragonPerch.standsClear(DragonPerch.player(s.cx(), s.y, s.cz()), d.pushes(), d.biters(), STAND_MARGIN)) return false;
        return !d.cloudUp() || segmentDistance(botX, botZ, s.cx(), s.cz(), d.cloudX(), d.cloudZ()) >= CLOUD_KEEP_OUT;
    }

    // the bigger of the head's and the neck's hit from a bed head in this cell
    public static double dragonDamage(Dragon d, Cell h) {
        double head = DragonPerch.bedDamage(dist(d.headX(), d.headY(), d.headZ(), h.cx(), h.y + 0.5, h.cz()));
        double neck = DragonPerch.bedDamage(dist(d.neckX(), d.neckY(), d.neckZ(), h.cx(), h.y + 0.5, h.cz()));
        return Math.max(head, neck);
    }

    // what the same bed does to us at the stand, unarmored and fully exposed
    public static double selfDamage(Cell s, Cell h) {
        return DragonPerch.bedDamage(headDistance(s, h));
    }

    // a bed cell clear of the head, neck and body boxes. checkWalls deletes any block they touch
    public static boolean cellClear(List<Box> eaters, Cell c) {
        Box cell = Box.cell(c.x, c.y, c.z);
        for (Box b : eaters) {
            if (b.inflate(PART_INFLATE).intersects(cell)) return false;
        }
        return true;
    }

    // the yaw from the stand to the foot rounds to dir, with FACING_MARGIN to spare
    static boolean facesAlong(Cell s, Cell f, int[] dir) {
        double dx = f.cx() - s.cx();
        double dz = f.cz() - s.cz();
        double along = dx * dir[0] + dz * dir[1];
        double across = Math.abs(dx * dir[1] - dz * dir[0]);
        return along > 0 && along - across >= FACING_MARGIN;
    }

    // the floor face under the foot (its top is at f.y) from the eye
    static boolean inReach(Cell s, Cell f) {
        return dist(s.cx(), s.y + DragonPerch.EYE_HEIGHT, s.cz(), f.cx(), f.y, f.cz()) <= REACH;
    }

    // our feet to the bed head's centre, which is where the bed explodes
    static double headDistance(Cell s, Cell h) {
        return dist(s.cx(), s.y, s.cz(), h.cx(), h.y + 0.5, h.cz());
    }

    // flat distance from (px, pz) to the segment a-b
    static double segmentDistance(double ax, double az, double bx, double bz, double px, double pz) {
        double vx = bx - ax;
        double vz = bz - az;
        double len2 = vx * vx + vz * vz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
        return Math.hypot(ax + vx * t - px, az + vz * t - pz);
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
