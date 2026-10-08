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
import java.util.function.Predicate;
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

    // is this furnace one the gamer put down this run. same seam as the jobs: the smelt task decides between "walk to the
    // furnace I remember" and "place another" and has to know whose it is. non gamer runs see none, which is the old behaviour
    private static volatile Predicate<BlockPos> ours = pos -> false;

    // the game tick a smelt task last had its furnace screen in hand (moving ore, fuel, taking output). the station pickup
    // preempts whatever the kit task is doing, and the screen closing under a half done load is how 37 raw iron got left in a
    // furnace that then got forgotten. -1 = never
    private static volatile long lastWork = -1;

    private AsyncSmelting() {
    }

    // a smelt task is "finished" two ways: it loaded the furnace and walked off (the job is the memory now), or the bag already
    // holds the item it was asked for. the second one is not a load: a cooked batch collected from the same smoker meets the
    // target and the raw meat is still in the bag. whoever waits on the task has to tell them apart
    public interface Handoff {
        boolean handedOff();

        // the task is being dropped without having handed off (the cook gave up on a long fuel trip) and may have left its input in
        // the station. say so, or nobody ever goes back for it (leftBehind)
        // true when a job was left for it
        default boolean recordLeftBehind(AltoClef mod) {
            return false;
        }
    }

    // the smelt tasks call this every tick the screen is open and they are working it
    public static void working(long now) {
        lastWork = now;
    }

    public static long lastWork() {
        return lastWork;
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

    public static void watchFurnaces(Predicate<BlockPos> source) {
        ours = source;
    }

    public static boolean isOurFurnace(BlockPos pos) {
        return ours.test(pos);
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
        job.unitsEach = unitsEach(output);
        LOADED.add(job);
        StorageHelper.closeScreen();
    }

    private static int unitsEach(ItemTarget output) {
        FoodProperties food = output.getMatches()[0].components().get(DataComponents.FOOD);
        return food == null || !FOOD_OUTPUTS.contains(name(output.getMatches()[0])) ? 0 : food.nutrition();
    }

    // the cook gave up with the meat already in the station and never lit it (the coal trip ran past its patience), so there is no
    // load to hand off and nobody knows the station holds anything. the job it leaves is due right now: the next visit finds the
    // station unlit, takes the meat back out and the planner cooks it again when the backoff is over. a lit one just gets waited on
    public static void leftBehind(AltoClef mod, BlockPos pos, Block kind, ItemStack input, ItemTarget output) {
        long now = mod.getWorld().getGameTime();
        RunState.FurnaceJob job = strandedJob(new RunState.Pos(pos.getX(), pos.getY(), pos.getZ()), WorldHelper.getCurrentDimension().name(),
                BuiltInRegistries.BLOCK.getKey(kind).getPath(), name(input.getItem()), input.getCount(), name(output.getMatches()[0]),
                unitsEach(output), now);
        LOADED.add(job);
    }

    // a job that is due the moment it is made, count read off the station's input slot
    public static RunState.FurnaceJob strandedJob(RunState.Pos pos, String dimension, String kind, String input, int count, String output,
                                                  int unitsEach, long now) {
        RunState.FurnaceJob job = new RunState.FurnaceJob(pos, dimension, kind, input, count, output, now, now);
        job.unitsEach = unitsEach;
        return job;
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
        lastWork = -1;
        jobs = List::of;
        ours = pos -> false;
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
