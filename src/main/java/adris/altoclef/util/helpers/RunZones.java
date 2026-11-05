package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.List;

// where a heavy hitter ran us off from, remembered for a few minutes. the sweep's path goes right back through the spot we
// were chased out of otherwise: same hoglins, same corridor, second fight. pure numbers, the combat brain writes and the
// sweep's path predicate reads (from baritone's thread, hence the immutable snapshot)
public final class RunZones {

    // the bastion zones are already 48 wide, a wall much fatter than this is a path that never exists
    public static final double RADIUS = 24;
    // a wither pack is a closet, not a bastion
    public static final double PACK_RADIUS = 12;
    // a pack seen from a way off, before anything has had to run from it. a wall for walks and nothing else, see covers
    public static final double FLOOR_RADIUS = 16;
    // past this they are not on any walk of ours yet. the y has to reach the floor under a fortress ledge, a 20 block drop
    // is decided up top and by the bottom it's too late to have an opinion
    public static final double PACK_SEEN_FLAT = 40;
    public static final double PACK_SEEN_Y = 32;
    // two hoglins is a fight the bail can answer, three is 20 hp in 8 s. three wither skeletons is 4.5 a swing from all sides
    public static final int PACK_SIZE = 3;
    public static final double PACK_SPREAD = 8;
    // a floor zone is only these cells around the pack's feet. fortress floors stack, a wall from bedrock to roof shuts the
    // bridge overhead for a pack that can't even see it. the top is what refuses a fall: its column gets asked on the way down
    public static final int FLOOR_BELOW = 2;
    public static final int FLOOR_ABOVE = 4;
    // game ticks. three minutes is long enough for the hoglins to wander off and short enough that the zone is not forever
    public static final long EXPIRY_TICKS = 3 * 60 * 20L;
    // two runs from the same spot are one zone of that size, a new one just refreshes the clock
    private static final double SAME_SPOT = 4;
    private static final int MAX = 16;

    public static final RunZones INSTANCE = new RunZones();

    // y is NaN for the flat ones, they go all the way up
    private record Zone(double x, double z, long at, double radius, double y) {
        boolean floor() {
            return !Double.isNaN(y);
        }
    }

    public record Pack(double x, double y, double z, int count) {
    }

    private volatile List<Zone> zones = List.of();

    // every zone carries its own radius, the bastion ones are fat and a pack of skeletons is not
    public void add(double x, double z, long now, double radius) {
        List<Zone> next = new ArrayList<>();
        for (Zone zone : zones) {
            // (the clock going backwards is another world, nothing from the old one means anything)
            if (zone.at > now || now - zone.at >= EXPIRY_TICKS) continue;
            // a pack and a fat zone on one spot stay two zones. a merge would move the pack and restart its clock on a run that
            // had nothing to do with it, and a pack zone still must not shrink a bastion one
            if (zone.radius == radius && Math.hypot(zone.x - x, zone.z - z) <= SAME_SPOT) continue;
            next.add(zone);
        }
        next.add(new Zone(x, z, now, radius, Double.NaN));
        trim(next);
        zones = List.copyOf(next);
    }

    // a pack on one floor, see FLOOR_BELOW. newPack already asked floorZoneNear, so there is nothing to merge in here
    public void addFloor(double x, double y, double z, long now) {
        List<Zone> next = new ArrayList<>();
        for (Zone zone : zones) {
            if (live(zone, now)) next.add(zone);
        }
        next.add(new Zone(x, z, now, FLOOR_RADIUS, Math.floor(y)));
        trim(next);
        zones = List.copyOf(next);
    }

    // over the cap the floor zones go first: we saw those from a way off and see them again in a second, a place we were chased
    // out of nobody will tell us about twice
    private static void trim(List<Zone> zones) {
        while (zones.size() > MAX) {
            int drop = 0;
            for (int i = 0; i < zones.size(); i++) {
                if (zones.get(i).floor()) {
                    drop = i;
                    break;
                }
            }
            zones.remove(drop);
        }
    }

    // swaps the whole list in one go, for a source that is rebuilt from scratch every so often (no empty gap for the other
    // thread to see). spots are {x, z}, all stamped now, so they live as long as the source keeps listing them
    public void replaceAll(List<double[]> spots, long now, double radius) {
        List<Zone> next = new ArrayList<>();
        for (double[] spot : spots) {
            next.add(new Zone(spot[0], spot[1], now, radius, Double.NaN));
        }
        while (next.size() > MAX) next.remove(0);
        zones = List.copyOf(next);
    }

    public void clear() {
        zones = List.of();
    }

    // how many zones are still alive
    public int active(long now) {
        int n = 0;
        for (Zone zone : zones) {
            if (live(zone, now)) n++;
        }
        return n;
    }

    // is (x, z) inside a live zone that is allowed to be a wall. a zone we stand in is no wall (a hard ring around the player
    // is a path that never exists, same rule as the bastion zones) and neither is one the goal is in: the sweep has to get there,
    // and it is a three minute detour at worst, not a reason to give up. NaN goal = no goal known
    public boolean blocks(int x, int z, long now, double playerX, double playerZ, double goalX, double goalZ) {
        for (Zone zone : zones) {
            if (!live(zone, now) || !inside(zone, x, z)) continue;
            if (inside(zone, playerX, playerZ) || inside(zone, goalX, goalZ)) continue;
            return true;
        }
        return false;
    }

