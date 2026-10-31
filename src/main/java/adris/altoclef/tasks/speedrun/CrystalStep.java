package adris.altoclef.tasks.speedrun;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.DoToClosestEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// the sword fight's crystal run, pulled out so the bed fight can do it first: get a block to pillar with, walk (and pillar)
// up next to a crystal and smack it once it is in reach. same steps as it always was, only which crystals is up to the caller
public class CrystalStep {
    private static final int MINIMUM_BUILDING_BLOCKS = 1;
    private final Task _collectBuildMaterialsTask = new MineAndCollectTask(new ItemTarget(Items.END_STONE, 100), new Block[]{Blocks.END_STONE}, MiningRequirement.WOOD);

    // null when no crystal we care about is left (or we can't get blocks for it), else the step
    public Task tick(AltoClef mod, Predicate<Entity> which, Consumer<String> debug) {
        boolean any = mod.getEntityTracker().entityFound(which, EndCrystal.class);
        if (any && mod.getItemStorage().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.END_STONE) < MINIMUM_BUILDING_BLOCKS
                || (_collectBuildMaterialsTask.isActive() && !_collectBuildMaterialsTask.isFinished(mod))) {
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) {
                mod.getBehaviour().addProtectedItems(Items.END_STONE);
                debug.accept("Collecting building blocks to pillar to crystals");
                return _collectBuildMaterialsTask;
            }
        } else {
            mod.getBehaviour().removeProtectedItems(Items.END_STONE);
        }
        if (!any) {
            return null;
        }
        debug.accept("Kamakazeeing crystals");
        return new DoToClosestEntityTask(
                (toDestroy) -> {
                    if (toDestroy.closerThan(mod.getPlayer(), 7)) {
                        mod.getControllerExtras().attack(toDestroy);
                    }
                    // Go next to the crystal, arbitrary where we just need to get close.
                    return new GetToBlockTask(toDestroy.blockPosition().offset(1, 0, 0), false);
                },
                which,
                EndCrystal.class
        );
    }
}
