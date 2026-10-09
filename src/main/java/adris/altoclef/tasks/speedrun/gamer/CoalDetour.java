package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.gamer.CoalRules.Offset;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

// the world half of CoalRules: finds coal ore near the player, runs a MineAndCollectTask of its own at it, and hands the task
// back to PrepSupport until the rules say stop. it is the last side job there is, everything ranked above it wins the tick.
// the mining task asks the tracker for the nearest coal wherever it is, so the rules end the job when the ore near the spot it
// began at is gone (a count target would send it off to a vein 80 blocks away, and "hold N" is a bad question anyway: the
// cluster grows as we dig into it). when the task gives up on a block it sets off for a far vein, and the leash from that spot
// cuts the walk short and the ban keeps us off that cluster. the bans go in the shared book (Bans) scoped to the detour: our
// mining task skips them through onlyWhere, and the fuel task that really needs coal still gets offered them. the tracker is
// the mining task's own business, it tracks on start and lets go on stop, so nothing here needs releasing when the phase
// leaves, only forgetting
public final class CoalDetour {
    // the same two blocks TaskCatalogue mines for "coal". an array of our own: the iron task's list must not learn about coal,
    // two mine tasks with the same blocks are the same task to the task system and coal would count towards the iron
    private static final Block[] ORES = {Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE};
    // a few thousand block reads per look, twice a second is a lot of watching already
    private static final int LOOK_EVERY_TICKS = 10;

    private final CoalRules rules = new CoalRules();
    private Task mine;
    // where the detour began. the keep reach, the leash and the ban are all measured from here and not from the player: the player
    // is the thing that wanders off when the mining task gives up on a block
    private BlockPos anchor;
    private int ticks;
    private int coalAtStart;
    private int coalLast;
    private String hud;
    // the ore the rules last looked at, for the card's "N blocks". the look happens anyway, this just keeps the answer
    private BlockPos ore;
    // the "we hold enough" line went out and is still true
    private boolean enoughSaid;

    String hud() {
        return hud;
    }

    // ---- for the card (GamerHud), plain reads

    public boolean active() {
        return rules.running() && mine != null;
    }

    public long startTick() {
        return rules.startTick();
    }

    // the coal ore we are on our way to, null when the last look found none
    public BlockPos ore() {
        return ore;
    }

    // a detour that is going, over (see CoalRules.preempted)
    void preempted(long now) {
        if (rules.running()) {
            Debug.logInternal("coal side job: something else took over, ending the detour");
        }
        rules.preempted(now);
        mine = null;
        anchor = null;
    }

    // the phase is entering or leaving. the bans stay, they are about the world and not the phase (and live in Bans anyway)
    void reset() {
        rules.reset();
        mine = null;
        anchor = null;
        hud = null;
        ticks = 0;
    }

    // the mining task while a detour is on, null when there is none
    Task tick(AltoClef mod, GamerContext ctx, KitNeed head) {
        // looking for a vein is the part that costs, a detour that is going is asked about every tick
        if (!rules.running() && ++ticks % LOOK_EVERY_TICKS != 0) {
            return null;
        }
        GamerFacts f = ctx.facts();
        OverworldConfig cfg = ctx.cfg().overworld;
        long now = f.gameTime();
        int coal = f.count(Items.COAL);
        BlockPos me = mod.getPlayer().blockPosition();
        double keep = CoalRules.keepBudget(cfg.coalSideBudget);
        BlockPos from = anchor == null ? me : anchor;
        CoalRules.Ore ore = new CoalRules.Ore(() -> (this.ore = nearest(mod, me, cfg.coalSideBudget)) != null,
                () -> (this.ore = nearest(mod, from, keep)) != null, () -> dropNear(mod), () -> strayed(me, from, cfg));
        CoalRules.Inputs in = inputs(mod, ctx, head, coal);
        noteEnough(in, cfg);
        CoalRules.Step step = rules.tick(now, in, cfg, ore);
        switch (step) {
            case START:
                start(mod, ctx, cfg, me, coal);
                return mine;
            case KEEP:
                if (coal > coalLast) {
                    // a piece in the bag is the stall timer's kind of progress, a long detour is not
                    ctx.progress("mining coal");
                }
                coalLast = coal;
                return mine;
            case SETTLE:
                // the mining task has its own wait for the drop of a block it just broke, we only keep it alive through it
                return mine;
            case TIMEOUT:
            case STRAYED:
                Debug.logInternal("coal side job: " + step + " with " + coal + " coal held, banning what is left around " + from.toShortString());
                ban(mod, from, keep);
                finish(coal, step);
                return null;
            case DONE:
            case BLOCKED:
                finish(coal, step);
                return null;
            default:
                return null;
        }
    }

