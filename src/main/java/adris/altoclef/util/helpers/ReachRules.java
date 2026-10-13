package adris.altoclef.util.helpers;

// how close is close enough to hit. two measures: feet to feet, which is what the reach setting has always been compared
// against, and eye to the nearest point of the target's box, which is how vanilla measures. the feet line misjudges a mob
// in our own cell, straight above (the eye is 1.62 up, so it is nearer than its feet) and straight below (we hit the top of
// its head, so it is farther) and a wide box (a spider's near face is closer than its feet)
public final class ReachRules {

    // a plain player's interaction range: contact. the corner rules use it as "it is on us", where the setting's looser
    // reach would call a zombie across a gap contact
    public static final double MELEE_REACH = 3.0;

    private ReachRules() {
    }

    public static double gap(double eyeX, double eyeY, double eyeZ,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - eyeX, 0), eyeX - maxX);
        double dy = Math.max(Math.max(minY - eyeY, 0), eyeY - maxY);
        double dz = Math.max(Math.max(minZ - eyeZ, 0), eyeZ - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static boolean inReach(double gap, double reach) {
        return gap <= reach;
    }

    // the kill tasks' reach: the old feet line OR the eye to box gap inside the same setting. only ever adds to what the feet
    // line allowed, the server takes a hit from further out than this (up to 6) so cutting it only made the bot walk more
    public static boolean killReach(boolean feetLine, double gap, double setting) {
        return feetLine || inReach(gap, setting);
    }
}
