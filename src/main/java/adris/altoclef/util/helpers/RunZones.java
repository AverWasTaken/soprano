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
    // a hoglin pack seen from a way off, before anything has had to run from it. a wall for walks and nothing else, see covers
    public static final double HOGLIN_RADIUS = 16;
    // past this they are not on any walk of ours yet, and a floor 24 up or down is somebody else's problem
    public static final double HOGLIN_SEEN_FLAT = 40;
    public static final double HOGLIN_SEEN_Y = 24;
    // two hoglins is a fight the bail can answer, three is 20 hp in 8 s
    public static final int HOGLIN_PACK_SIZE = 3;
    public static final double HOGLIN_PACK_SPREAD = 8;
    // game ticks. three minutes is long enough for the hoglins to wander off and short enough that the zone is not forever
    public static final long EXPIRY_TICKS = 3 * 60 * 20L;
    // two runs from the same spot are one zone of that size, a new one just refreshes the clock
    private static final double SAME_SPOT = 4;
    private static final int MAX = 16;

    public static final RunZones INSTANCE = new RunZones();

    private record Zone(double x, double z, long at, double radius) {
    }

    public record Pack(double x, double z, int count) {
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
        next.add(new Zone(x, z, now, radius));
        while (next.size() > MAX) next.remove(0);
        zones = List.copyOf(next);
    }

    // swaps the whole list in one go, for a source that is rebuilt from scratch every so often (no empty gap for the other
    // thread to see). spots are {x, z}, all stamped now, so they live as long as the source keeps listing them
    public void replaceAll(List<double[]> spots, long now, double radius) {
        List<Zone> next = new ArrayList<>();
        for (double[] spot : spots) {
            next.add(new Zone(spot[0], spot[1], now, radius));
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

    // is (x, z) inside any live zone, no exemptions. this is for picking blazes, not paths: a blaze over the closet is the walk
    // into the closet whether we stand in the zone or not. every zone counts (two runs from one spot merge into the fatter one,
    // and in a fortress the fat ones are wither skeleton runs anyway). hoglins sat near a fortress say nothing about its blazes
    public boolean covers(double x, double z, long now) {
        for (Zone zone : zones) {
            if (zone.radius != HOGLIN_RADIUS && live(zone, now) && inside(zone, x, z)) return true;
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

    // a hoglin zone's middle this close already: a pack ambling about is the same pack, not a new wall and a new clock every second
    public boolean hoglinZoneNear(double x, double z, long now) {
        for (Zone zone : zones) {
            if (zone.radius == HOGLIN_RADIUS && live(zone, now) && Math.hypot(zone.x - x, zone.z - z) <= HOGLIN_RADIUS) return true;
        }
        return false;
    }

    // the middle of the biggest hoglin pack around the player that has no zone yet, null for none. spots are {x, y, z}. the zone
    // check goes in the pick and not after it, or the big pack we already know about hides the small one behind it forever.
    // every hoglin gets a go as the seed: the middle of everybody is an empty field when there are two packs. and at twice the
    // spread too, three of them 14 apart all fit around their middle without any one of them being near another
    public Pack newHoglinPack(List<double[]> hoglins, double playerX, double playerY, double playerZ, long now) {
        List<double[]> seen = new ArrayList<>();
        for (double[] hoglin : hoglins) {
            if (Math.hypot(hoglin[0] - playerX, hoglin[2] - playerZ) <= HOGLIN_SEEN_FLAT && Math.abs(hoglin[1] - playerY) <= HOGLIN_SEEN_Y) seen.add(hoglin);
        }
        Pack best = null;
        for (double[] seed : seen) {
            best = bigger(best, packAround(seen, seed, HOGLIN_PACK_SPREAD), now);
            best = bigger(best, packAround(seen, seed, HOGLIN_PACK_SPREAD * 2), now);
        }
        return best;
    }

    private Pack bigger(Pack best, Pack pack, long now) {
        if (pack.count < HOGLIN_PACK_SIZE || (best != null && pack.count <= best.count)) return best;
        return hoglinZoneNear(pack.x, pack.z, now) ? best : pack;
    }

    private static Pack packAround(List<double[]> seen, double[] seed, double reach) {
        double x = 0;
        double z = 0;
        int group = 0;
        for (double[] hoglin : seen) {
            if (Math.hypot(hoglin[0] - seed[0], hoglin[2] - seed[2]) > reach) continue;
            x += hoglin[0];
            z += hoglin[2];
            group++;
        }
        x /= group;
        z /= group;
        // counted again from the real middle, the seed may have been the odd one out on the edge
        int count = 0;
        for (double[] hoglin : seen) {
            if (Math.hypot(hoglin[0] - x, hoglin[2] - z) <= HOGLIN_PACK_SPREAD) count++;
        }
        return new Pack(x, z, count);
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
