package adris.altoclef.util.helpers;

// "have we been standing about". the combat machine ignores a shooter past contact because the task keeps walking and a
// moving target is a bad target, and that stops being true at a furnace, a table, an ore face or a meal. this does not care
// which of those it is, only that we have not been anywhere else: still inside RADIUS of where we were TICKS ago.
// pure on purpose, the position and the game time come in as numbers
public final class AnchorWatch {

    // a knockback or a shuffle round a furnace is not leaving, a walk is
    public static final double RADIUS = 2;
    public static final long TICKS = 60;
    // not being fed for this long means we lost track of where we were (a paused game, a screen, a respawn), so start over
    private static final long GAP = 5;

    private double ax;
    private double ay;
    private double az;
    private long since = Long.MIN_VALUE;
    private long last = Long.MIN_VALUE;

    // one call per game tick. true once we have stayed put for TICKS
    public boolean update(long now, double x, double y, double z) {
        boolean lost = since == Long.MIN_VALUE || now < last || now - last > GAP;
        if (lost || Math.sqrt(sq(x - ax) + sq(y - ay) + sq(z - az)) > RADIUS) {
            ax = x;
            ay = y;
            az = z;
            since = now;
        }
        last = now;
        return now - since >= TICKS;
    }

    public void reset() {
        since = Long.MIN_VALUE;
        last = Long.MIN_VALUE;
    }

    private static double sq(double v) {
        return v * v;
    }
}