    private void start(AltoClef mod, GamerContext ctx, OverworldConfig cfg, BlockPos me, int coal) {
        // the target is the cap in total, so the task never calls itself finished before the rules do
        // and it only ever picks coal we can see from where we stand, so a vein behind the wall is not a tunnel (CoalSight).
        // our own give-ups too: they are scoped to the detour, the tracker still offers them to everybody else
        mine = new MineAndCollectTask(new ItemTarget(Items.COAL, cfg.coalSideCap), ORES, MiningRequirement.WOOD)
                .onlyWhere(pos -> !BanPolicy.coalBanned(mod.getBans(), pos.getX(), pos.getY(), pos.getZ()) && visible(mod, pos));
        anchor = me;
        coalAtStart = coal;
        coalLast = coal;
        hud = "Mining coal while it is close";
        // a restart on the cluster a bed or a chest just interrupted is the same trip as far as chat goes (CoalRules.announce)
        boolean said = rules.announce(ctx.facts().gameTime(), me.getX(), me.getY(), me.getZ());
        if (said) {
            ctx.log("Mining coal since we found it here");
        }
        BlockPos first = nearest(mod, me, cfg.coalSideBudget);
        Debug.logInternal("coal side job: started with " + coal + " coal held" + (first == null ? "" : ", nearest ore " + first.toShortString())
                + (said ? "" : ", chat line skipped, near the last one"));
    }

    private void finish(int coal, CoalRules.Step why) {
        Debug.logInternal("coal side job: " + why + ", " + coalAtStart + " -> " + coal + " coal held");
        mine = null;
        anchor = null;
        hud = null;
        ore = null;
    }

    // one line when the plan's coal is covered, not one per look. said again only after it stopped being true
    private void noteEnough(CoalRules.Inputs in, OverworldConfig cfg) {
        boolean enough = CoalRules.enough(in, cfg);
        if (enough && !enoughSaid && !rules.running()) {
            Debug.logInternal("coal side job: skipping, we hold " + in.coal() + " coal and the plan needs " + Math.min(in.need(), cfg.coalSideCap));
        }
        enoughSaid = enough;
    }

    // CoalRules.coalNeed over the facts. raw iron in the bag is still owed (ingotsNeeded counts the missing items, not the ore)
    static int coalNeed(GamerContext ctx) {
        GamerFacts f = ctx.facts();
        OverworldConfig cfg = ctx.cfg().overworld;
        return CoalRules.coalNeed(KitPlanner.ingotsNeeded(f, cfg), f.count(Items.IRON_INGOT), f.pendingOutput(Items.IRON_INGOT),
                CookGate.raw(f), CookGate.woodSmelts(f, cfg, ctx.cfg().end.beds));
    }

    private static CoalRules.Inputs inputs(AltoClef mod, GamerContext ctx, KitNeed head, int coal) {
        GamerFacts f = ctx.facts();
        long now = f.gameTime();
        boolean screen = mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu;
        // the same "due" the furnace plan uses, so a detour never sits on a collect trip it is about to hold up
        boolean due = FurnacePlan.anyDue(f.furnaceJobs(), now);
        String need = head == null ? null : head.catalogueName();
        // the food need and the cook both have latches that count the time they lead for, a detour in the middle of one eats it
        boolean food = KitNeed.FOOD.equals(need) || KitNeed.isCookName(need);
        return new CoalRules.Inputs(f.dimension() == Dimension.OVERWORLD, hasPickaxe(f), coal, coalNeed(ctx), f.cookStation() != null, due,
                WorkbenchRules.loadInFlight(screen, AsyncSmelting.lastWork(), now), food, "coal".equals(need));
    }

