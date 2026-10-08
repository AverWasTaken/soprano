package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.CookRawFoodTask;
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
    // site. PORTAL takes the one it just emptied too (newExitPhase), but never loads meat into it
    private boolean pickUpWhenEmpty;
    private boolean cookHere = true;
    private Task pickup;
    private BlockPos pickupAt;
    // what is coming down: the block kind it was (furnace or smoker) and the item it turns into
    private String pickupKind = "furnace";
    private Item pickupItem = Items.FURNACE;
    private boolean pickupBroken;
    private long pickupStart;
    // the furnace we just emptied is standing right here and we carry raw meat: it cooks that before it comes down
    private Task cook;
    private long cookStart;

    // jobs come and go, every batch empties the list
    public void reset() {
        cook = null;
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
        cookHere = pickUpWhenEmpty;
    }

    // a phase that only wants the station off the ground on its way out: it would not come back for a furnace that gets
    // loaded with meat here, so it never loads one
    public void newExitPhase() {
        newPhase(true);
        cookHere = false;
    }

    // the cook that reuses the furnace we just emptied leaves it cooking right where it stands. when that is the bottom of
    // a mine and the next work is not down there, it is a trip back (the smoker at y 32 that cost 40 s): the caller says no
    public void mayCookHere(boolean yes) {
        cookHere = yes;
    }

    // the furnace is coming down right now (the phase must not end under it)
    public boolean pickingUp() {
        return pickup != null || cook != null;
    }

    // the furnace comes down after the last collect, run until the block is broken and the item is back in the bag
    public Task finishing(AltoClef mod, GamerContext ctx) {
        if (cook != null) {
            // loaded is done (the job lands in the state next tick), and a cook that drags on is not worth holding the furnace for
            boolean finished = cook.isFinished(mod);
            if (finished || (ctx.facts().gameTime() - cookStart) / 20.0 > ctx.cfg().overworld.tablePickupSeconds) {
                // timed out part way (a long coal trip) with the meat already in the furnace: it is not a job yet, and breaking the
                // furnace now would spill it. leave a job, the furnace stays and the visit it brings takes the furnace back
                boolean stranded = !finished && cook instanceof CookRawFoodTask raw && raw.recordLeftBehind(mod);
                cook = null;
                hud = null;
                if (stranded) {
                    // same backoff as the cook's own give up, or the visit that takes the meat back out finds an empty furnace we
                    // are standing at and starts the same cook (and the same coal trip) all over again
                    CookTrip.suspend(ctx.facts().gameTime());
                    Debug.logInternal(pickupKind + " still has our meat in it, leaving it for the job instead of breaking it at " + pickupAt.toShortString());
                    pickupAt = null;
                    return null;
                }
                // loaded: the job is the memory now and its last collect takes the furnace back. not loaded (gave up, timed
                // out, the meat was gone): it was coming down anyway, so it still does, or it stood there for the rest of the run
                RunState.Pos spot = new RunState.Pos(pickupAt.getX(), pickupAt.getY(), pickupAt.getZ());
                boolean stillOurs = FurnaceJobs.mayTakeBack(ctx.state(), pickupKind, spot, 0, ctx.facts().has(pickupItem));
                if (!stillOurs || !WorldHelper.canBreak(mod, pickupAt)) {
                    pickupAt = null;
                    return null;
                }
                Debug.logInternal(pickupKind + " did not get the meat, taking it back at " + pickupAt.toShortString());
                startPickup(ctx);
            } else {
                hud = "Cooking the meat while the furnace is free";
                return cook;
            }
        }
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
        int before = visited.count;
        FurnaceJobs.afterVisit(ctx.state().furnaceJobs, target, left, task.leftTicks(), now, task.cappedOut());
        if (left > 0) {
            // the estimate assumed the chunk ticked the whole time we were away, now it is what the furnace itself says
            Debug.logInternal("furnace at " + visited.pos + " still has " + left + " cooking, ready in "
                    + Math.round(task.leftTicks() / 20.0) + " s (we had guessed " + Math.round(guess / 20.0) + " s), timer re-stamped"
                    + (visited.stalls > 0 ? " (" + visited.stalls + " visits with nothing coming out)" : ""));
        }
        // only a trip that got something out (or emptied it) is progress. a capped wait that saw the same count it started
        // with used to count too, and with the fresh timer above every wait after it looked honest, so a furnace that never
        // finished held the phase for ever
        if (left < before) {
            ctx.progress("collected from the furnace");
        }
        ctx.save();
        task = null;
        target = null;
        hud = null;
        if (takeBack(mod, ctx, visited, left)) {
            // the cook needs to know where it is too: one that comes to nothing still takes the furnace back after
            pickupAt = at(visited);
            pickupKind = visited.kind;
            pickupItem = itemOf(visited.kind);
            // an empty furnace is the best place for the raw meat we are carrying, and we are standing at it
            if (cookHere && CookGate.reusable(ctx.facts(), ctx.cfg().overworld, ctx.cfg().end.beds, "smoker".equals(visited.kind))) {
                cook = new CookRawFoodTask("smoker".equals(visited.kind));
                cookStart = ctx.facts().gameTime();
                Debug.logInternal(visited.kind + " is empty and we hold " + CookGate.raw(ctx.facts()) + " raw meat, cooking it before taking the "
                        + visited.kind + " back");
                return finishing(mod, ctx);
            }
            startPickup(ctx);
            Debug.logInternal(pickupKind + " is empty, taking it back at " + pickupAt.toShortString());
            return finishing(mod, ctx);
        }
        return null;
    }

    // pickupAt/Kind/Item are set, the block comes down now
    private void startPickup(GamerContext ctx) {
        pickupStart = ctx.facts().gameTime();
        pickupBroken = false;
        pickup = new DestroyBlockTask(pickupAt);
    }

    // a new trip to the job that is ready first, null when there is no job here. NORMAL takes what is done, WAIT_ALL stays
    // until everything is out, TAKE_ALL is for leaving. why = what started the trip, it goes in the log
    public Task collect(AltoClef mod, GamerContext ctx, Mode mode, String why) {
        Task running = active(mod, ctx);
        if (running != null) {
            return running;
        }
        return collectJob(mod, ctx, FurnaceJobs.soonest(ctx.facts().furnaceJobs()), mode, why);
    }

    // the same trip to one chosen job (leaving a mine picks the deep one, not the soonest)
    public Task collectJob(AltoClef mod, GamerContext ctx, RunState.FurnaceJob job, Mode mode, String why) {
        return collectJob(mod, ctx, job, mode, why, Math.round(ctx.cfg().overworld.furnaceWaitSeconds * 20));
    }

    // `nearly` = how close to done still counts as worth waiting for
    public Task collectJob(AltoClef mod, GamerContext ctx, RunState.FurnaceJob job, Mode mode, String why, long nearly) {
        Task running = active(mod, ctx);
        if (running != null) {
            return running;
        }
        if (job == null) {
            return null;
        }
        long now = ctx.facts().gameTime();
        // two waits to the end of the estimate and nothing came out: it is not cooking (fuel that ran dry under a lit
        // reading, an input it will not smelt). take the input back out and let the planner redo it, and the station comes
        // down with it once it is empty. an unlit furnace with no fuel is already handled inside the trip, this is the rest
        if (FurnaceJobs.stuck(job) && mode != Mode.TAKE_ALL) {
            Debug.logInternal(job.kind + " at " + job.pos + " did not cook anything in " + job.stalls + " waits, taking the "
                    + job.input + " back out");
            mode = Mode.TAKE_ALL;
        }
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
