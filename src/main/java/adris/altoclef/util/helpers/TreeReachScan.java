package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

// the world half of TreeReach: reads the column under a log and the cells around it, and remembers the answer for a couple of
// seconds. the log pick asks about the nearest few logs every tick, and the floor scan is a column of world reads
public final class TreeReachScan {
    // how far under the log we look for the floor. past this it is not coming back
    private static final int SCAN_DOWN = 24;
    private static final int CACHE_TICKS = 40;
    // a dirt block that is lying around, in the box around us. one look per few seconds, it is a few thousand reads
    private static final int DIRT_RADIUS = 8;
    private static final int DIRT_UP = 2;
    private static final int DIRT_DOWN = 4;
    private static final int DIRT_LOOK_TICKS = 100;

    // flagged: WorldHelper.dangerousToBreakIfRightAbove. fromGround and gap are only worth reading when it is
    public record Look(boolean flagged, boolean fromGround, int gap) {
    }

    private final Map<BlockPos, Look> looks = new HashMap<>();
    private int looksAt = Integer.MIN_VALUE;
    private int dirtAt = Integer.MIN_VALUE;
    private boolean dirtNear;

    public Look look(AltoClef mod, BlockPos log, int now) {
        if (now - looksAt >= CACHE_TICKS || now < looksAt) {
            looks.clear();
            looksAt = now;
        }
        return looks.computeIfAbsent(log.immutable(), at -> read(mod, at));
    }

    private static Look read(AltoClef mod, BlockPos log) {
        // an unloaded chunk reads as air all the way down, which is a "no floor" for a log that has one. not knowing is not a reason to skip
        if (!loadedAround(mod, log)) {
            return new Look(false, true, 0);
        }
        boolean flagged = WorldHelper.dangerousToBreakIfRightAbove(mod, log);
        if (!flagged) {
            return new Look(false, true, 0);
        }
        int floor = floorUnder(mod, log);
        return new Look(true, TreeReach.fromGround(log.getY(), floor, sideStandable(mod, log)), TreeReach.gap(log.getY(), floor));
    }

    private static boolean loadedAround(AltoClef mod, BlockPos log) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (!mod.getWorld().isLoaded(log.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        return mod.getWorld().isLoaded(log);
    }

    // the top of the first thing we can stand on under the log (water counts, we can swim in it and mine from there).
    // lava and a column with nothing in it have no floor
    private static int floorUnder(AltoClef mod, BlockPos log) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= SCAN_DOWN; dy++) {
            at.set(log.getX(), log.getY() - dy, log.getZ());
            BlockState state = mod.getWorld().getBlockState(at);
            if (MovementHelper.isLava(state)) {
                return TreeReach.NO_FLOOR;
            }
            if (MovementHelper.isWater(state) || WorldHelper.isSolid(mod, at)) {
                return at.getY();
            }
        }
        return TreeReach.NO_FLOOR;
    }

    // a neighbouring column with a floor at the log's height or within two under it: the sides GoalMineFromSide takes
    private static boolean sideStandable(AltoClef mod, BlockPos log) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 0; dy >= -2; dy--) {
                    at.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                    if (WorldHelper.isSolid(mod, at) || MovementHelper.isWater(mod.getWorld().getBlockState(at))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // is there dirt close enough to be worth digging before a tower. grass blocks drop dirt too
    public boolean dirtAround(AltoClef mod, int now) {
        if (now - dirtAt < DIRT_LOOK_TICKS && now >= dirtAt) {
            return dirtNear;
        }
        dirtAt = now;
        dirtNear = scanDirt(mod);
        return dirtNear;
    }

    private static boolean scanDirt(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -DIRT_RADIUS; dx <= DIRT_RADIUS; dx++) {
            for (int dz = -DIRT_RADIUS; dz <= DIRT_RADIUS; dz++) {
                for (int dy = DIRT_UP; dy >= -DIRT_DOWN; dy--) {
                    at.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    BlockState state = mod.getWorld().getBlockState(at);
                    if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void forget() {
        looks.clear();
        looksAt = Integer.MIN_VALUE;
        dirtAt = Integer.MIN_VALUE;
    }
}
