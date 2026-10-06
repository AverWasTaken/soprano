package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// the load-only half of smelting. when altoAsyncSmelting is on, the smelt tasks stop at "everything is in and lit", close
// the screen and say where they left it here, instead of standing at the furnace for the whole cook. the smelt tasks cannot
// see the gamer's RunState (and should not), so the jobs wait in this queue and GamerTask moves them into RunState.furnaceJobs
// at the start of its tick. only iron for now: it is the one smelt that is long AND something the planner knows how to wait on
public final class AsyncSmelting {
    private static final Set<String> OUTPUTS = Set.of("iron_ingot");
    private static final Queue<RunState.FurnaceJob> LOADED = new ConcurrentLinkedQueue<>();

    private AsyncSmelting() {
    }

    // setting on and the thing coming out is one we have a plan for
    public static boolean wants(ItemTarget output) {
        if (!Baritone.settings().altoAsyncSmelting.value) {
            return false;
        }
        Item[] matches = output.getMatches();
        return matches.length == 1 && OUTPUTS.contains(name(matches[0]));
    }

    // is there enough fuel in (the lit item plus the fuel slot) to cook everything in the input slot without us. the numbers
    // are smelts, same units as the rest of the smelt task, so a coal is 8
    public static boolean fuelCovers(ItemStack fuelSlot, double litFuel, int inputCount) {
        double slot = fuelSlot.isEmpty() ? 0 : ItemHelper.getFuelAmount(fuelSlot);
        return Math.max(litFuel, 0) + slot >= inputCount;
    }

    // everything is in: remember the furnace and let go of the screen
    public static void loaded(AltoClef mod, BlockPos pos, Block kind, ItemStack input, ItemTarget output) {
        long now = mod.getWorld().getGameTime();
        String kindName = BuiltInRegistries.BLOCK.getKey(kind).getPath();
        LOADED.add(new RunState.FurnaceJob(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()),
                WorldHelper.getCurrentDimension().name(), kindName, name(input.getItem()), input.getCount(),
                name(output.getMatches()[0]), now, FurnaceJobs.doneTick(kindName, now, input.getCount())));
        StorageHelper.closeScreen();
    }

    // what was loaded since the last call. the gamer's tick is the only reader
    public static List<RunState.FurnaceJob> drain() {
        List<RunState.FurnaceJob> out = new ArrayList<>();
        RunState.FurnaceJob job;
        while ((job = LOADED.poll()) != null) {
            out.add(job);
        }
        return out;
    }

    // a new run must not inherit a job from the one before it
    public static void clear() {
        LOADED.clear();
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
