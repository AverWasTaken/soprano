package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.trackers.storage.ContainerCache;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

// what a smelt task knows about a furnace or smoker it is not looking at right now. an interrupt (the pickup, a mob, a death)
// restarts the task with empty caches, and "nothing in the caches" used to read as "nothing in the furnace": the bot mined
// the 37 raw iron it had already loaded, and fetched fuel for the coal that was already in the fuel slot. the container
// tracker's last look is the memory, the real slots decide the moment the screen is open again
final class StationMemory {
    private StationMemory() {
    }

    // what the slots showed beats the memory, and so does an open screen (a real zero). only a closed screen with nothing
    // ever seen falls back to the last look. a stale number is fine, the screen corrects it
    static double known(boolean screenOpen, double seen, double remembered) {
        return seen > 0 || screenOpen ? seen : Math.max(0, remembered);
    }

    // fuel still to find once the furnace's own is counted
    static double fuelStillNeeded(double needed, double inFurnace) {
        return Math.max(0, needed - inFurnace);
    }

    // raw stuff of ours in the last look at this spot. only a furnace we put down counts (a village's was never loaded by us)
    static int materialsRemembered(AltoClef mod, BlockPos at, ItemTarget materials) {
        if (at == null || !AsyncSmelting.isOurFurnace(at)) {
            return 0;
        }
        return mod.getItemStorage().getContainerAtPosition(at).map(c -> c.getItemCount(materials.getMatches())).orElse(0);
    }

    // smelts of fuel in the last look: the fuel slot, in the same units the smelt tasks count in. the cargo is not fuel even
    // when it burns (planks to smelt are not planks to burn)
    static double fuelRemembered(AltoClef mod, BlockPos at, ItemTarget materials) {
        if (at == null || !AsyncSmelting.isOurFurnace(at)) {
            return 0;
        }
        return mod.getItemStorage().getContainerAtPosition(at).map(c -> fuelOf(c, materials)).orElse(0.0);
    }

    // a table to craft the station on: in the bag, standing close, or the wood to make one on the spot
    static boolean tableAround(AltoClef mod) {
        if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                || mod.getItemStorage().hasItem(ItemHelper.LOG) || mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4) {
            return true;
        }
        Optional<BlockPos> table = mod.getBlockTracker().getNearestTracking(Blocks.CRAFTING_TABLE);
        return table.isPresent() && table.get().closerToCenterThan(mod.getPlayer().position(), 40);
    }

    // the stone for a furnace in the bag, whatever it comes in
    static int cobbleish(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
    }

    private static double fuelOf(ContainerCache cache, ItemTarget materials) {
        return cache.sumOver((item, count) -> {
            if (materials.matches(item) || !AltoSettings.isSupportedFuel(item)) {
                return 0.0;
            }
            return ItemHelper.getFuelAmount(new ItemStack(item, count));
        });
    }
}
