package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.level.block.Blocks;

import java.util.List;


// the side jobs the overworld phases share: danger filtering, taking our crafting table and furnace back, ruined portal chests,
// village chests, the odd iron golem.
// all of them return a task to run INSTEAD of the kit task this tick, or null
public final class PrepSupport {
    private final DangerFilter danger = new DangerFilter();
    private final RuinedPortalLoot loot;
    private final VillageLoot village;
    private final VillageBeds villageBeds = new VillageBeds();
    // our crafting table and furnace, taken back at a need boundary
    private final StationPickup stations = new StationPickup();
    private final GolemHunt golem;
    private boolean tracking;
    private String hud;

    public PrepSupport(boolean lootRuinedPortals) {
        loot = lootRuinedPortals ? new RuinedPortalLoot() : null;
        village = lootRuinedPortals ? new VillageLoot() : null;
        golem = lootRuinedPortals ? new GolemHunt() : null;
    }

    public String hud() {
        return hud;
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
        stations.reset();
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
        stations.reset();
    }

    // the phase may not call itself done while a station of ours is still owed back, see StationPickup.owed
    public boolean stationOwed() {
        return stations.owed();
    }

    // the bot is standing by a smoker on purpose (SmeltFiller.standBy): a few seconds of waiting is the plan, so the side jobs that
    // walk off (village chests and beds, ruined portals, a fresh golem, the table pickup) can all wait the 40 s. only a golem
    // fight already going outranks it, same as it outranks everything
    public Task tickStandBy(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        danger.tick(mod, ctx.state());
        hud = null;
        if (golem != null && golem.active()) {
            Task fight = golem.tick(mod, ctx, needs.isEmpty() ? null : needs.get(0));
            if (fight != null) {
                hud = golem.hud();
                return fight;
            }
        }
        return null;
    }

    // `needs` is what the phase still has to do, in order (empty = nothing)
    public Task tick(AltoClef mod, GamerContext ctx, List<KitNeed> needs) {
        KitNeed current = needs.isEmpty() ? null : needs.get(0);
        danger.tick(mod, ctx.state());
        hud = null;
        // a golem fight in progress outranks everything, a chest is not worth stepping off the pillar for
        if (golem != null && golem.active()) {
            Task fight = golem.tick(mod, ctx, current);
            if (fight != null) {
                hud = golem.hud();
                return fight;
            }
        }
        Task station = stations.tick(mod, ctx, current, needs, tracking);
        if (station != null) {
            hud = stations.hud();
            return station;
        }
        Task chest = loot == null ? null : loot.tick(mod, ctx);
        if (chest != null) {
            hud = "Looting a ruined portal";
            return chest;
        }
        Task blacksmith = village == null ? null : village.tick(mod, ctx, current);
        if (blacksmith != null) {
            hud = "Looting a village chest";
            return blacksmith;
        }
        // not behind the lootRuinedPortals flag: GATHER is where we usually meet the village, and a bed is a bed
        // and before the golem, a bed is a few seconds of punching and a golem is a minute on a pillar
        Task bed = villageBeds.tick(mod, ctx);
        if (bed != null) {
            hud = "Taking a village bed (" + villageBeds.toGo() + " to go)";
            return bed;
        }
        Task fight = golem == null ? null : golem.tick(mod, ctx, current);
        if (fight != null) {
            hud = golem.hud();
        }
        return fight;
    }
}
