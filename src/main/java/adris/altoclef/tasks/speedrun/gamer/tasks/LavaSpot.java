package adris.altoclef.tasks.speedrun.gamer.tasks;

import java.util.Arrays;

// places the pearl hunt got stuck by lava (backing off for ever, or standing there letting a foe come). endermen near one are
// not worth the trip for a while, and every new one that spawns there would get its own fresh patience otherwise. up to three
// at once, each times out by itself because the forest is the main source of endermen and giving it up for good costs the hunt.
// three and not one: with a single spot a second lava place could not go up while the first was live, so the hunt flip flopped
// by the second lava until the first ran out. and moving the one spot would un-ban the first place, so the hunt walks straight
// back there and ping pongs between the two
final class LavaSpot {
    // 3 min
    static final long LIFETIME_TICKS = 3 * 60 * 20;
    static final double RADIUS = 24;
    // 20 s of standing by the lava inside the 60 s window (LavaHoldClock)
    static final int HOLD_TRIGGER_TICKS = 20 * 20;
    static final int MAX_SPOTS = 3;

    enum Armed { NEW, RESTARTED }

    private final double[] xs = new double[MAX_SPOTS];
    private final double[] ys = new double[MAX_SPOTS];
    private final double[] zs = new double[MAX_SPOTS];
    private final long[] armedAt = new long[MAX_SPOTS];
    // the centre of the last spot arm() touched, for the log line
    private int lastArmed = -1;

    LavaSpot() {
        Arrays.fill(armedAt, Long.MIN_VALUE);
    }

    static boolean trigger(boolean awayGaveUp, int holdTicks) {
        return awayGaveUp || holdTicks >= HOLD_TRIGGER_TICKS;
    }

    boolean active(long now) {
        return liveCount(now) > 0;
    }

    int liveCount(long now) {
        int n = 0;
        for (int i = 0; i < MAX_SPOTS; i++) {
            if (live(i, now)) {
                n++;
            }
        }
        return n;
    }

    // standing inside a live spot restarts its clock (and the walk away runs again): a spot we never walked out of did
    // nothing, and the endermen right next to us were still fair game. anywhere else is a new spot, the old ones stay
    Armed arm(double x, double y, double z, long now) {
        int inside = nearestCovering(x, y, z, now);
        if (inside >= 0) {
            armedAt[inside] = now;
            lastArmed = inside;
            return Armed.RESTARTED;
        }
        int slot = freeSlot(now);
        xs[slot] = x;
        ys[slot] = y;
        zs[slot] = z;
        armedAt[slot] = now;
        lastArmed = slot;
        return Armed.NEW;
    }

    // "x,y,z" of the spot the last arm() put up or restarted, rounded
    String lastCentre() {
        if (lastArmed < 0) {
            return "none";
        }
        return Math.round(xs[lastArmed]) + "," + Math.round(ys[lastArmed]) + "," + Math.round(zs[lastArmed]);
    }

    // the walk to that enderman goes through a spot (or starts inside one). flat on purpose: covers only asks where the
    // enderman stands, and the one past the lava is the one that walks us straight back in
    boolean blocksWalk(double usX, double usZ, double manX, double manZ, long now) {
        for (int i = 0; i < MAX_SPOTS; i++) {
            if (live(i, now) && walkHits(i, usX, usZ, manX, manZ)) {
                return true;
            }
        }
        return false;
    }

    boolean covers(double ex, double ey, double ez, long now) {
        return nearestCovering(ex, ey, ez, now) >= 0;
    }

    private boolean live(int i, long now) {
        return armedAt[i] != Long.MIN_VALUE && now >= armedAt[i] && now - armedAt[i] < LIFETIME_TICKS;
    }

    private boolean walkHits(int i, double usX, double usZ, double manX, double manZ) {
        double walkX = manX - usX;
        double walkZ = manZ - usZ;
        double length = walkX * walkX + walkZ * walkZ;
        // closest point of the walk to the spot, 0 is us and 1 is the enderman
        double along = length == 0 ? 0 : Math.max(0, Math.min(1, ((xs[i] - usX) * walkX + (zs[i] - usZ) * walkZ) / length));
        double dx = usX + along * walkX - xs[i];
        double dz = usZ + along * walkZ - zs[i];
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    // -1 when no live spot has that point inside. two overlapping spots: the closer centre is the one we are "in"
    private int nearestCovering(double ex, double ey, double ez, long now) {
        int best = -1;
        double bestSq = RADIUS * RADIUS;
        for (int i = 0; i < MAX_SPOTS; i++) {
            if (!live(i, now)) {
                continue;
            }
            double dx = ex - xs[i];
            double dy = ey - ys[i];
            double dz = ez - zs[i];
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq <= bestSq) {
                best = i;
                bestSq = sq;
            }
        }
        return best;
    }

    // an expired (or never used) slot first, else the oldest live one goes. a clock that went backwards counts as expired,
    // same as active() says
    private int freeSlot(long now) {
        int oldest = 0;
        for (int i = 0; i < MAX_SPOTS; i++) {
            if (!live(i, now)) {
                return i;
            }
            if (armedAt[i] < armedAt[oldest]) {
                oldest = i;
            }
        }
        return oldest;
    }
}
