package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.level.block.Blocks;

import java.util.List;


// the side jobs the overworld phases share: danger filtering, taking our crafting table and furnace back, ruined portal chests,
// village chests, the odd iron golem, and a bit of coal when it is right there.
// all of them return a task to run INSTEAD of the kit task this tick, or null
public final class PrepSupport {
    private final DangerFilter danger = new DangerFilter();
    private final RuinedPortalLoot loot;
    private final VillageLoot village;
    private final VillageBeds villageBeds = new VillageBeds();
    // our crafting table, furnace and smoker: kept, taken back or forgotten by the rules in WorkbenchRules (phases share one, see FurnaceWatch)
    private final Workbenches benches;
    private final GolemHunt golem;
    // last in line, see tick
    private final CoalDetour coal = new CoalDetour();
    private boolean tracking;
    private String hud;

    public PrepSupport(boolean lootRuinedPortals) {
        this(lootRuinedPortals, new Workbenches());
    }

    // the phase's FurnaceWatch gets the same Workbenches, or they would each drive their own task for one pickup
    public PrepSupport(boolean lootRuinedPortals, Workbenches benches) {
        this.benches = benches;
        loot = lootRuinedPortals ? new RuinedPortalLoot() : null;
        village = lootRuinedPortals ? new VillageLoot() : null;
        golem = lootRuinedPortals ? new GolemHunt() : null;
    }

    public String hud() {
        return hud;
    }

    // for the card: the coal side job, which is the only one with a clock worth a row
    public CoalDetour coal() {
        return coal;
    }

    public void onEnter(AltoClef mod) {
        if (!tracking) {
            mod.getBlockTracker().trackBlock(Blocks.CRAFTING_TABLE);
            tracking = true;
        }
        if (loot != null) {
            loot.onEnter(mod);
            village.onEnter(mod);
        }
        benches.reset();
        coal.reset();
        hud = null;
    }

    public void onExit(AltoClef mod) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.CRAFTING_TABLE);
            tracking = false;
        }
        if (loot != null) {
            loot.onExit(mod);
            village.onExit(mod);
            golem.onExit();
        }
        villageBeds.onExit(mod);
        benches.reset();
        coal.reset();
    }

    // the bot is standing by a smoker on purpose (FurnacePlan STAND_BY): a few seconds of waiting is the plan, so the side jobs that
    // walk off (village chests and beds, ruined portals, a fresh golem, a new station pickup) can all wait the 40 s. only a golem
    // fight already going outranks it, same as it outranks everything. a pickup that already started is not a side job, it runs
    // to the end (a half taken furnace is how stations got left behind)
    public Task tickStandBy(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        danger.tick(mod, ctx.state());
        hud = null;
        // standing by the smoker is the plan, a coal detour that was going is over
        coal.preempted(ctx.facts().gameTime());
        if (golem != null && golem.active()) {
            Task fight = golem.tick(mod, ctx, needs.isEmpty() ? null : needs.get(0));
            if (fight != null) {
                hud = golem.hud();
                return fight;
            }
        }
        Task pickup = benches.resume(mod, ctx);
        if (pickup != null) {
            hud = benches.hud();
            return pickup;
        }
        return null;
    }

    // `needs` is what the phase still has to do, in order (empty = nothing)
    public Task tick(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        KitNeed current = needs.isEmpty() ? null : needs.get(0);
        Task ranked = rankedJobs(mod, ctx, needs, current);
        if (ranked != null) {
            // anything above the coal wins the tick, and a detour that was going is over (not paused): when the job is done
            // and the ore is still there it is a fresh detour, after its cooldown
            coal.preempted(ctx.facts().gameTime());
            return ranked;
        }
        // coal is last on purpose: every job above is worth more per second (a golem is 3 to 5 iron, a chest or a bed is loot we
        // cannot just mine) and coal is the only one for something we might need rather than something we do. last also means
        // it can never hold one of them up, and one of them taking the tick is the clean signal that ends a detour
        Task ore = coal.tick(mod, ctx, current);
        if (ore != null) {
            hud = coal.hud();
        }
        return ore;
    }

    private Task rankedJobs(AltoClef mod, GamerContext ctx, List<KitNeed> needs, KitNeed current) {
        beginTick(mod, ctx);
        // a golem fight in progress outranks everything, a chest is not worth stepping off the pillar for
        Task fight = golemFight(mod, ctx, current);
        if (fight != null) {
            return fight;
        }
        Task station = station(mod, ctx, needs);
        if (station != null) {
            return station;
        }
        Task chest = ruinedPortal(mod, ctx);
        if (chest != null) {
            return chest;
        }
        Task blacksmith = villageChest(mod, ctx, current);
        if (blacksmith != null) {
            return blacksmith;
        }
        // not behind the lootRuinedPortals flag: GATHER is where we usually meet the village, and a bed is a bed
        // and before the golem, a bed is a few seconds of punching and a golem is a minute on a pillar
        Task bed = bed(mod, ctx);
        if (bed != null) {
            return bed;
        }
        return golemStart(mod, ctx, current);
    }

    // ---- one job at a time, for IronActivity's driver (IronPhase). the old tick above runs them in its fixed order, the
    // arbiter asks the one it picked. each sets the hud when it hands back a task

    // once per tick, before any job is asked
    public void beginTick(AltoClef mod, GamerContext ctx) {
        danger.tick(mod, ctx.state());
        hud = null;
    }

    // whether the side jobs that loot (ruined portals, village chests, golems) are on in this phase
    public boolean loots() {
        return loot != null;
    }

    public boolean golemFighting() {
        return golem != null && golem.active();
    }

    // the fight that is going, null when there is none (never starts one)
    public Task golemFight(AltoClef mod, GamerContext ctx, KitNeed current) {
        if (!golemFighting()) {
            return null;
        }
        Task fight = golem.tick(mod, ctx, current);
        if (fight != null) {
            hud = golem.hud();
        }
        return fight;
    }

    // a new hunt when the trigger says go, or the fight it already started
    public Task golemStart(AltoClef mod, GamerContext ctx, KitNeed current) {
        Task fight = golem == null ? null : golem.tick(mod, ctx, current);
        if (fight != null) {
            hud = golem.hud();
        }
        return fight;
    }

    public Task station(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        Task station = benches.tick(mod, ctx, needs);
        if (station != null) {
            hud = benches.hud();
        }
        return station;
    }

    public Task ruinedPortal(AltoClef mod, GamerContext ctx) {
        Task chest = loot == null ? null : loot.tick(mod, ctx);
        if (chest != null) {
            hud = "Looting a ruined portal";
        }
        return chest;
    }

    public Task villageChest(AltoClef mod, GamerContext ctx, KitNeed current) {
        Task blacksmith = village == null ? null : village.tick(mod, ctx, current);
        if (blacksmith != null) {
            hud = "Looting a village chest";
        }
        return blacksmith;
    }

    public Task bed(AltoClef mod, GamerContext ctx) {
        Task bed = villageBeds.tick(mod, ctx);
        if (bed != null) {
            hud = "Taking a village bed (" + villageBeds.toGo() + " to go)";
        }
        return bed;
    }

    public Task coalDetour(AltoClef mod, GamerContext ctx, KitNeed current) {
        Task ore = coal.tick(mod, ctx, current);
        if (ore != null) {
            hud = coal.hud();
        }
        return ore;
    }

    // something else took the wheel from a detour, it is over (not paused), same as the old tick
    public void endCoal(long now) {
        coal.preempted(now);
    }

    // how the last detour ended, in words
    public String coalEnded() {
        return coal.ended();
    }
}
