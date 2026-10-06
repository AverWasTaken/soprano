package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.CollectFromFurnaceTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

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

    public void reset() {
        task = null;
        target = null;
        walkBack = null;
        pulling = false;
        pullbacks = 0;
        hud = null;
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
        FurnaceJobs.afterVisit(ctx.state().furnaceJobs, target, task.inputLeft(), ctx.facts().gameTime());
        ctx.progress("collected from the furnace");
        ctx.save();
        task = null;
        target = null;
        hud = null;
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
