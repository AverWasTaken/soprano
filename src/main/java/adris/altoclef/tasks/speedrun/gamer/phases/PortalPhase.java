package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.container.CollectFromFurnaceTask.Mode;
import adris.altoclef.tasks.speedrun.gamer.FurnaceWatch;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PiglinGold;
import adris.altoclef.tasks.speedrun.gamer.PortalPlanner;
import adris.altoclef.tasks.speedrun.gamer.PortalPlanner.Method;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.portal.LavaPoolPortalTask;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;

// gets us into the nether. first everything we want to hold when we leave (flint and steel, the buckets, water, armor
// on, blocks, food), then the portal: the lava pool mold first (altoLavaPoolPortal), and when that gives up the old
// CAST (lava lake, no diamonds), and after that OBSIDIAN. the cast never finishes on its own when there is no lake, it
// wanders, so a clock decides when to give up on it
public class PortalPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    private final FurnaceWatch furnaces = new FurnaceWatch();
    private boolean gateDone;
    private boolean noGoldSaid;
    private boolean tracking;
    private long castStartTick = -1;
    private DefaultGoToDimensionTask cast;
    private LavaPoolPortalTask pool;
    private boolean poolFailed;
    private ConstructNetherPortalObsidianTask obsidian;
    private EnterNetherPortalTask enter;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.PORTAL;
    }

    @Override
    public String hud() {
        return GamerPhase.PORTAL.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return facts.dimension() == Dimension.NETHER;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        // the furnace we empty on the way out comes down with us, but never gets a batch of meat on the way
        furnaces.newExitPhase();
        gateDone = false;
        castStartTick = -1;
        cast = new DefaultGoToDimensionTask(Dimension.NETHER);
        pool = new LavaPoolPortalTask(ctx::log);
        poolFailed = false;
        obsidian = new ConstructNetherPortalObsidianTask();
        enter = new EnterNetherPortalTask(Dimension.NETHER);
        hudState = null;
        if (!tracking) {
            // portals so we can tell when ours exists, lava so "no lava seen" means something during the cast
            mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL, Blocks.LAVA);
            tracking = true;
        }
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL, Blocks.LAVA);
            tracking = false;
        }
        // we get here standing in the nether side of the pair
        PortalPlanner.recordArrival(ctx.state(), ctx.facts());
        if (ctx.state().overworldPortal == null) {
            Optional<BlockPos> used = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD);
            used.ifPresent(p -> ctx.state().overworldPortal = new RunState.Pos(p.getX(), p.getY(), p.getZ()));
        }
        ctx.save();
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        noteOverworldPortal(mod, ctx);
        GamerFacts f = ctx.facts();
        // iron still cooking in the overworld is iron we are about to leave behind (an IRON phase that skipped itself on a
        // timeout can get us here). one last visit: take what is done, wait if it is nearly, pull the rest back out
        if (f.dimension() == Dimension.OVERWORLD) {
            Task takingBack = furnaces.finishing(mod, ctx);
            if (takingBack != null) {
                hudState = furnaces.hud();
                return takingBack;
            }
        }
        if (f.dimension() == Dimension.OVERWORLD && !f.furnaceJobs().isEmpty()) {
            furnaces.housekeeping(mod, ctx);
            Task leaving = furnaces.collect(mod, ctx, Mode.TAKE_ALL, "leaving the overworld with iron still cooking");
            if (leaving != null) {
                hudState = furnaces.hud();
                return leaving;
            }
        }
        if (!gateDone) {
            List<KitNeed> gate = PortalPlanner.gate(f, ctx.cfg().overworld);
            if (!gate.isEmpty()) {
                Task prep = runner.run(ctx, gate);
                hudState = runner.hud();
                return prep;
            }
            // from here the cast juggles its own buckets, checking the gate again would fight it
            gateDone = true;
            // the gate already tried to make or wear gold, so nothing on us now means nothing to make it from. no mining detour
            if (!PiglinGold.worn(f) && !noGoldSaid) {
                noGoldSaid = true;
                ctx.log("no gold for piglins, good luck");
            }
            ctx.progress("ready for the portal");
        }
        // the mold goes first. when it gives up the cast clock starts from now, not from when the phase did
        if (f.dimension() == Dimension.OVERWORLD
                && PortalPlanner.usePool(Baritone.settings().altoLavaPoolPortal.value, poolFailed, PortalPlanner.parse(ctx.state().portalMethod))) {
            if (portalExists(mod)) {
                hudState = "Going through the portal";
                return enter;
            }
            if (!pool.failed()) {
                hudState = "Building the portal on a lava pool";
                return pool;
            }
            poolFailed = true;
            castStartTick = -1;
            ctx.progress("lava pool gave up: " + pool.failReason());
        }
        if (castStartTick < 0) {
            castStartTick = f.gameTime();
        }
        Method method = chooseMethod(mod, ctx);
        if (method == Method.CAST) {
            hudState = portalExists(mod) ? "Going through the portal" : "Casting obsidian with lava";
            return cast;
        }
        if (portalExists(mod)) {
            hudState = "Going through the portal";
            return enter;
        }
        hudState = "Getting obsidian for the portal";
        return obsidian;
    }

    private Method chooseMethod(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        Method before = PortalPlanner.parse(ctx.state().portalMethod);
        double castSeconds = (f.gameTime() - castStartTick) / 20.0;
        boolean sawLava = mod.getBlockTracker().isTracking(Blocks.LAVA) && mod.getBlockTracker().anyFound(Blocks.LAVA);
        boolean diamondPick = f.count(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE) > 0;
        Method now = PortalPlanner.decide(before, castSeconds, ctx.cfg().overworld, sawLava, diamondPick);
        if (now != before) {
            ctx.state().portalMethod = now.name();
            ctx.log("giving up on casting, going the obsidian way");
            ctx.save();
        }
        return now;
    }

    private boolean portalExists(AltoClef mod) {
        return mod.getBlockTracker().isTracking(Blocks.NETHER_PORTAL) && mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL);
    }

    // remember the overworld end once we are standing next to it, the return trip walks back to this one
    private void noteOverworldPortal(AltoClef mod, GamerContext ctx) {
        if (ctx.state().overworldPortal != null || ctx.facts().dimension() != Dimension.OVERWORLD || !portalExists(mod)) {
            return;
        }
        Optional<BlockPos> portal = mod.getBlockTracker().getNearestTracking(mod.getPlayer().position(), Blocks.NETHER_PORTAL);
        if (portal.isPresent() && portal.get().closerToCenterThan(mod.getPlayer().position(), 6)) {
            ctx.state().overworldPortal = new RunState.Pos(portal.get().getX(), portal.get().getY(), portal.get().getZ());
            ctx.save();
        }
    }

    // first timeout: the cast was the problem (or the whole prep was slow), so the retry goes the obsidian way.
    // the second one is the end of the line, default retry would loop the same two plans for ever
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        if (PortalPlanner.parse(ctx.state().portalMethod) == Method.CAST && attempt < ctx.cfg().maxAttempts) {
            ctx.state().portalMethod = Method.OBSIDIAN.name();
            ctx.log("portal timed out (" + reason + "), trying obsidian");
            ctx.save();
            return Timeout.RETRY;
        }
        return attempt < ctx.cfg().maxAttempts ? Timeout.RETRY : Timeout.STUCK;
    }
}
