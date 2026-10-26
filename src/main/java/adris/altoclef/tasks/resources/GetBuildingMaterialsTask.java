package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.item.Item;

import java.util.Arrays;

public class GetBuildingMaterialsTask extends Task {
    private final int _count;

    public GetBuildingMaterialsTask(int count) {
        _count = count;
    }

    @Override
    protected void onStart(AltoClef mod) {

    }

    @Override
    protected Task onTick(AltoClef mod) {
        // the count takes any build block, the mining only goes for the core ones. granite and friends took a slot each
        // for one or two blocks and the bag filled up with fragments
        Item[] core = Arrays.stream(StorageHelper.getBuildingMaterials(mod)).filter(ItemHelper::isCoreBuildBlock).toArray(Item[]::new);
        if (core.length == 0) {
            core = StorageHelper.getBuildingMaterials(mod);
        }
        return new MineAndCollectTask(new ItemTarget[]{new ItemTarget(core, _count)}, MiningRequirement.WOOD).noReserve();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetBuildingMaterialsTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return StorageHelper.getBuildingMaterialCount(mod) >= _count;
    }

    @Override
    protected String toHudString() {
        return "Getting " + _count + " building blocks";
    }

    @Override
    protected String toDebugString() {
        return "Collecting " + _count + " building materials.";
    }
}
