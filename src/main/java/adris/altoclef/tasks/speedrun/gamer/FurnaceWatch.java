package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

// the game side of smelting in the background: keeps the job list honest (stale ones, furnaces that are gone) and hands out
// the task that goes back for the iron. no leash: the bot goes wherever the work is and remembers where the furnace is. one
// per phase that can have jobs (IRON, and PORTAL for the last look before we leave). the rules it follows are in FurnaceJobs
// and SmeltFiller, this is only the part that needs a world
public final class FurnaceWatch {
    private CollectFromFurnaceTask task;
    private RunState.FurnaceJob target;
    private String hud;
    // the IRON phase takes its furnace back once the last of the iron is out, so the next smelt can go down at the next work
    // site. PORTAL leaves it alone, it is on its way out
    private boolean pickUpWhenEmpty;
    private Task pickup;
    private BlockPos pickupAt;
    // what is coming down: the block kind it was (furnace or smoker) and the item it turns into
    private String pickupKind = "furnace";
    private Item pickupItem = Items.FURNACE;
    private boolean pickupBroken;
    private long pickupStart;

    // jobs come and go, every batch empties the list
    public void reset() {
        task = null;
        target = null;
        hud = null;
        pickup = null;
        pickupAt = null;
        pickupBroken = false;
    }

    // a fresh phase
    public void newPhase(boolean pickUpWhenEmpty) {
        reset();
        this.pickUpWhenEmpty = pickUpWhenEmpty;
    }

    // the furnace is coming down right now (the phase must not end under it)
    public boolean pickingUp() {
        return pickup != null;
    }

    // the furnace comes down after the last collect, run until the block is broken and the item is back in the bag
    public Task finishing(AltoClef mod, GamerContext ctx) {
        if (pickup == null) {
            return null;
        }
        if (!pickupBroken && pickup.isFinished(mod)) {
            pickupBroken = true;
            // only one of the two lists has this spot, removing from both is the cheap way to not care which
            RunState.Pos gone = new RunState.Pos(pickupAt.getX(), pickupAt.getY(), pickupAt.getZ());
            ctx.state().placedFurnaces.remove(gone);
            ctx.state().placedSmokers.remove(gone);
            pickup = new PickupDroppedItemTask(pickupItem, 1);
        }
        double elapsed = (ctx.facts().gameTime() - pickupStart) / 20.0;
        if ((pickupBroken && ctx.facts().has(pickupItem)) || elapsed > ctx.cfg().overworld.tablePickupSeconds) {
            Debug.logInternal(pickupKind + " pickup after the last collect: " + (pickupBroken ? "done" : "timed out") + " at " + pickupAt.toShortString());
            pickup = null;
            pickupAt = null;
            pickupBroken = false;
            hud = null;
            return null;
        }
        hud = "Picking up the " + pickupKind;
        return pickup;
    }

    // ours, a plain furnace or a smoker (never a village's blast furnace), nothing left in it, and we hold no spare. then it
    // comes with us. the rule itself is FurnaceJobs.mayTakeBack, this only adds "can we actually reach it"
    private boolean takeBack(AltoClef mod, GamerContext ctx, RunState.FurnaceJob job, int inputLeft) {
        Item item = itemOf(job.kind);
        if (!pickUpWhenEmpty || item == null
                || !FurnaceJobs.mayTakeBack(ctx.state(), job.kind, job.pos, inputLeft, ctx.facts().has(item))) {
            return false;
        }
        return WorldHelper.canBreak(mod, at(job));
    }

    private static Item itemOf(String kind) {
        return switch (kind) {
            case "furnace" -> Items.FURNACE;
            case "smoker" -> Items.SMOKER;
            default -> null;
        };
    }

    // plain words for what we are doing about the furnace, null when nothing
    public String hud() {
        return hud;
    }

