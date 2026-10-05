package adris.altoclef.tasks.speedrun.gamer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// pure: tells a pillager outpost from a patrol walking past. a patrol keeps moving, outpost pillagers stand around
// the tower, so a pillager that stays put for a while marks an outpost and only then do the trees and sheep near it
// get written off. (the blacklist has no expiry, so a patrol must never trigger it)
public final class PillagerWatch {
    private static final double STAY_RADIUS = 12;
    // outposts closer than this to a known one are the same outpost
    private static final double MERGE_RADIUS = 24;

    private final long stayTicks;
    private final Map<Integer, double[]> anchors = new HashMap<>();
    private final Map<Integer, Long> since = new HashMap<>();
    private final List<double[]> outposts = new ArrayList<>();

    public PillagerWatch(long stayTicks) {
        this.stayTicks = stayTicks;
    }

    // positions: entity id -> {x, z} of every pillager we can see right now
    public void update(long tick, Map<Integer, double[]> positions) {
        anchors.keySet().retainAll(positions.keySet());
        since.keySet().retainAll(positions.keySet());
        for (Map.Entry<Integer, double[]> e : positions.entrySet()) {
            double[] now = e.getValue();
            double[] anchor = anchors.get(e.getKey());
            if (anchor == null || Math.hypot(now[0] - anchor[0], now[1] - anchor[1]) > STAY_RADIUS) {
                anchors.put(e.getKey(), now);
                since.put(e.getKey(), tick);
            } else if (tick - since.get(e.getKey()) >= stayTicks) {
                addOutpost(anchor);
            }
        }
    }

    private void addOutpost(double[] at) {
        for (double[] known : outposts) {
            if (Math.hypot(known[0] - at[0], known[1] - at[1]) < MERGE_RADIUS) {
                return;
            }
        }
        outposts.add(at);
    }

    public boolean nearOutpost(double x, double z, double radius) {
        for (double[] o : outposts) {
            if (Math.hypot(o[0] - x, o[1] - z) <= radius) {
                return true;
            }
        }
        return false;
    }

    public int outposts() {
        return outposts.size();
    }
}
