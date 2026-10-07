package adris.altoclef.util.helpers;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

// the pure parts of "where can i actually bucket this". everything takes fluid states (or a lookup of them) so a test
// can drive it without a world
public final class FluidSources {

    private FluidSources() {
    }

    // a source with none of the same fluid on top of it. this is the only kind of water or lava that a scan budget
    // should be spent on: flowing and submerged ones are most of any lake, and none of them can be bucketed from the
    // top anyway (see WorldHelper.isSourceBlock, it throws the same ones out)
    public static boolean exposedSource(FluidState self, FluidState above) {
        return self.isSource() && !above.getType().isSame(self.getType());
    }

    // follows a falling column or a flowing stream back to what feeds it. up while there is fluid on top, and at the
    // top, if it is not a source, sideways to the neighbour with the most fluid (flow only ever spreads from a taller
    // level to a shorter one, so that is where it came from). maxMoves is the cap on all of that together, a stream
    // that wanders off for 40 blocks is not worth chasing
    public static Optional<BlockPos> climbToSource(Function<BlockPos, FluidState> fluidAt, BlockPos start, int maxMoves) {
        FluidState first = fluidAt.apply(start);
        if (first.isEmpty()) return Optional.empty();
        Fluid kind = first.getType();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos pos = start;
        for (int moves = 0; moves <= maxMoves; moves++) {
            FluidState here = fluidAt.apply(pos);
            if (!sameFluid(here, kind) || !seen.add(pos)) return Optional.empty();
            BlockPos up = pos.above();
            if (sameFluid(fluidAt.apply(up), kind)) {
                pos = up;
                continue;
            }
            if (here.isSource()) return Optional.of(pos);
            BlockPos feeder = null;
            int tallest = here.getAmount();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(side);
                FluidState there = fluidAt.apply(next);
                if (sameFluid(there, kind) && there.getAmount() > tallest) {
                    tallest = there.getAmount();
                    feeder = next;
                }
            }
            if (feeder == null) return Optional.empty();
            pos = feeder;
        }
        return Optional.empty();
    }

    private static boolean sameFluid(FluidState state, Fluid kind) {
        return !state.isEmpty() && state.getType().isSame(kind);
    }
}