    // every tick that has jobs: forget the old ones and the ones whose furnace is gone, and keep our own digging off the rest
    public void housekeeping(AltoClef mod, GamerContext ctx) {
        List<RunState.FurnaceJob> jobs = ctx.state().furnaceJobs;
        long now = ctx.facts().gameTime();
        List<RunState.FurnaceJob> gone = FurnaceJobs.dropStale(jobs, now, ctx.cfg().overworld.furnaceStaleSeconds);
        if (!gone.isEmpty()) {
            ctx.log("giving up on a furnace we loaded a long time ago");
        }
        for (RunState.FurnaceJob job : new ArrayList<>(ctx.facts().furnaceJobs())) {
            BlockPos at = at(job);
            if (mod.getChunkTracker().isChunkLoaded(at)
                    && !BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(at).getBlock()).getPath().equals(job.kind)) {
                // somebody (a creeper, us) took the furnace, and whatever was in it went with it
                jobs.remove(job);
                gone.add(job);
                ctx.log("the furnace at " + job.pos + " is gone, forgetting what was in it");
            } else {
                mod.getBehaviour().avoidBlockBreaking(at);
            }
        }
        if (!gone.isEmpty()) {
            if (target != null && gone.contains(target)) {
                task = null;
                target = null;
            }
            ctx.save();
        }
    }

    // the collect trip that is already under way (finishing it when it is done), null when there is none. a trip that
    // started keeps going whatever the plan says, it already walked there
    public Task active(AltoClef mod, GamerContext ctx) {
        if (task == null) {
            return null;
        }
        if (!task.isFinished(mod)) {
            return task;
        }
        RunState.FurnaceJob visited = target;
        if (visited == null) {
            // housekeeping dropped the job under the trip, nothing to re-stamp
            task = null;
            return null;
        }
        int left = task.inputLeft();
        long now = ctx.facts().gameTime();
        long guess = visited.doneTick - now;
        FurnaceJobs.afterVisit(ctx.state().furnaceJobs, target, left, task.leftTicks(), now);
        if (left > 0) {
            // the estimate assumed the chunk ticked the whole time we were away, now it is what the furnace itself says
            Debug.logInternal("furnace at " + visited.pos + " still has " + left + " cooking, ready in "
                    + Math.round(task.leftTicks() / 20.0) + " s (we had guessed " + Math.round(guess / 20.0) + " s), timer re-stamped");
        }
        ctx.progress("collected from the furnace");
        ctx.save();
        task = null;
        target = null;
        hud = null;
        if (takeBack(mod, ctx, visited, left)) {
            pickupAt = at(visited);
            pickupKind = visited.kind;
            pickupItem = itemOf(visited.kind);
            pickupStart = ctx.facts().gameTime();
            pickupBroken = false;
            pickup = new DestroyBlockTask(pickupAt);
            Debug.logInternal(pickupKind + " is empty, taking it back at " + pickupAt.toShortString());
            return finishing(mod, ctx);
        }
        return null;
    }

    // a new trip to the job that is ready first, null when there is no job here. NORMAL takes what is done, WAIT_ALL stays
    // until everything is out, TAKE_ALL is for leaving. why = what started the trip, it goes in the log
    public Task collect(AltoClef mod, GamerContext ctx, Mode mode, String why) {
        Task running = active(mod, ctx);
        if (running != null) {
            return running;
        }
        RunState.FurnaceJob job = FurnaceJobs.soonest(ctx.facts().furnaceJobs());
        if (job == null) {
            return null;
        }
        long now = ctx.facts().gameTime();
        long nearly = Math.round(ctx.cfg().overworld.furnaceWaitSeconds * 20);
        // the cap is how long past the estimate WAIT_ALL waits, a furnace that never finishes must not hold us for ever
        long cap = Math.max(0, job.doneTick - now) + 600;
        Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocation.withDefaultNamespace(job.kind));
        target = job;
        // once per trip: the task is handed back by active() from here on
        Debug.logInternal("collect trip to the " + job.kind + " at " + job.pos + ": " + why + " (" + mode + ", "
                + Math.round((job.doneTick - now) / 20.0) + " s to go by the estimate)");
        task = new CollectFromFurnaceTask(at(job), block, job.kind, mode, nearly, cap);
        hud = mode == Mode.WAIT_ALL ? "Waiting for the furnace" : "Collecting from the furnace";
        return task;
    }

    private static BlockPos at(RunState.FurnaceJob job) {
        return new BlockPos(job.pos.x, job.pos.y, job.pos.z);
    }
}
