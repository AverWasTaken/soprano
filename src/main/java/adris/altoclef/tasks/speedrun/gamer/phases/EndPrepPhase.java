package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.EndConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.tasks.speedrun.gamer.end.EndRules;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// overworld, portal open. beds from the wool, a spawn bed by the portal, the gear gate (EndGear says what is missing,
// counting what we left in the End), THEN walk into the portal. done = we are in the End: the gear being fine is not
// done, walking in is the last step of this phase
public class EndPrepPhase implements PhaseHandler {
    private final Map<String, Task> _catalogue = new HashMap<>();
    private final Map<Item, Task> _equip = new HashMap<>();
    // built on first use, not here: the pure parts (isDone) get tested with no game and no task machinery
    private PlaceBedAndSetSpawnTask _setSpawn;
    private Task _enterPortal;
    private Task _buildBlocks;
    private Task _food;
    private int _foodTarget;
    private Task _toPortal;
    private BlockPos _toPortalAt;
    private String _hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.END_PREP;
    }

    @Override
    public String hud() {
        return GamerPhase.END_PREP.hud();
    }

    @Override
    public String hudState() {
        return _hudState;
    }

    // the engine only advances on the dimension: the walk into the portal is a step of this phase, so gear alone is not "done"
    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return facts.dimension() == Dimension.END || state.dragonDead || facts.creditsShown();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        ensureTasks();
        // the task keeps its "spawn is set" flag from the last visit, a death in the End means it may be gone
        _setSpawn.resetSleep();
        _hudState = null;
    }

    // END_PORTAL is in the engine's tracked set (GamerTask.TRACKED), a handler level trackBlock leaked its ref count when
    // the task was stopped while interrupted (onExit never ran)
    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        ctx.walkOnEndPortal(false);
    }

    private void ensureTasks() {
        if (_setSpawn == null) {
            _setSpawn = new PlaceBedAndSetSpawnTask();
            _enterPortal = new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos.above()), Blocks.END_PORTAL);
        }
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        ensureTasks();
        if (ctx.facts().dimension() != Dimension.OVERWORLD) {
            _hudState = null;
            ctx.walkOnEndPortal(false);
            return null;
        }
        EndConfig cfg = ctx.cfg().end;
        RunState state = ctx.state();
        noteSpawnSet(mod, ctx);
        giveUpSpawnWhenSlow(ctx, cfg);

        // a second try goes with what it has, the sword strat needs no wool and the budget is not endless
        int required = relaxed(ctx) ? 0 : cfg.beds;
        EndGear.Gap gap = EndGear.missing(ctx.facts(), state, cfg, required, ctx.food());
        Task step = bedStep(mod, ctx, cfg, gap);
        if (step == null) {
            step = gearStep(mod, ctx, cfg, gap);
        }
        // one write per tick (the path thread reads this flag): true only when everything else is done and we walk in
        ctx.walkOnEndPortal(step == null);
        return step != null ? step : walkIn();
    }

    private static boolean relaxed(GamerContext ctx) {
        return EndGear.relaxed(ctx.attempt(), EndRules.endDeaths(ctx.state()));
    }

    // beds, then the spawn bed. returns null when both are done (or given up on)
    private Task bedStep(AltoClef mod, GamerContext ctx, EndConfig cfg, EndGear.Gap gap) {
        RunState state = ctx.state();
        boolean wantSpawn = EndGear.wantSpawnBed(state, cfg) && !relaxed(ctx);
        int required = relaxed(ctx) ? 0 : cfg.beds;
        int lacking = EndGear.bedShortfall(ctx.facts(), state, cfg, required, wantSpawn);
        if (lacking > 0) {
            // the catalogue counts what we hold, so ask for the total
            int want = ctx.facts().count(ItemHelper.BED) + lacking;
            _hudState = "Crafting beds";
            return catalogue("bed", want, () -> TaskCatalogue.getItemTask("bed", want));
        }
        return wantSpawn ? spawnStep(mod, ctx, cfg) : null;
    }

    private Task spawnStep(AltoClef mod, GamerContext ctx, EndConfig cfg) {
        BlockPos center = portalCenter(mod, ctx.state());
        if (center == null) {
            giveUpSpawn(ctx, "no portal to set the spawn near");
            return null;
        }
        if (_setSpawn.isActive() && !_setSpawn.isFinished(mod)) {
            _hudState = "Setting spawn by the portal";
            return _setSpawn;
        }
        if (!WorldHelper.inRangeXZ(mod.getPlayer(), center, cfg.spawnBedRange)) {
            _hudState = "Heading to the portal to set spawn";
            if (_toPortal == null || !center.equals(_toPortalAt)) {
                _toPortalAt = center;
                _toPortal = new GetToXZTask(center.getX(), center.getZ());
            }
            return _toPortal;
        }
        _hudState = "Setting spawn by the portal";
        return _setSpawn;
    }

    // the place task finished = the bed took our spawn. getBedSleptPos throws when it never saw a bed, so ask nicely
    private void noteSpawnSet(AltoClef mod, GamerContext ctx) {
        RunState state = ctx.state();
        if (state.spawnBedSet || !_setSpawn.isFinished(mod)) {
            return;
        }
        try {
            BlockPos at = _setSpawn.getBedSleptPos();
            state.spawnBed = new RunState.Pos(at.getX(), at.getY(), at.getZ());
        } catch (IllegalStateException e) {
            // spawn set by a bed we never walked to, there is no position to remember
        }
        state.spawnBedSet = true;
        ctx.progress("spawn bed set");
        ctx.save();
    }

    private void giveUpSpawnWhenSlow(GamerContext ctx, EndConfig cfg) {
        if (EndGear.wantSpawnBed(ctx.state(), cfg) && ctx.secondsInPhase() > cfg.spawnBedBudgetSeconds) {
            giveUpSpawn(ctx, "the spawn bed is taking too long");
        }
    }

    private void giveUpSpawn(GamerContext ctx, String why) {
        ctx.state().spawnBedGaveUp = true;
        ctx.log("skipping the spawn bed: " + why);
        ctx.save();
    }

    private BlockPos portalCenter(AltoClef mod, RunState state) {
        if (state.endPortalCenter != null) {
            RunState.Pos c = state.endPortalCenter;
            return new BlockPos(c.x, c.y, c.z);
        }
        return mod.getBlockTracker().getNearestTracking(Blocks.END_PORTAL).orElse(null);
    }

    // weapon, bucket, pickaxe, blocks, armor, food. null = all there
    private Task gearStep(AltoClef mod, GamerContext ctx, EndConfig cfg, EndGear.Gap gap) {
        if (gap.weapon()) {
            _hudState = "Getting an axe";
            return catalogueItem(Items.IRON_AXE);
        }
        if (gap.waterBucket()) {
            _hudState = "Getting a water bucket";
            return catalogueItem(Items.WATER_BUCKET);
        }
        if (gap.pickaxe()) {
            _hudState = "Getting a pickaxe";
            return catalogueItem(Items.IRON_PICKAXE);
        }
        Task blocks = blocksStep(mod, cfg, gap);
        if (blocks != null) {
            return blocks;
        }
        List<Item> wear = gap.armorToWear();
        if (!wear.isEmpty()) {
            _hudState = "Putting on armor";
            return _equip.computeIfAbsent(wear.get(0), piece -> new EquipArmorTask(piece));
        }
        if (gap.food()) {
            _hudState = "Getting food";
            // the gate counts food we would eat, CollectFoodTask counts rotten flesh too, so a bag of junk makes it finish
            // instantly while the gate stays short and we idle. FoodPlan adds the junk (the same call KitRunner.foodTarget makes),
            // rebuilt only when the number moves
            int target = ctx.food().endCollect();
            if (_food == null || target != _foodTarget) {
                _foodTarget = target;
                _food = new CollectFoodTask(target);
            }
            return _food;
        }
        return null;
    }

    // collects up to cfg.buildBlocks once it starts, not just up to the gate (the bridge off the arrival platform eats ~30)
    private Task blocksStep(AltoClef mod, EndConfig cfg, EndGear.Gap gap) {
        if (_buildBlocks == null) {
            _buildBlocks = new GetBuildingMaterialsTask(cfg.buildBlocks);
        }
        boolean running = _buildBlocks.isActive() && !_buildBlocks.isFinished(mod);
        if (gap.buildBlocks() == 0 && !running) {
            return null;
        }
        _hudState = "Collecting building blocks";
        return _buildBlocks;
    }

    private Task walkIn() {
        _hudState = "Walking into the End portal";
        return _enterPortal;
    }

    private Task catalogueItem(Item item) {
        return catalogue(item.toString(), 1, () -> TaskCatalogue.getItemTask(item, 1));
    }

    // TaskCatalogue.getItemTask builds a new task every call, and a new one per tick restarts its state
    private Task catalogue(String name, int count, java.util.function.Supplier<Task> make) {
        return _catalogue.computeIfAbsent(name + ":" + count, k -> make.get());
    }
}
