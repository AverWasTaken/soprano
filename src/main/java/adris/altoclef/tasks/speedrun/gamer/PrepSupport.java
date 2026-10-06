package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// the side jobs the overworld phases share: danger filtering, taking our crafting table back, ruined portal chests.
// all of them return a task to run INSTEAD of the kit task this tick, or null
public final class PrepSupport {
    private final DangerFilter danger = new DangerFilter();
    private final RuinedPortalLoot loot;
    private final VillageLoot village;
    private final Set<BlockPos> tablesTried = new HashSet<>();
    // the destroy task first, then the pickup of what it dropped
    private Task tablePickup;
    private boolean tableBroken;
    private BlockPos tableTarget;
    private long tableStartTick;
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
        tablePickup = null;
        tableBroken = false;
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
            return chest;
        }
        Task blacksmith = village == null ? null : village.tick(mod, ctx, current);
        if (blacksmith != null) {
            hud = "Looting a village chest";
        }
        return blacksmith;
    }

    // a table WE placed lying around while we hold none, only when we are about to go mining anyway (taking it back
    // right before a craft would just place it again). it used to be "the nearest crafting table", which in a village
    // is the village's: one run took a table out of a house for no reason. now only positions that GamerTask saw us
    // place (RunState.placedTables) are ever candidates, and the task breaks exactly that block
    private Task tableRecovery(AltoClef mod, GamerContext ctx, KitNeed current) {
        if (tablePickup != null) {
            double elapsed = (ctx.facts().gameTime() - tableStartTick) / 20.0;
            if (!tableBroken && tablePickup.isFinished(mod)) {
                // the block is gone, now the item it dropped. the position is not a table any more either way
                tableBroken = true;
                ctx.state().placedTables.remove(new RunState.Pos(tableTarget.getX(), tableTarget.getY(), tableTarget.getZ()));
                tablePickup = new PickupDroppedItemTask(Items.CRAFTING_TABLE, 1);
            }
            boolean pickedUp = tableBroken && ctx.facts().has(Items.CRAFTING_TABLE);
            if (pickedUp) {
                ctx.state().lastTableRecoveredTick = ctx.facts().gameTime();
            }
            if (pickedUp || elapsed > ctx.cfg().overworld.tablePickupSeconds) {
                // a table we could not get in time is written off, same as a chest
                tablesTried.add(tableTarget);
                tablePickup = null;
                tableBroken = false;
            } else {
                return tablePickup;
            }
        }
        // a table open right now is a table in use, stamp it so the cooldown runs from the last time it was
        boolean menuOpen = mod.getPlayer().containerMenu instanceof CraftingMenu;
        long now = ctx.facts().gameTime();
        if (menuOpen) {
            ctx.state().lastTableUseTick = now;
        }
        if (current == null || !current.isGathering() || !tracking || ctx.facts().has(Items.CRAFTING_TABLE)) {
            return null;
        }
        OverworldConfig cfg = ctx.cfg().overworld;
        if (!OwnTables.mayStartRecovery(now, ctx.state().lastTableUseTick, ctx.state().lastTableRecoveredTick, menuOpen,
                cfg.tableUseCooldownSeconds, cfg.tableRecoverCooldownSeconds)) {
            return null;
        }
        List<RunState.Pos> own = ctx.state().placedTables;
        // somebody (us, a creeper) already took it: not a candidate, not worth remembering
        own.removeIf(pos -> {
            BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
            return mod.getChunkTracker().isChunkLoaded(at) && !mod.getWorld().getBlockState(at).is(Blocks.CRAFTING_TABLE);
        });
        Vec3 player = mod.getPlayer().position();
        RunState.Pos found = OwnTables.nearest(own, pos -> {
            BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
            return !tablesTried.contains(at) && WorldHelper.canBreak(mod, at);
        }, player.x, player.y, player.z, ctx.cfg().overworld.tableRecoverRadius);
        if (found == null) {
            return null;
        }
        tableTarget = new BlockPos(found.x, found.y, found.z);
        tableStartTick = ctx.facts().gameTime();
        tableBroken = false;
        tablePickup = new DestroyBlockTask(tableTarget);
        return tablePickup;
    }
}
