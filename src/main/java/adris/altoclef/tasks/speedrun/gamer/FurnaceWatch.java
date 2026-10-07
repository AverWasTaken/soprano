package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// the game side of smelting in the background: keeps the job list honest (stale ones, furnaces that are gone), holds the
// leash, and hands out the task that goes back for the iron. one per phase that can have jobs (IRON, and PORTAL for the
// last look before we leave). the rules it follows are in FurnaceJobs and SmeltFiller, this is only the part that needs a world
public final class FurnaceWatch {
    private CollectFromFurnaceTask task;
    private RunState.FurnaceJob target;
    private Task walkBack;
    private boolean pulling;
    private int pullbacks;
    private String hud;
    // the IRON phase takes its furnace back once the last of the iron is out, so the next smelt can go down at the next work
    // site. PORTAL leaves it alone, it is on its way out
    private boolean pickUpWhenEmpty;
    private Task pickup;
    private BlockPos pickupAt;
    private boolean pickupBroken;
    private long pickupStart;
    private SmeltFiller.Nearby nearby = SmeltFiller.Nearby.ANYWHERE;
    private long nearbyAt = Long.MIN_VALUE;

    // jobs come and go (every batch empties the list) but the leash story is the whole phase's: a bot that got dragged back
    // three times on batch one is not going to behave on batch two, so this keeps the count
    public void reset() {
        task = null;
        target = null;
        walkBack = null;
        pulling = false;
        hud = null;
        pickup = null;
        pickupAt = null;
        pickupBroken = false;
        nearbyAt = Long.MIN_VALUE;
    }

    // a fresh phase: everything, the leash count included
    public void newPhase(boolean pickUpWhenEmpty) {
        reset();
        pullbacks = 0;
        this.pickUpWhenEmpty = pickUpWhenEmpty;
    }

    // the furnace is coming down right now (the phase must not end under it)
    public boolean pickingUp() {
        return pickup != null;
    }

    // what is worth walking to inside the leash, asked a couple of times a second at most. EntityTracker only knows what is
    // loaded around us, which is the honest answer: a sheep we cannot see is not one we can plan on
    public SmeltFiller.Nearby nearby(AltoClef mod, GamerContext ctx) {
        List<RunState.FurnaceJob> jobs = ctx.facts().furnaceJobs();
        if (jobs.isEmpty()) {
            return SmeltFiller.Nearby.ANYWHERE;
        }
        long now = ctx.facts().gameTime();
        if (now - nearbyAt < 10 && nearbyAt != Long.MIN_VALUE) {
            return nearby;
        }
        nearbyAt = now;
        RunState.FurnaceJob job = jobs.get(0);
        double best = Double.MAX_VALUE;
        for (RunState.FurnaceJob j : jobs) {
            double d = SmeltFiller.horizontal(mod.getPlayer().getX(), mod.getPlayer().getZ(), j.pos);
            if (d < best) {
                best = d;
                job = j;
            }
        }
        double radius = SmeltFiller.fillerRadius(SmeltFiller.leashBlocks(simulationChunks(), ctx.cfg().overworld.furnaceLeashBlocks));
        Vec3 at = new Vec3(job.pos.x + 0.5, job.pos.y, job.pos.z + 0.5);
        Predicate<Entity> inside = e -> Math.hypot(e.getX() - at.x, e.getZ() - at.z) <= radius;
        boolean sheep = mod.getEntityTracker().getClosestEntity(at, e -> inside.test(e) && e instanceof Sheep s && !s.isSheared() && !s.isBaby(),
                Sheep.class).isPresent();
        boolean food = sheep || mod.getEntityTracker().getClosestEntity(at, e -> inside.test(e) && !((Animal) e).isBaby(),
                Pig.class, Cow.class, Chicken.class, Sheep.class).isPresent();
        nearby = new SmeltFiller.Nearby(sheep, food);
        return nearby;
    }

    // the furnace comes down after the last collect, run until the block is broken and the item is back in the bag
    public Task finishing(AltoClef mod, GamerContext ctx) {
        if (pickup == null) {
            return null;
        }
        if (!pickupBroken && pickup.isFinished(mod)) {
            pickupBroken = true;
            ctx.state().placedFurnaces.remove(new RunState.Pos(pickupAt.getX(), pickupAt.getY(), pickupAt.getZ()));
            pickup = new PickupDroppedItemTask(Items.FURNACE, 1);
        }
        double elapsed = (ctx.facts().gameTime() - pickupStart) / 20.0;
        if ((pickupBroken && ctx.facts().has(Items.FURNACE)) || elapsed > ctx.cfg().overworld.tablePickupSeconds) {
            Debug.logInternal("furnace pickup after the last collect: " + (pickupBroken ? "done" : "timed out") + " at " + pickupAt.toShortString());
            pickup = null;
            pickupAt = null;
            pickupBroken = false;
            hud = null;
            return null;
        }
        hud = "Picking up the furnace";
        return pickup;
    }

