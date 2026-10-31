package adris.altoclef.tasks.speedrun.gamer.end;

import java.util.List;

// where the perched dragon's parts are and what they do to us, pure so the tests can sit a dragon down at any yaw. numbers
// from the 26.3 jar (EnderDragon.aiStep / tickPart / knockBack / hurt). the live task reads the real part boxes, this is the
// same geometry for the ring check and the tests
public final class DragonPerch {
    // the wings shove everything in wing.inflate(4, 2, 4).move(0, -2, 0) every tick, sitting or not
    public static final double WING_PUSH_XZ = 4;
    public static final double WING_PUSH_Y = 2;
    // head and neck grown by one bite for 10
    public static final double BITE = 1;
    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;
    // the flame cloud sits this far ahead of the head on the floor, and is this wide
    public static final double CLOUD_AHEAD = 2.5;
    public static final double CLOUD_RADIUS = 5;
    // bed explosion power 5, so damage dies out at twice that
    public static final double BED_REACH = 10;
    // the heal scan is the dragon's box grown by 32, the perched box is about 16 wide, so 8 + 32 from the pillar
    public static final double HEAL_REACH_XZ = 40;
    public static final double HEAL_REACH_UP = 8 + 32;
    public static final double HEAL_REACH_DOWN = 32;

    private DragonPerch() {
    }

    // an axis aligned box. AABB without the game, intersects is strict like vanilla's
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static Box centred(double cx, double bottomY, double cz, double width, double height) {
            double h = width / 2;
            return new Box(cx - h, bottomY, cz - h, cx + h, bottomY + height, cz + h);
        }

        public static Box cell(int x, int y, int z) {
            return new Box(x, y, z, x + 1, y + 1, z + 1);
        }

        public Box inflate(double x, double y, double z) {
            return new Box(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
        }

        public Box inflate(double d) {
            return inflate(d, d, d);
        }

        public Box move(double dx, double dy, double dz) {
            return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
        }

        public boolean intersects(Box o) {
            return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
        }

        public double centreX() {
            return (minX + maxX) / 2;
        }

        public double centreZ() {
            return (minZ + maxZ) / 2;
        }
    }

    public record Parts(Box head, Box neck, Box body, Box wing1, Box wing2) {
        // what checkWalls eats blocks with
        public List<Box> eaters() {
            return List.of(head, neck, body);
        }

        public List<Box> pushes() {
            return List.of(wingPush(wing1), wingPush(wing2));
        }

        public List<Box> biters() {
            return List.of(bite(head), bite(neck));
        }
    }

    // the parts of a dragon sitting at (x, y, z) with this yaw. y is the pillar top surface. the head points along
    // (sin r, -cos r), the dragon's yaw is backwards from everybody else's
    public static Parts sitting(double x, double y, double z, double yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        double fx = Math.sin(r);
        double fz = -Math.cos(r);
        double sx = Math.cos(r);
        double sz = Math.sin(r);
        Box head = Box.centred(x + fx * 6.5, y - 1, z + fz * 6.5, 1, 1);
        Box neck = Box.centred(x + fx * 5.5, y - 1, z + fz * 5.5, 3, 3);
        Box body = Box.centred(x + fx * 0.5, y, z + fz * 0.5, 5, 3);
        Box wing1 = Box.centred(x + sx * 4.5, y + 2, z + sz * 4.5, 4, 2);
        Box wing2 = Box.centred(x - sx * 4.5, y + 2, z - sz * 4.5, 4, 2);
        return new Parts(head, neck, body, wing1, wing2);
    }

    public static Box wingPush(Box wing) {
        return wing.inflate(WING_PUSH_XZ, WING_PUSH_Y, WING_PUSH_XZ).move(0, -WING_PUSH_Y, 0);
    }

    public static Box bite(Box headOrNeck) {
        return headOrNeck.inflate(BITE);
    }

    // a player standing with its feet at (x, y, z)
    public static Box player(double x, double y, double z) {
        return new Box(x - PLAYER_HALF_WIDTH, y, z - PLAYER_HALF_WIDTH, x + PLAYER_HALF_WIDTH, y + PLAYER_HEIGHT, z + PLAYER_HALF_WIDTH);
    }

    // the player box misses every wing push and bite box by more than the margin
    public static boolean standsClear(Box player, List<Box> pushes, List<Box> biters, double margin) {
        Box fat = player.inflate(margin);
        for (Box b : pushes) {
            if (fat.intersects(b)) return false;
        }
        for (Box b : biters) {
            if (fat.intersects(b)) return false;
        }
        return true;
    }

    // a bed going off this far from a part, unarmored, full exposure. (i^2 + i) / 2 * 7 * 2 * power + 1 with power 5
    public static double bedDamage(double distance) {
        double i = 1 - distance / BED_REACH;
        if (i <= 0) return 0;
        return (i * i + i) * 35 + 1;
    }

    // the way the head points, flat: from the dragon's position to the head. zero length gives north, it never happens
    public static double[] facing(double posX, double posZ, double headX, double headZ) {
        double dx = headX - posX;
        double dz = headZ - posZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return new double[]{0, -1};
        return new double[]{dx / len, dz / len};
    }

    // the flame cloud's middle, CLOUD_AHEAD past the head along the facing
    public static double[] cloudCentre(double posX, double posZ, double headX, double headZ) {
        double[] f = facing(posX, posZ, headX, headZ);
        return new double[]{headX + f[0] * CLOUD_AHEAD, headZ + f[1] * CLOUD_AHEAD};
    }

    // does a crystal here reach the perched dragon. (fx, perchY, fz) is the pillar top
    public static boolean healsPerch(double cx, double cy, double cz, double fx, double perchY, double fz) {
        return Math.abs(cx - fx) <= HEAL_REACH_XZ && Math.abs(cz - fz) <= HEAL_REACH_XZ
                && cy <= perchY + HEAL_REACH_UP && cy >= perchY - HEAL_REACH_DOWN;
    }
}
