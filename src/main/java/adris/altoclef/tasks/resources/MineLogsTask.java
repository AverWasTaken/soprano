package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import baritone.api.process.IMineProcess;
import net.minecraft.world.level.block.Block;

// logs are what baritone's own #mine has always been good at: it picks the tree, breaks up the trunk and walks over the
// drops (mineScanDroppedItems). MineAndCollect grew an anchor, a drop lock, a drop wait and the sand-column side mining on
// top of the generic block breaker and still pillared up trees and hopped between them, so logs just hand it the job
public class MineLogsTask extends ResourceTask {
    private static final Block[] LOGS = ItemHelper.itemsToBlocks(ItemHelper.LOG);

    private final int _count;

    public MineLogsTask(int count) {
        super(new ItemTarget(ItemHelper.LOG, count));
        _count = count;
    }

    // the mine process picks its own drops up, a pickup task from ResourceTask would fight it for the pather
    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return true;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        IMineProcess mine = mod.getClientBaritone().getMineProcess();
        // started once, and again when something else (a fight, eating) took the pather and it let go
        if (!mine.isActive()) {
            mine.mine(_count, LOGS);
        }
        setDebugState("Mining logs");
        return null;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getMineProcess().onLostControl();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof MineLogsTask task && task._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Mining " + _count + " logs";
    }
}