    // stone or better. a wooden pick could mine coal, but the wooden pick days are for getting cobble and leaving, and its 59
    // uses are spoken for. gold is wood tier with worse durability, so it doesn't count either
    private static boolean hasPickaxe(GamerFacts f) {
        return f.has(Items.STONE_PICKAXE) || f.has(Items.IRON_PICKAXE) || f.has(Items.DIAMOND_PICKAXE) || f.has(Items.NETHERITE_PICKAXE);
    }

    private static boolean isCoalOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
    }

    // the nearest coal ore within the budget of `center` that we could mine, have not given up on, and can see right now (CoalSight:
    // an air face and a clear line from the eyes, within 8). start and keep both ask it, so the detour ends when the only coal left
    // in the cluster is behind stone. "seen once" (SeenFilter) was not enough: a glimpse through a crack 30 blocks off counted.
    // a banned ore (ours, scoped to the detour, or anybody's) is skipped here and by the detour's mining task alike (onlyWhere)
    private BlockPos nearest(AltoClef mod, BlockPos center, double budget) {
        ClientLevel level = mod.getWorld();
        Bans bans = mod.getBans();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Offset o : CoalRules.offsets(budget)) {
            at.set(center.getX() + o.dx(), center.getY() + o.dy(), center.getZ() + o.dz());
            if (!isCoalOre(level.getBlockState(at)) || BanPolicy.coalBanned(bans, at.getX(), at.getY(), at.getZ())) {
                continue;
            }
            BlockPos found = at.immutable();
            if (WorldHelper.canBreak(mod, found) && visible(mod, found)) {
                return found;
            }
        }
        return null;
    }

    // the mining task asks this for every candidate, twice a pick, every tick. same block same tick is the same answer
    private final Map<Long, Boolean> sight = new HashMap<>();
    private long sightTick = Long.MIN_VALUE;

    private boolean visible(AltoClef mod, BlockPos pos) {
        // the gui clock and not game time, which stands still under /tick freeze while we keep walking
        long now = Minecraft.getInstance().gui.getGuiTicks();
        if (now != sightTick) {
            sight.clear();
            sightTick = now;
        }
        return sight.computeIfAbsent(pos.asLong(), k -> look(mod, pos));
    }

    private static boolean look(AltoClef mod, BlockPos pos) {
        ClientLevel level = mod.getWorld();
        Vec3 eye = mod.getPlayer().getEyePosition();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        CoalSight.Cells cells = new CoalSight.Cells() {
            @Override
            public boolean air(int x, int y, int z) {
                return level.getBlockState(at.set(x, y, z)).isAir();
            }

            @Override
            public boolean blocksView(int x, int y, int z) {
                return level.getBlockState(at.set(x, y, z)).canOcclude();
            }
        };
        return CoalSight.visible(cells, eye.x, eye.y, eye.z, pos.getX(), pos.getY(), pos.getZ());
    }

    // further from where the detour began than the leash, as the crow flies
    private static boolean strayed(BlockPos me, BlockPos from, OverworldConfig cfg) {
        return CoalRules.strayed(me.getX() - from.getX(), me.getY() - from.getY(), me.getZ() - from.getZ(), cfg.coalSideBudget);
    }

    // everything coal around `center` goes on the ban list, not just the block we were stuck on: which one that was is the mining
    // task's secret, and its neighbours are stuck in the same way more often than not
    private void ban(AltoClef mod, BlockPos center, double budget) {
        ClientLevel level = mod.getWorld();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int added = 0;
        for (Offset o : CoalRules.offsets(budget)) {
            at.set(center.getX() + o.dx(), center.getY() + o.dy(), center.getZ() + o.dz());
            if (isCoalOre(level.getBlockState(at)) && BanPolicy.coal(mod.getBans(), at.getX(), at.getY(), at.getZ())) {
                added++;
            }
        }
        Debug.logInternal("coal side job: banned " + added + " ore blocks");
    }

    // a coal item on the floor close by that the bag has room for. one it has no room for would hold the detour until its clock
    // ran out, same test the mining task uses to leave a drop alone
    private static boolean dropNear(AltoClef mod) {
        Vec3 me = mod.getPlayer().position();
        return mod.getEntityTracker().getClosestItemDrop(me, d -> d.isAlive() && d.position().distanceTo(me) <= CoalRules.DROP_RADIUS
                && !mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(d.getItem(), false).isEmpty(), Items.COAL).isPresent();
    }
}
