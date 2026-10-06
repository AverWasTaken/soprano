package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.level.block.Blocks;


// the side jobs the overworld phases share: danger filtering, taking our crafting table and furnace back, ruined portal chests.
// all of them return a task to run INSTEAD of the kit task this tick, or null
public final class PrepSupport {
    private final DangerFilter danger = new DangerFilter();
    private final RuinedPortalLoot loot;
    private final VillageLoot village;
    // our crafting table and furnace, taken back at a need boundary
    private final StationPickup stations = new StationPickup();
    private boolean tracking;
    private String hud;

    public PrepSupport(boolean lootRuinedPortals) {
        loot = lootRuinedPortals ? new RuinedPortalLoot() : null;
        village = lootRuinedPortals ? new VillageLoot() : null;
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
        }
        stations.reset();
    }

    // the phase may not call itself done while a station of ours is still owed back, see StationPickup.owed
    public boolean stationOwed() {
        return stations.owed();
    }

    public Task tick(AltoClef mod, GamerContext ctx, KitNeed current) {
        danger.tick(mod);
        hud = null;
        Task station = stations.tick(mod, ctx, current, tracking);
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
        }
        return blacksmith;
    }
}
