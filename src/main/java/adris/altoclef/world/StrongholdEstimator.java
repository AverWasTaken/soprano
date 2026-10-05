package adris.altoclef.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// STUB of the contract (gamer-design.md 5.6). the estimator worker replaces the bodies, the signatures are what
// the stronghold worker codes against. pure math, no minecraft types
public final class StrongholdEstimator {
    // origin and unit direction in XZ, dived = the eye sank into the ground (target within ~12 blocks)
    public record Ray(double ox, double oz, double dx, double dz, boolean dived) {
    }

    public record Estimate(double x, double z, double radius, double confidence, int rays, boolean snapped, int ring) {
    }

    public enum Step {THROW, WALK, ARRIVE, DIG}

    // x,z = where to walk / stand / dig, why = one line for the log
    public record Advice(Step step, double x, double z, String why) {
    }

    public record Params(double sigmaDeg, int maxRays, double snapRadius) {
        public static Params defaults() {
            return new Params(0.25, 12, 12);
        }
    }

    private final Params params;
    private final List<Ray> rays = new ArrayList<>();

    public StrongholdEstimator(Params params) {
        this.params = params;
    }

    // false = rejected (parallel to everything, points the wrong way, or an outlier against the current fit)
    public boolean addRay(Ray ray) {
        rays.add(ray);
        return true;
    }

    public Optional<Estimate> estimate() {
        return Optional.empty();
    }

    // walkedSinceLastThrow in blocks
    public Advice advise(double playerX, double playerZ, double walkedSinceLastThrow) {
        return new Advice(Step.THROW, playerX, playerZ, "stub");
    }

    public List<Ray> rays() {
        return List.copyOf(rays);
    }

    public void clear() {
        rays.clear();
    }

    // direction from the throw position to where the eye was seen, normalised
    public static Ray rayFromPoints(double originX, double originZ, double eyeX, double eyeZ, boolean dived) {
        double dx = eyeX - originX;
        double dz = eyeZ - originZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        return new Ray(originX, originZ, len == 0 ? 0 : dx / len, len == 0 ? 0 : dz / len, dived);
    }
}
