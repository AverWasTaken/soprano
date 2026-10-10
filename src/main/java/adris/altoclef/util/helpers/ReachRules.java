package adris.altoclef.util.helpers;

// what "in reach" means to the server's melee check: the eye to the nearest point of the target's box, not feet to feet. a
// zombie on our head is feet 1.8 away and a spider across a gap is feet 3.4 away and both are closer than that sounds
public final class ReachRules {

    // the interaction range of a plain player
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
}
