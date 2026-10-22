package adris.altoclef.tasks.speedrun.gamer.end;

// the pure half of waiting for the dragon to perch (WaitForDragonAndPearlTask): what we do from what is in the bag right
// now, where we stand on the ground, and which way we step out of the breath. no state, asked every tick
public final class DragonWaitRules {

    // the ground wait ring around the fountain. inside the 15 the wait task needs to call a perch "close enough" for the bed
    // cycle to run, and far enough out that the head and wings landing on the portal do not land on us
    public static final double GROUND_MIN = 8;
    public static final double GROUND_MAX = 12;
    public static final double GROUND_AIM = 10;
    // a cloud is stood in inside its radius (it grows from 3 to 7 over its life), so we leave a bit before the edge and go
    // well past it, or the walk back to the ring turns around at the rim
    public static final double CLOUD_TRIGGER_MARGIN = 1.5;
    public static final double CLOUD_EXIT_MARGIN = 3;
    // a fireball is dodged sideways when we are ahead of it and this close to its line
    public static final double FIREBALL_LINE_RANGE = 4;
    public static final double FIREBALL_SIDESTEP = 6;

    private DragonWaitRules() {
    }

    public enum Mode {
        // a pearl to throw: pillar up and throw it at the fountain when the dragon perches
        PILLAR,
        // no pearl: stay on the ground near the fountain and walk to the bed spot when the dragon perches
        GROUND
    }

    // pearls in the bag this tick, nothing remembered. a thrown pearl is gone next tick and so is the pillar plan
    public static Mode mode(int pearls) {
        return pearls > 0 ? Mode.PILLAR : Mode.GROUND;
    }

    public record Spot(double x, double z) {
    }

    public static boolean inGroundRing(double px, double pz, double fx, double fz) {
        double d = Math.hypot(px - fx, pz - fz);
        return d >= GROUND_MIN && d <= GROUND_MAX;
    }

    // where to stand: here if already in the ring, else GROUND_AIM out from the fountain on the side we are on
    public static Spot groundSpot(double px, double pz, double fx, double fz) {
        if (inGroundRing(px, pz, fx, fz)) return new Spot(px, pz);
        double dx = px - fx;
        double dz = pz - fz;
        double len = Math.hypot(dx, dz);
        // dead center: any side will do, east is where the arrival platform is
        if (len < 1) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new Spot(fx + dx / len * GROUND_AIM, fz + dz / len * GROUND_AIM);
    }

    // a spot to stand clear of a breath cloud of this radius, null when we are not near its edge. straight away from the
    // middle, past the rim by CLOUD_EXIT_MARGIN
    public static Spot clearOfCloud(double px, double pz, double cx, double cz, double radius) {
        double dx = px - cx;
        double dz = pz - cz;
        double len = Math.hypot(dx, dz);
        if (len > radius + CLOUD_TRIGGER_MARGIN) return null;
        // right on top of it, any way out is the right way out
        if (len < 0.1) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        double out = radius + CLOUD_EXIT_MARGIN;
        return new Spot(cx + dx / len * out, cz + dz / len * out);
    }

    // a fireball flies at somebody, so away from where it is now is along its line, into where it lands. sideways is out of it:
    // null when it is behind us, going nowhere, or the line misses us by more than FIREBALL_LINE_RANGE
    public static Spot dodgeFireball(double px, double pz, double fx, double fz, double vx, double vz) {
        double speed = Math.hypot(vx, vz);
        if (speed < 1e-3) return null;
        double ox = px - fx;
        double oz = pz - fz;
        double ahead = (ox * vx + oz * vz) / speed;
        if (ahead < 0) return null;
        // signed distance off the line, positive when we are to its left
        double side = (vx * oz - vz * ox) / speed;
        if (Math.abs(side) > FIREBALL_LINE_RANGE) return null;
        // exactly on the line: left is as good as right
        double dir = side >= 0 ? 1 : -1;
        double nx = -vz / speed * dir;
        double nz = vx / speed * dir;
        return new Spot(px + nx * FIREBALL_SIDESTEP, pz + nz * FIREBALL_SIDESTEP);
    }
}
