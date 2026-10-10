package adris.altoclef.util.helpers;

// which way to sprint for the first few ticks of a creeper step, before baritone has a path. no world in here: the
// caller hands in a lookup for what a cell is, so every case can be tested with a map of fake blocks
public final class CreeperEscape {

    // how far along the heading we look, and how often. half a block so a diagonal never hops over a cell
    static final double AHEAD = 2;
    static final double STEP = 0.5;
    // a jump that has to happen inside this much of runway, any earlier and we are airborne over the wrong thing
    static final double JUMP_RUNWAY = 1;
    // falls up to three blocks cost nothing, the fourth hurts
    static final int MAX_DROP = 3;
    // straight away first, then either side. the sign is just which side gets tried first
    static final int[] FAN = {0, 45, -45, 90, -90};

    private CreeperEscape() {
    }

    public enum Kind {
        // nothing to collide with and no liquid
        OPEN,
        // a body does not go through it
        SOLID,
        // water is fine to run through and to land in, it is just slow
        WATER,
        // lava, fire, cactus, berries, powder snow, cobweb: not into it, not onto it
        DANGER
    }

    public interface Cells {
        Kind kind(int x, int y, int z);
    }

    // degrees is how far off directly-away this is (0 = straight), dx/dz the unit direction, jump = a one block step is
    // coming up inside the runway
    public record Heading(int degrees, double dx, double dz, boolean jump) {
        // minecraft yaw: 0 faces +z, 90 faces -x
        public float yaw() {
            return (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
    }

    // null when no heading is safe (or the creeper is on top of us and "away" means nothing), and then the path has it
    public static Heading choose(double px, double py, double pz, double cx, double cz, Cells cells) {
        double ax = px - cx;
        double az = pz - cz;
        double away = Math.hypot(ax, az);
        if (away < 1e-3) return null;
        ax /= away;
        az /= away;
        for (int degrees : FAN) {
            double rad = Math.toRadians(degrees);
            double dx = ax * Math.cos(rad) - az * Math.sin(rad);
            double dz = ax * Math.sin(rad) + az * Math.cos(rad);
            // sideways is only worth it if it still gains a little. nothing in the fan goes backwards, but the check is
            // what keeps it that way if somebody widens the fan
            if (Math.hypot(px + dx * AHEAD - cx, pz + dz * AHEAD - cz) <= away + 0.01) continue;
            int jump = runway(px, py, pz, dx, dz, cells);
            if (jump >= 0) return new Heading(degrees, dx, dz, jump == 1);
        }
        return null;
    }

    // -1 = not safe to run, 0 = run, 1 = run and jump
    private static int runway(double px, double py, double pz, double dx, double dz, Cells cells) {
        int y = (int) Math.floor(py);
        int lastX = (int) Math.floor(px);
        int lastZ = (int) Math.floor(pz);
        boolean jump = false;
        for (double s = STEP; s <= AHEAD + 1e-9; s += STEP) {
            int x = (int) Math.floor(px + dx * s);
            int z = (int) Math.floor(pz + dz * s);
            if (x == lastX && z == lastZ) continue;
            // crossing a corner the body clips both cells it touches, so a diagonal through a wall corner is a wall
            if (x != lastX && z != lastZ && (!free(cells, x, y, lastZ) || !free(cells, lastX, y, z))) return -1;
            Kind feet = cells.kind(x, y, z);
            Kind head = cells.kind(x, y + 1, z);
            if (feet == Kind.DANGER || head == Kind.DANGER) return -1;
            if (feet == Kind.SOLID) {
                // one block up is a jump, a wall two high (or a low ceiling over the step) is a wall. the cell over our own
                // head has to be open too or the jump just bonks
                Kind over = cells.kind(x, y + 2, z);
                if (!passable(head) || !passable(over)) return -1;
                if (!passable(cells.kind(lastX, y + 2, lastZ))) return -1;
                y++;
                if (s <= JUMP_RUNWAY) jump = true;
            } else {
                if (head == Kind.SOLID) return -1;
                if (feet != Kind.WATER) {
                    int ground = -1;
                    for (int drop = 1; drop <= MAX_DROP + 1; drop++) {
                        Kind below = cells.kind(x, y - drop, z);
                        if (below == Kind.DANGER) return -1;
                        if (below == Kind.SOLID || below == Kind.WATER) {
                            ground = y - drop + 1;
                            break;
                        }
                    }
                    if (ground < 0) return -1;
                    y = ground;
                }
            }
            lastX = x;
            lastZ = z;
        }
        return jump ? 1 : 0;
    }

    private static boolean passable(Kind k) {
        return k == Kind.OPEN || k == Kind.WATER;
    }

    // a cell a body can be in, both feet and head
    private static boolean free(Cells cells, int x, int y, int z) {
        return passable(cells.kind(x, y, z)) && passable(cells.kind(x, y + 1, z));
    }
}
