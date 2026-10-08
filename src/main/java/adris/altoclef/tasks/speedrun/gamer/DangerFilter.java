package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// things the block tracker offers that are traps wearing a crafting table: trial chamber furniture (sits on copper),
// pillager outpost wool and logs, witch hut tables. the tracked blocks get blacklisted so the leaf tasks never walk
// to them. only looks at block types somebody already tracks (no extra scanning) and only every couple of seconds
public final class DangerFilter {
    private static final int EVERY_TICKS = 40;
    private static final double PILLAGER_RADIUS = 40;
    private static final double WITCH_RADIUS = 15;

    // a minute of standing around: patrols walk, outposts do not
    private final PillagerWatch pillagerWatch = new PillagerWatch(1200);
    private int ticks;
    // logs and wool we banned because of an outpost, so we can unban exactly those when it expires
    private final Set<BlockPos> outpostBans = new HashSet<>();
    private Block[] copperBlocks;
    private Block[] beds;
    private Block[] logs;
    private Block[] wools;

    public void tick(AltoClef mod, RunState state) {
        if (++ticks % EVERY_TICKS != 0) {
            return;
        }
        BlockTracker tracker = mod.getBlockTracker();
        if (beds == null) {
            copperBlocks = ItemHelper.itemsToBlocks(ItemHelper.COPPER_BLOCKS);
            beds = ItemHelper.itemsToBlocks(ItemHelper.BED);
            logs = ItemHelper.itemsToBlocks(ItemHelper.LOG);
            wools = ItemHelper.itemsToBlocks(ItemHelper.WOOL);
        }
        blacklistOnCopper(mod, tracker, Blocks.CRAFTING_TABLE);
        blacklistOnCopper(mod, tracker, Blocks.CHEST);
        for (Block bed : beds) {
            blacklistOnCopper(mod, tracker, bed);
        }
        blacklistWitchOrPillageTables(mod, tracker, state);
        blacklistNearOutposts(mod, tracker);
    }

    // trial chambers are built on copper, a table or bed or chest on top of it is not ours to take
    private void blacklistOnCopper(AltoClef mod, BlockTracker tracker, Block block) {
        if (!tracker.isTracking(block)) {
            return;
        }
        for (BlockPos pos : tracker.getKnownLocations(block)) {
            Block below = mod.getWorld().getBlockState(pos.below()).getBlock();
            if (isCopper(below)) {
                blacklist(tracker, pos);
            }
        }
    }

    private void blacklistWitchOrPillageTables(AltoClef mod, BlockTracker tracker, RunState state) {
        if (!tracker.isTracking(Blocks.CRAFTING_TABLE)) {
            return;
        }
        List<Vec3> witches = positions(mod.getEntityTracker().getTrackedEntities(Witch.class));
        for (BlockPos pos : tracker.getKnownLocations(Blocks.CRAFTING_TABLE)) {
            // a table we put down ourselves is ours wherever the witch happens to be standing
            if (ownStation(state == null ? null : state.placedTables, pos)) {
                continue;
            }
            // outposts put white wool two above their tables
            boolean outpost = mod.getWorld().getBlockState(pos.above(2)).getBlock() == Blocks.WHITE_WOOL;
            if (outpost || nearAny(pos, witches, WITCH_RADIUS)) {
                blacklist(tracker, pos);
            }
        }
    }

    // only around an outpost (pillagers that stand still), a patrol passing through is not worth losing a forest over
    private void blacklistNearOutposts(AltoClef mod, BlockTracker tracker) {
        Map<Integer, double[]> pillagers = new HashMap<>();
        for (Pillager p : mod.getEntityTracker().getTrackedEntities(Pillager.class)) {
            // a dead pillager is not holding an outpost
            if (p.isAlive()) {
                pillagers.put(p.getId(), new double[]{p.getX(), p.getZ()});
            }
        }
        pillagerWatch.update(ticks, pillagers);
        liftExpiredBans(tracker);
        if (pillagerWatch.outposts() == 0) {
            return;
        }
        for (Block block : logs) {
            blacklistNear(tracker, block);
        }
        for (Block block : wools) {
            blacklistNear(tracker, block);
        }
    }

    private void blacklistNear(BlockTracker tracker, Block block) {
        if (!tracker.isTracking(block)) {
            return;
        }
        for (BlockPos pos : tracker.getKnownLocations(block)) {
            // only the bans we put there are ours to lift later, a block that was already unreachable stays that way
            if (pillagerWatch.nearOutpost(pos.getX(), pos.getZ(), PILLAGER_RADIUS) && !tracker.unreachable(pos)) {
                blacklist(tracker, pos);
                outpostBans.add(pos.immutable());
            }
        }
    }

    // an outpost nobody has seen a live pillager at for a few minutes is gone (cleared, or we left and it despawned them),
    // its logs and wool go back on the menu unless another outpost still covers them
    private void liftExpiredBans(BlockTracker tracker) {
        for (double[] gone : pillagerWatch.drainExpired()) {
            outpostBans.removeIf(pos -> {
                boolean ofThatOne = Math.hypot(pos.getX() - gone[0], pos.getZ() - gone[1]) <= PILLAGER_RADIUS;
                if (!ofThatOne || pillagerWatch.nearOutpost(pos.getX(), pos.getZ(), PILLAGER_RADIUS)) {
                    return false;
                }
                tracker.clearBlockUnreachable(pos);
                return true;
            });
        }
    }

    // pure so a test can poke it: is this block one the run itself placed
    public static boolean ownStation(List<RunState.Pos> placed, BlockPos pos) {
        return placed != null && placed.contains(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean isCopper(Block block) {
        for (Block copper : copperBlocks) {
            if (copper == block) {
                return true;
            }
        }
        return false;
    }

    private static void blacklist(BlockTracker tracker, BlockPos pos) {
        if (!tracker.unreachable(pos)) {
            tracker.requestBlockUnreachable(pos, 0);
        }
    }

    private static List<Vec3> positions(List<? extends Entity> entities) {
        List<Vec3> out = new ArrayList<>();
        for (Entity e : entities) {
            out.add(e.position());
        }
        return out;
    }

    public static boolean nearAny(BlockPos pos, List<Vec3> points, double radius) {
        for (Vec3 p : points) {
            if (pos.closerToCenterThan(p, radius)) {
                return true;
            }
        }
        return false;
    }
}
