package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

// the side jobs the overworld phases share: danger filtering, taking our crafting table back, ruined portal chests.
// all of them return a task to run INSTEAD of the kit task this tick, or null
public final class PrepSupport {
    private final DangerFilter danger = new DangerFilter();
    private final RuinedPortalLoot loot;
    private Task tablePickup;
    private boolean tracking;
    private String hud;

    public PrepSupport(boolean lootRuinedPortals) {
        loot = lootRuinedPortals ? new RuinedPortalLoot() : null;
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
        }
        tablePickup = null;
        hud = null;
    }

    public void onExit(AltoClef mod) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.CRAFTING_TABLE);
            tracking = false;
        }
        if (loot != null) {
            loot.onExit(mod);
        }
    }

    public Task tick(AltoClef mod, GamerContext ctx, KitNeed current) {
        danger.tick(mod);
        hud = null;
        Task table = tableRecovery(mod, ctx, current);
        if (table != null) {
            hud = "Picking up the crafting table";
            return table;
        }
        Task chest = loot == null ? null : loot.tick(mod, ctx);
        if (chest != null) {
            hud = "Looting a ruined portal";
        }
        return chest;
    }

    // a table lying around while we hold none, only when we are about to go mining anyway (taking it back
    // right before a craft would just place it again)
    private Task tableRecovery(AltoClef mod, GamerContext ctx, KitNeed current) {
        if (tablePickup != null && tablePickup.isActive() && !tablePickup.isFinished(mod)) {
            return tablePickup;
        }
        if (current == null || !current.isGathering() || !tracking || ctx.facts().has(Items.CRAFTING_TABLE)) {
            return null;
        }
        Optional<BlockPos> table = mod.getBlockTracker().getNearestTracking(Blocks.CRAFTING_TABLE);
        if (table.isEmpty() || !WorldHelper.canBreak(mod, table.get())) {
            return null;
        }
        if (!table.get().closerToCenterThan(mod.getPlayer().position(), ctx.cfg().overworld.tableRecoverRadius)) {
            return null;
        }
        tablePickup = new MineAndCollectTask(Items.CRAFTING_TABLE, 1, new Block[]{Blocks.CRAFTING_TABLE}, MiningRequirement.HAND);
        return tablePickup;
    }
}
