package adris.altoclef.util.baritone;

import adris.altoclef.util.helpers.CrowdRepulsion;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;

// the goal of a run the combat commitment owns: far from where it started AND clear of whoever is following. the plain
// crowd goal is done the moment everyone is a dozen blocks back, which is how a run turns into a jog. this one asks for
// the 50 as well, and CombatCommit still decides when the run is really over (it also wants two quiet seconds)
public class GoalCommittedRun extends GoalRunAwayFromCrowd {

    private final double _originX;
    private final double _originZ;
    private final double _farDistance;
    private final Supplier<List<Entity>> _crowd;

    public GoalCommittedRun(double originX, double originZ, double farDistance, double crowdDistance, Supplier<List<Entity>> crowd) {
        super(crowdDistance);
        _originX = originX;
        _originZ = originZ;
        _farDistance = farDistance;
        _crowd = crowd;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return CrowdRepulsion.farFromOrigin(_originX, _originZ, _farDistance, x + 0.5, z + 0.5) && super.isInGoal(x, y, z);
    }

    // both shortfalls are in ticks and both are zero exactly where isInGoal says yes, so the search is pulled the same way
    // by "too close to the crowd" and "too close to where we started"
    @Override
    public double heuristic(int x, int y, int z) {
        return super.heuristic(x, y, z) + CrowdRepulsion.originHeuristic(_originX, _originZ, _farDistance, x + 0.5, z + 0.5);
    }

    @Override
    protected List<Entity> getCrowd() {
        return _crowd.get();
    }
}