    // ours, a plain furnace (never a village's blast furnace), nothing in it, and we hold no spare. then it comes with us
    private boolean takeBack(AltoClef mod, GamerContext ctx, RunState.FurnaceJob job) {
        if (!pickUpWhenEmpty || !"furnace".equals(job.kind) || ctx.facts().has(Items.FURNACE)
                || !ctx.state().placedFurnaces.contains(job.pos)) {
            return false;
        }
        return WorldHelper.canBreak(mod, at(job));
    }

    // plain words for what we are doing about the furnace, null when nothing
    public String hud() {
        return hud;
    }

    // times the leash dragged us back since the last reset, SmeltFiller.trip gives up wandering after a few
    public int pullbacks() {
        return pullbacks;
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
        int left = task.inputLeft();
        FurnaceJobs.afterVisit(ctx.state().furnaceJobs, target, left, ctx.facts().gameTime());
        ctx.progress("collected from the furnace");
        ctx.save();
        task = null;
        target = null;
        hud = null;
        if (left <= 0 && takeBack(mod, ctx, visited)) {
            pickupAt = at(visited);
            pickupStart = ctx.facts().gameTime();
            pickupBroken = false;
            pickup = new DestroyBlockTask(pickupAt);
            Debug.logInternal("furnace is empty, taking it back at " + pickupAt.toShortString());
            return finishing(mod, ctx);
        }
        return null;
    }

    // a new trip to the job that is ready first, null when there is no job here. NORMAL takes what is done, WAIT_ALL stays
    // until everything is out, TAKE_ALL is for leaving
    public Task collect(AltoClef mod, GamerContext ctx, Mode mode) {
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
        task = new CollectFromFurnaceTask(at(job), block, job.kind, mode, nearly, cap);
        hud = mode == Mode.WAIT_ALL ? "Waiting for the furnace" : "Collecting from the furnace";
        return task;
    }

    // the leash: too far from the nearest job and we walk back to half of it, null when we are fine
    public Task pullBack(AltoClef mod, GamerContext ctx) {
        List<RunState.FurnaceJob> jobs = ctx.facts().furnaceJobs();
        if (jobs.isEmpty()) {
            pulling = false;
            return null;
        }
        double px = mod.getPlayer().getX();
        double pz = mod.getPlayer().getZ();
        RunState.FurnaceJob nearest = null;
        double best = Double.MAX_VALUE;
        for (RunState.FurnaceJob job : jobs) {
            double d = SmeltFiller.horizontal(px, pz, job.pos);
            if (d < best) {
                best = d;
                nearest = job;
            }
        }
        double leash = SmeltFiller.leashBlocks(simulationChunks(), ctx.cfg().overworld.furnaceLeashBlocks);
        if (!SmeltFiller.pullBack(pulling, best, leash)) {
            pulling = false;
            walkBack = null;
            return null;
        }
        if (!pulling) {
            pulling = true;
            pullbacks++;
            // the loop this count exists to break: out to a sheep, dragged back, out to the sheep. now it is in the log
            ctx.log("leash: " + Math.round(best) + " blocks from the furnace (leash " + leash + "), walking back, pullback "
                    + pullbacks + " of " + ctx.cfg().overworld.furnaceMaxPullbacks);
        }
        if (walkBack == null) {
            walkBack = new GetWithinRangeOfBlockTask(at(nearest), Math.max(8, (int) (leash * SmeltFiller.PULL_BACK_FRACTION) - 4));
        }
        hud = "Heading back to the furnace";
        return walkBack;
    }

    // chunks around us that tick, the integrated server knows. a real server we cannot ask from here (0 = use the config)
    static int simulationChunks() {
        try {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            return server == null ? 0 : server.getPlayerList().getSimulationDistance();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static BlockPos at(RunState.FurnaceJob job) {
        return new BlockPos(job.pos.x, job.pos.y, job.pos.z);
    }
}
