package adris.altoclef.trace;

import java.util.Locale;
import java.util.function.Function;

// the last second or three of motion, kept so a long fall into the nether sea can be read back tick by tick. parallel
// primitive arrays that wrap, the object refs (block, movement, goal) are only turned into text when a dump is asked for.
// pure: Monitors reads the player and hands the numbers in
public final class FallTrace {

    public static final int SIZE = 60;
    // a fall is this many blocks before it is worth a dump (the little hops of a path never get there)
    public static final float FALL_TRIGGER = 4;

    public static final byte FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, SPRINT = 16, JUMP = 32;

    private final double[] x = new double[SIZE], y = new double[SIZE], z = new double[SIZE];
    private final double[] dx = new double[SIZE], dy = new double[SIZE], dz = new double[SIZE];
    private final float[] fall = new float[SIZE];
    private final boolean[] ground = new boolean[SIZE], hurt = new boolean[SIZE];
    private final byte[] keys = new byte[SIZE];
    private final int[] attacker = new int[SIZE];
    private final double[] ax = new double[SIZE], ay = new double[SIZE], az = new double[SIZE];
    private final Object[] under = new Object[SIZE], move = new Object[SIZE], goal = new Object[SIZE];
    private int next;
    private int size;
    // 5 ticks on the ground and the next fall counts again
    private final EntryLatch fallLatch = new EntryLatch(5);

    // the fall half of the trigger: the tick fallDistance crosses the line in the nether, once per fall. the caller ORs in the
    // lava entry. the latch is fed every tick or it never re-arms
    public boolean falling(boolean nether, float fallDistance) {
        return fallLatch.tick(nether && fallDistance > FALL_TRIGGER);
    }

    // attackerId < 0 means nobody (no hit this tick)
    public void push(double px, double py, double pz, double vx, double vy, double vz, boolean onGround, float fallDistance, byte keyBits,
                     Object blockUnder, Object movement, Object goalNow, boolean hit, int attackerId, double hx, double hy, double hz) {
        int i = next;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        dx[i] = vx;
        dy[i] = vy;
        dz[i] = vz;
        ground[i] = onGround;
        fall[i] = fallDistance;
        keys[i] = keyBits;
        under[i] = blockUnder;
        move[i] = movement;
        goal[i] = goalNow;
        hurt[i] = hit;
        attacker[i] = attackerId;
        ax[i] = hx;
        ay[i] = hy;
        az[i] = hz;
        next = (i + 1) % SIZE;
        if (size < SIZE) {
            size++;
        }
    }

    public int size() {
        return size;
    }

    // oldest first, one line per tick. i = 0 is the oldest tick still kept. movement names come from the caller, this class
    // does not know what a movement is
    public String line(int i, Function<Object, String> moveName) {
        int at = (next - size + i + SIZE) % SIZE;
        String line = String.format(Locale.ROOT, "[falltrace] t-%d pos %.2f %.2f %.2f vel %.3f %.3f %.3f ground %b fall %.1f keys %s under %s move %s goal %s",
                size - 1 - i, x[at], y[at], z[at], dx[at], dy[at], dz[at], ground[at], fall[at], keyWord(keys[at]), under[at], move[at] == null ? "none" : moveName.apply(move[at]), shorten(goal[at]));
        if (hurt[at]) {
            line += String.format(Locale.ROOT, " HURT by #%d at %.2f %.2f %.2f", attacker[at], ax[at], ay[at], az[at]);
        }
        return line;
    }

    static String keyWord(byte bits) {
        if (bits == 0) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        if ((bits & FORWARD) != 0) out.append('W');
        if ((bits & BACK) != 0) out.append('S');
        if ((bits & LEFT) != 0) out.append('A');
        if ((bits & RIGHT) != 0) out.append('D');
        if ((bits & SPRINT) != 0) out.append('R');
        if ((bits & JUMP) != 0) out.append('J');
        return out.toString();
    }

    // goals print their whole life story, the first bit says which one it is
    private static String shorten(Object o) {
        if (o == null) {
            return "none";
        }
        String s = o.toString();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
