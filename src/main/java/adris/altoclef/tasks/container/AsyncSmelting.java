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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

// the load-only half of smelting. when altoAsyncSmelting is on, the smelt tasks stop at "everything is in and lit", close
// the screen and say where they left it here, instead of standing at the furnace for the whole cook. the smelt tasks cannot
// see the gamer's RunState (and should not), so the jobs wait in this queue and GamerTask moves them into RunState.furnaceJobs
// at the start of its tick. iron and cooked food: the two smelts that are long AND something the planner knows how to wait on
public final class AsyncSmelting {
    private static final Set<String> OUTPUTS = Set.of("iron_ingot");
    // what the smoker and the furnace turn raw food into (the food task only ever asks for these, kelp is nobody's dinner)
    private static final Set<String> FOOD_OUTPUTS = Set.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken",
            "cooked_rabbit", "cooked_cod", "cooked_salmon", "baked_potato");
    private static final Queue<RunState.FurnaceJob> LOADED = new ConcurrentLinkedQueue<>();
    // the jobs the gamer is tracking, so the food task (which cannot see RunState) can count the meat that is cooking.
    // non gamer runs never set it and see no jobs, which is the old behaviour
    private static volatile Supplier<List<RunState.FurnaceJob>> jobs = List::of;

    private AsyncSmelting() {
    }

    // setting on and the thing coming out is one we have a plan for
    public static boolean wants(ItemTarget output) {
        Item[] matches = output.getMatches();
        if (matches.length != 1) {
            return false;
        }
        return wantsName(name(matches[0]), Baritone.settings().altoAsyncSmelting.value, Baritone.settings().altoAsyncCooking.value);
    }

    // the same decision without the settings or the registry, for the tests. food needs BOTH switches: smelting is the one
    // the phases flip when they can come back for a furnace, cooking is the user's "but not for the meat"
    public static boolean wantsName(String output, boolean smelting, boolean cooking) {
        if (!smelting) {
            return false;
        }
        return OUTPUTS.contains(output) || (cooking && FOOD_OUTPUTS.contains(output));
    }

    // GamerTask hands over where its jobs live (and takes it back with clear())
    public static void watchJobs(Supplier<List<RunState.FurnaceJob>> source) {
        jobs = source;
    }

    // nutrition cooking in the background right now. the food task adds it to what it could make from the bag, so a loaded
    // smoker is food on the way and not a reason to go hunting again
    public static int pendingFoodUnits() {
        return FurnaceJobs.pendingUnits(jobs.get());
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
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()),
                WorldHelper.getCurrentDimension().name(), kindName, name(input.getItem()), input.getCount(),
                name(output.getMatches()[0]), now, FurnaceJobs.doneTick(kindName, now, input.getCount()));
        FoodProperties food = output.getMatches()[0].components().get(DataComponents.FOOD);
        job.unitsEach = food == null || !FOOD_OUTPUTS.contains(job.output) ? 0 : food.nutrition();
        LOADED.add(job);
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
        jobs = List::of;
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
