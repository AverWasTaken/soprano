package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// takes the crafting table and the furnace WE placed back when the run moves on to a different need while we are still
// standing next to them, and the table also as soon as the crafting at it is over (rules in OwnTables). only when the walk
// back is cheap, a station further than that is forgotten. one pickup at a time, the table first. only positions GamerTask saw us place
// are ever candidates, so a village's table or furnace is never touched
final class StationPickup {
    private final Slot table = new Slot(true);
    private final Slot furnace = new Slot(false);
    private String hud;

    String hud() {
        return hud;
    }

    void reset() {
        table.reset();
        furnace.reset();
        hud = null;
    }

    // true while a station is owed back (a pickup is running, or one would start if the plan had nothing left). the
    // phases hold their own end for this: the decision that a phase is done runs BEFORE its tick, so without it the
    // last craft's table (nothing is "the next need" any more) would be left behind exactly like the bug said
    boolean owed() {
        return table.owed || furnace.owed;
    }

    Task tick(AltoClef mod, GamerContext ctx, KitNeed current, boolean tracking) {
        String need = current == null ? null : current.catalogueName();
        ctx.state().currentNeed = need;
        hud = null;
        Task t = table.tick(mod, ctx, need, tracking);
        if (t != null) {
            hud = "Picking up the crafting table";
            return t;
        }
        t = furnace.tick(mod, ctx, need, tracking);
        if (t != null) {
            hud = "Picking up the furnace";
        }
        return t;
    }

    private static final class Slot {
        private final boolean isTable;
        private final Block block;
        private final Item item;
        private final Set<BlockPos> tried = new HashSet<>();
        // the destroy task first, then the pickup of what it dropped (and whatever was inside, it lands in the same spot)
        private Task pickup;
        private boolean broken;
        private BlockPos target;
        private long startTick;
        private boolean owed;
        private boolean wasOpen;
        private final Set<String> deferredLogged = new HashSet<>();
        // idles and never finishes, the guard that asked for it is what ends it
        private final Task hold = new IdleTask();

        Slot(boolean isTable) {
            this.isTable = isTable;
            block = isTable ? Blocks.CRAFTING_TABLE : Blocks.FURNACE;
            item = isTable ? Items.CRAFTING_TABLE : Items.FURNACE;
        }

        void reset() {
            pickup = null;
            broken = false;
            owed = false;
            wasOpen = false;
            deferredLogged.clear();
        }

        private RunState.StationUse use(GamerContext ctx) {
            return isTable ? ctx.state().tableUse : ctx.state().furnaceUse;
        }

        private List<RunState.Pos> placed(GamerContext ctx) {
            return isTable ? ctx.state().placedTables : ctx.state().placedFurnaces;
        }

        private boolean menuOpen(AltoClef mod) {
            AbstractContainerMenu menu = mod.getPlayer().containerMenu;
            return isTable ? menu instanceof CraftingMenu : menu instanceof FurnaceMenu;
        }

        // a furnace that is smelting right now (the LIT flag shows on the client) must not be taken, the items inside
        // would drop on the floor mid job. the ones a background smelting job owns are jobOwns, below
        private boolean busy(AltoClef mod, BlockPos at) {
            if (isTable) {
                return false;
            }
            BlockState state = mod.getWorld().getBlockState(at);
            return state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
        }