    // blocks with a y, what a walk's predicate asks. a flat zone answers the same as blocks. a floor zone only has its band of
    // cells, and only stands aside for a player who isn't over it: from 25 up the floor below is still somewhere not to land
    public boolean blocksAt(int x, int y, int z, long now, double playerX, double playerY, double playerZ, double goalX, double goalZ) {
        for (Zone zone : zones) {
            if (!live(zone, now) || !inside(zone, x, z)) continue;
            if (zone.floor() && !inBand(zone, y)) continue;
            if (inside(zone, playerX, playerZ) && (!zone.floor() || !overFloor(zone, playerY))) continue;
            if (inside(zone, goalX, goalZ)) continue;
            return true;
        }
        return false;
    }

    private static boolean inBand(Zone zone, double y) {
        return y >= zone.y - FLOOR_BELOW && y <= zone.y + FLOOR_ABOVE;
    }

    // under the band counts as not over it. the predicate gets head cells too, so a step up from just below asks about the band
    // and a no there is the hard ring around the player the exemption is there to stop. the fall this is all for only comes from above
    private static boolean overFloor(Zone zone, double feetY) {
        return feetY > zone.y + FLOOR_ABOVE;
    }

    // is (x, z) inside any live zone, no exemptions. this is for picking blazes, not paths: a blaze over the closet is the walk
    // into the closet whether we stand in the zone or not. every zone counts (two runs from one spot merge into the fatter one,
    // and in a fortress the fat ones are wither skeleton runs anyway). a pack seen from afar says nothing about the blazes over it
    public boolean covers(double x, double z, long now) {
        for (Zone zone : zones) {
            if (!zone.floor() && live(zone, now) && inside(zone, x, z)) return true;
        }
        return false;
    }

    // is (x, z) in a live wither pack zone. this is for picking spawners: the fat zones from a run off one skeleton don't count,
    // one skeleton near a spawner is a fight we can have and 24 blocks is most of a fortress
    public boolean coversPack(double x, double z, long now) {
        return packZoneAt(x, z, now) != null;
    }

    // the middle of the pack zone covering (x, z) as {x, z}, null for none
    public double[] packZoneAt(double x, double z, long now) {
        for (Zone zone : zones) {
            if (zone.radius == PACK_RADIUS && live(zone, now) && inside(zone, x, z)) return new double[]{zone.x, zone.z};
        }
        return null;
    }

    // a floor zone's middle this close on this floor already: a pack ambling about is the same pack, not a new wall and a new
    // clock every second. tighter in y than sameFloor on purpose: a pack 4 under a zone stands below its band, and calling that
    // the same zone leaves it with no wall at all
    public boolean floorZoneNear(double x, double y, double z, long now) {
        for (Zone zone : zones) {
            if (zone.floor() && live(zone, now) && Math.hypot(zone.x - x, zone.z - z) <= FLOOR_RADIUS
                    && Math.abs(zone.y - Math.floor(y)) <= FLOOR_BELOW) return true;
        }
        return false;
    }

    private static boolean sameFloor(double a, double b) {
        return Math.abs(a - b) <= FLOOR_ABOVE;
    }

    // the middle of the biggest pack around the player that has no zone yet, null for none. spots are {x, y, z}. the zone
    // check goes in the pick and not after it, or the big pack we already know about hides the small one behind it forever.
    // every hoglin gets a go as the seed: the middle of everybody is an empty field when there are two packs. and at twice the
    // spread too, three of them 14 apart all fit around their middle without any one of them being near another
    public Pack newPack(List<double[]> mobs, double playerX, double playerY, double playerZ, long now) {
        List<double[]> seen = new ArrayList<>();
        for (double[] mob : mobs) {
            if (Math.hypot(mob[0] - playerX, mob[2] - playerZ) <= PACK_SEEN_FLAT && Math.abs(mob[1] - playerY) <= PACK_SEEN_Y) seen.add(mob);
        }
        Pack best = null;
        for (double[] seed : seen) {
            best = bigger(best, packAround(seen, seed, PACK_SPREAD), now);
            best = bigger(best, packAround(seen, seed, PACK_SPREAD * 2), now);
        }
        return best;
    }

    private Pack bigger(Pack best, Pack pack, long now) {
        if (pack.count < PACK_SIZE || (best != null && pack.count <= best.count)) return best;
        return floorZoneNear(pack.x, pack.y, pack.z, now) ? best : pack;
    }

    private static Pack packAround(List<double[]> seen, double[] seed, double reach) {
        double x = 0;
        double z = 0;
        int group = 0;
        // (one floor at a time. three skeletons stacked in one column are not a pack anybody can walk into)
        for (double[] mob : seen) {
            if (!sameFloor(mob[1], seed[1]) || Math.hypot(mob[0] - seed[0], mob[2] - seed[2]) > reach) continue;
            x += mob[0];
            z += mob[2];
            group++;
        }
        x /= group;
        z /= group;
        // counted again from the real middle, the seed may have been the odd one out on the edge
        List<Double> ys = new ArrayList<>();
        for (double[] mob : seen) {
            if (sameFloor(mob[1], seed[1]) && Math.hypot(mob[0] - x, mob[2] - z) <= PACK_SPREAD) ys.add(mob[1]);
        }
        // the median: one of them mid jump or up a step should not move the floor
        ys.sort(null);
        return new Pack(x, ys.isEmpty() ? seed[1] : ys.get(ys.size() / 2), z, ys.size());
    }

    private static boolean live(Zone zone, long now) {
        return zone.at <= now && now - zone.at < EXPIRY_TICKS;
    }

    private static boolean inside(Zone zone, double x, double z) {
        double dx = zone.x - x;
        double dz = zone.z - z;
        // (NaN fails the compare, so a goal nobody knows about is never inside)
        return dx * dx + dz * dz < zone.radius * zone.radius;
    }
}
