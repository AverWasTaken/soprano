package adris.altoclef.tasks.resources;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.SmeltInBlastFurnaceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CollectIronIngotTask extends ResourceTask {

    private final int _count;
    private final SmeltRouter _router = new SmeltRouter();

    public CollectIronIngotTask(int count) {
        super(Items.IRON_INGOT, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(Blocks.FURNACE, Blocks.BLAST_FURNACE);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        SmeltTarget all = new SmeltTarget(new ItemTarget(Items.IRON_INGOT, _count), new ItemTarget(Items.RAW_IRON, _count));
        // a blast furnace that is already standing close (village armorer) beats a plain furnace, and works with
        // altoUseBlastFurnace off since that one is only about making our own
        Task nearby = _router.tryNearbyBlast(mod, all);
        if (nearby != null) {
            return nearby;
        }
        if (Baritone.settings().altoUseBlastFurnace.value) {
            if (mod.getItemStorage().hasItem(Items.BLAST_FURNACE) ||
                    mod.getBlockTracker().anyFound(Blocks.BLAST_FURNACE) ||
                    mod.getEntityTracker().itemDropped(Items.BLAST_FURNACE)) {
                return new SmeltInBlastFurnaceTask(all);
            }
            if (_count < 5) {
                return _router.furnace(all);
            }
            Optional<BlockPos> furnacePos = mod.getBlockTracker().getNearestTracking(Blocks.FURNACE);
            furnacePos.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
            if (mod.getItemStorage().getItemCount(Items.IRON_INGOT) >= 5) {
                return TaskCatalogue.getItemTask(Items.BLAST_FURNACE, 1);
            }
            return _router.furnace(new SmeltTarget(new ItemTarget(Items.IRON_INGOT, 5), new ItemTarget(Items.RAW_IRON, 5)));
        }
        return _router.furnace(all);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(Blocks.FURNACE, Blocks.BLAST_FURNACE);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectIronIngotTask && ((CollectIronIngotTask) other)._count == _count;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " iron.";
    }
}