        Task tick(AltoClef mod, GamerContext ctx, String need, boolean tracking) {
            long now = ctx.facts().gameTime();
            RunState.StationUse use = use(ctx);
            if (pickup != null) {
                owed = true;
                double elapsed = (now - startTick) / 20.0;
                if (!broken && pickup.isFinished(mod)) {
                    // the block is gone, now the item it dropped. the position is not a station any more either way
                    broken = true;
                    placed(ctx).remove(new RunState.Pos(target.getX(), target.getY(), target.getZ()));
                    pickup = new PickupDroppedItemTask(item, 1);
                }
                boolean pickedUp = broken && ctx.facts().has(item);
                if (pickedUp) {
                    use.lastRecoveredTick = now;
                }
                if (pickedUp || elapsed > ctx.cfg().overworld.tablePickupSeconds) {
                    // one we could not get in time is written off, same as a chest
                    tried.add(target);
                    pickup = null;
                    broken = false;
                    owed = false;
                } else {
                    return pickup;
                }
            }
            // open right now is in use, stamp it. the need that is using it only on the tick it opened: when the planner
            // moves on while the menu is still closing, stamping the new need would make it look like the same job
            boolean open = menuOpen(mod);
            if (open) {
                use.lastUseTick = now;
                if (!wasOpen) {
                    use.useNeed = need;
                }
            }
            wasOpen = open;
            owed = false;
            if (!tracking || ctx.facts().has(item)) {
                return null;
            }
            OverworldConfig cfg = ctx.cfg().overworld;
            // the backstop alone decides if one is owed. the guards below only delay the start, a phase that ends inside them
            // would otherwise leave the station behind (the craft that just closed its menu is the last thing it does)
            if (OwnTables.startRecovery(now, OwnTables.NEVER, OwnTables.NEVER, use.lastRecoveredTick, false, true, 0, 0, cfg.tableRecoverCooldownSeconds) == OwnTables.Start.NO) {
                return null;
            }
            List<RunState.Pos> own = placed(ctx);
            // somebody (us, a creeper) already took it: not a candidate, not worth remembering
            own.removeIf(pos -> {
                BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
                return mod.getChunkTracker().isChunkLoaded(at) && !mod.getWorld().getBlockState(at).is(block);
            });
            Vec3 player = mod.getPlayer().position();
            double budget = cfg.tableRecoverRadius;
            // the crafting is over (table only, furnaces keep the need boundary): used since it went down, menu shut for a
            // second, nothing in the task tree is crafting at a table and the next need is not a craft that would place it again
            boolean craftsDone = isTable && OwnTables.finishedCrafting(now, use.lastUseTick, use.lastPlaceTick, open,
                    craftRunning(mod), KitNeed.isCraftName(need));
            boolean wantsNow = wants(ctx, need, use, craftsDone);
            if (wantsNow) {
                // this is the moment it would be taken. too far to walk cheaply means we write it off: a table is a log,
                // the old sphere walked us back down a cave for one
                int gone = OwnTables.forgetFar(own, pos -> jobOwns(ctx, pos), player.x, player.y, player.z, budget);
                if (gone > 0) {
                    Debug.logInternal("station pickup: forgot " + gone + " " + name() + "(s), the walk back costs more than " + budget);
                }
            }
            RunState.Pos found = OwnTables.nearest(own, pos -> {
                BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
                return !tried.contains(at) && WorldHelper.canBreak(mod, at) && !busy(mod, at) && !jobOwns(ctx, pos);
            }, player.x, player.y, player.z, budget);
            if (found == null) {
                if (!own.isEmpty()) {
                    // this is the gate that lost a table last time, out of range by the time anything looked
                    deferred("none of ours in range (or tried, busy, owned by a smelting job)");
                }
                return null;
            }
            // from here on a phase that runs out of needs has to wait for us (see StationPickup.owed)
            owed = true;
            if (!wantsNow) {
                deferred("the next need (" + need + ") still wants it");
                return null;
            }
            OwnTables.Start go = OwnTables.startRecovery(now, use.lastUseTick, use.lastPlaceTick, use.lastRecoveredTick, open,
                    true, cfg.tableUseCooldownSeconds, OwnTables.PLACE_GUARD_SECONDS, cfg.tableRecoverCooldownSeconds);
            if (go == OwnTables.Start.HOLD) {
                // a null here hands the tick to the next need, which walks off (a pig for three seconds was enough to
                // lose a table). the guard is a second at most so standing still is cheap, and it cannot hang: the stamps
                // are game time and nothing refreshes them while we idle
                deferred("placed a moment ago, holding still");
                return hold;
            }
            if (go == OwnTables.Start.NO) {
                deferred(open ? "menu is open" : "recovered one lately");
                return null;
            }
            target = new BlockPos(found.x, found.y, found.z);
            startTick = now;
            broken = false;
            pickup = new DestroyBlockTask(target);
            deferredLogged.clear();
            Debug.logInternal("station pickup started at " + found.x + " " + found.y + " " + found.z + " (" + name() + ")");
            return pickup;
        }

        private String name() {
            return isTable ? "table" : "furnace";
        }

        // once per reason per station, a line per tick would bury the one that matters
        private void deferred(String reason) {
            if (deferredLogged.add(reason)) {
                Debug.logInternal("station pickup deferred: " + name() + ", " + reason);
            }
        }

        // the table asks the task tree too: a CraftInTableTask anywhere under the user task means a craft is running or
        // about to (collecting its ingredients counts, the table must stay). the bot is mid-pickup-check here, so the tree
        // is still last tick's kit task, which is exactly what we want to look at
        private boolean craftRunning(AltoClef mod) {
            Task root = mod.getUserTaskChain().getCurrentTask();
            return root != null && root.thisOrChildSatisfies(t -> t instanceof CraftInTableTask);
        }

        private boolean wants(GamerContext ctx, String need, RunState.StationUse use, boolean craftsDone) {
            if (isTable) {
                return OwnTables.wantsTableNow(use.useNeed, need, craftsDone);
            }
            return OwnTables.wantsFurnaceBack(use.useNeed, need, OwnTables.smeltsSoon(need, ctx.facts().count(Items.RAW_IRON)));
        }

        // the async smelting jobs record the furnaces they are cooking in (FurnaceJobs), breaking one would drop the iron
        // on the floor and forget about it
        private boolean jobOwns(GamerContext ctx, RunState.Pos pos) {
            return FurnaceJobs.isBusy(ctx.state(), pos);
        }
    }
}
