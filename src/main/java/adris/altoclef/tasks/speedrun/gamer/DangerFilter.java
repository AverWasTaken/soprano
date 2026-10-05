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
import java.util.List;

// things the block tracker offers that are traps wearing a crafting table: trial chamber furniture (sits on copper),
// pillager outpost wool and logs, witch hut tables. the tracked blocks get blacklisted so the leaf tasks never walk
// to them. only looks at block types somebody already tracks (no extra scanning) and only every couple of seconds
public final class DangerFilter {
    private static final int EVERY_TICKS = 40;
    private static final double PILLAGER_RADIUS = 40;
    private static final double WITCH_RADIUS = 15;

    private int ticks;
    private Block[] copperBlocks;
    private Block[] beds;
    private Block[] logs;
    private Block[] wools;

    public void tick(AltoClef mod) {
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
        blacklistWitchOrPillageTables(mod, tracker);
        blacklistNearPillagers(mod, tracker);
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

    private void blacklistWitchOrPillageTables(AltoClef mod, BlockTracker tracker) {
        if (!tracker.isTracking(Blocks.CRAFTING_TABLE)) {
            return;
        }
        List<Vec3> witches = positions(mod.getEntityTracker().getTrackedEntities(Witch.class));
        for (BlockPos pos : tracker.getKnownLocations(Blocks.CRAFTING_TABLE)) {
            // outposts put white wool two above their tables
            boolean outpost = mod.getWorld().getBlockState(pos.above(2)).getBlock() == Blocks.WHITE_WOOL;
            if (outpost || nearAny(pos, witches, WITCH_RADIUS)) {
                blacklist(tracker, pos);
            }
        }
    }

    private void blacklistNearPillagers(AltoClef mod, BlockTracker tracker) {
        List<Vec3> pillagers = positions(mod.getEntityTracker().getTrackedEntities(Pillager.class));
        if (pillagers.isEmpty()) {
            return;
        }
        for (Block block : logs) {
            blacklistNear(tracker, block, pillagers);
        }
        for (Block block : wools) {
            blacklistNear(tracker, block, pillagers);
        }
    }

    private void blacklistNear(BlockTracker tracker, Block block, List<Vec3> pillagers) {
        if (!tracker.isTracking(block)) {
            return;
        }
        for (BlockPos pos : tracker.getKnownLocations(block)) {
            if (nearAny(pos, pillagers, PILLAGER_RADIUS)) {
                blacklist(tracker, pos);
            }
        }
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
