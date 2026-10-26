package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

// topping the water bucket back up when there is a pond right there (and the end prep gate, which looks further). the mlg clutch spends it, and the next time it is
// needed (lava, a fall) is usually twenty minutes later and nowhere near water. the rules are pure, the chain hands in what it sees
public final class WaterUpkeep {

    private WaterUpkeep() {
    }

    // a pond further than this is a trip, and trips belong to the kit planner (the end prep gate takes a longer one on a clock)
    public static final double RANGE = 16;
    // one go at it, then the pond gets a rest. 15 s is a walk of 16 blocks plus the scoop with room to spare
    public static final int TIMEOUT_TICKS = 300;
    // after any failure, 60 s before the next go
    public static final int COOLDOWN_TICKS = 1200;
    // looking for the pond walks a cube of blocks, once in a while is plenty
    public static final int LOOK_TICKS = 40;

    // only inside a gamer run, and not where it juggles buckets on purpose: the portal cast (water and lava buckets in and
    // out), and the end where EndGear does its own water. outside a run (null) the user is driving and gets no surprise detours
    public static boolean phaseAllows(GamerPhase phase) {
        if (phase == null) {
            return false;
        }
        return switch (phase) {
            case PORTAL, END_PREP, DRAGON, DONE, STUCK -> false;
            default -> true;
        };
    }

    // calm = nothing else has the bot (no clutch, cleanup, swim up, meal, fire). room = the full bucket has a slot to land in
    public static boolean wanted(boolean emptyBucket, boolean waterBucket, boolean evaporates, boolean threat, boolean calm,
                                 boolean room, GamerPhase phase) {
        return emptyBucket && !waterBucket && !evaporates && !threat && calm && room && phaseAllows(phase);
    }

    public static boolean coolingDown(int now, int failedAt) {
        // the tick counter restarts on a reconnect, a stamp from before it is old news
        return GameClock.since(failedAt, now) < COOLDOWN_TICKS;
    }

    public static boolean lookedRecently(int now, int lookedAt) {
        return GameClock.since(lookedAt, now) < LOOK_TICKS;
    }

    public static boolean timedOut(int now, int startedAt) {
        return GameClock.since(startedAt, now) >= TIMEOUT_TICKS;
    }

    // water we can scoop from above: a source with open air over it
    public static boolean pondSource(AltoClef mod, BlockPos p) {
        return BucketPour.isWaterSource(mod.getWorld(), p)
                && FluidSources.exposedSource(mod.getWorld().getFluidState(p), mod.getWorld().getFluidState(p.above()))
                && mod.getWorld().getBlockState(p.above()).getCollisionShape(mod.getWorld(), p.above()).isEmpty();
    }

    // nearest pond source within range (and 8 up or down), straight line. a cube walk, which is why callers only ask
    // once in a while. skip = ponds that already ate their clock
    public static BlockPos nearestPond(AltoClef mod, int range, Predicate<BlockPos> skip) {
        BlockPos me = mod.getPlayer().blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = (double) range * range;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > bestDist) {
                        continue;
                    }
                    p.set(me.getX() + dx, me.getY() + dy, me.getZ() + dz);
                    if (pondSource(mod, p) && !mod.getBlockTracker().unreachable(p) && !skip.test(p)) {
                        best = p.immutable();
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }
}
