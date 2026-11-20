package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;

import java.util.ArrayList;
import java.util.List;

// the round no-go spots of the pearl hunt's forest walk, on top of the run zones: where a brute or a wither pack killed us
// (DeathZones.killerZones) and the bastions the sweep saw. zones are {x, z, radius}. pure, so a test needs no minecraft
public final class PearlWalkWall {

    private PearlWalkWall() {
    }

    // is (x, z) inside a zone that may be a wall. the one we stand in is no wall (a hard ring around the player is a path that
    // never exists) and neither is the one the forest is in, same rules as RunZones.blocks. player and goal come from the hunt's
    // own snapshot, not the sweep planner's, which only moves while the sweep runs. NaN goal = none
    public static boolean blocks(int x, int z, double[][] zones, double playerX, double playerZ, double goalX, double goalZ) {
        for (double[] zone : zones) {
            if (!inside(zone, x, z)) continue;
            if (inside(zone, playerX, playerZ) || inside(zone, goalX, goalZ)) continue;
            return true;
        }
        return false;
    }

    // inside any of them, no exemptions. for picking which dropped pearl is worth the walk: a pearl in the spot a brute killed
    // us in is the same walk whether we already stand at its edge or not
    public static boolean inAny(double x, double z, List<double[]> zones) {
        for (double[] zone : zones) {
            if (inside(zone, x, z)) return true;
        }
        return false;
    }

    // a zone per known spot, all the same size
    public static List<double[]> around(List<RunState.Pos> spots, double radius) {
        List<double[]> out = new ArrayList<>(spots.size());
        for (RunState.Pos p : spots) {
            out.add(new double[]{p.x, p.z, radius});
        }
        return out;
    }

    // (a NaN on either side is never inside, so "no goal" falls out for free)
    private static boolean inside(double[] zone, double x, double z) {
        double dx = zone[0] - x;
        double dz = zone[1] - z;
        return dx * dx + dz * dz <= zone[2] * zone[2];
    }
}
