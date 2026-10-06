package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
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
// standing next to them (rules in OwnTables). one pickup at a time, the table first. only positions GamerTask saw us place
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

        Slot(boolean isTable) {
            this.isTable = isTable;
            block = isTable ? Blocks.CRAFTING_TABLE : Blocks.FURNACE;
            item = isTable ? Items.CRAFTING_TABLE : Items.FURNACE;
        }

        void reset() {
            pickup = null;
            broken = false;
            owed = false;
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
            // open right now is in use, stamp it (and the need that is using it) so the debounce runs from the last time
            boolean open = menuOpen(mod);
            if (open) {
                use.lastUseTick = now;
                use.useNeed = need;
            }
            owed = false;
            if (!tracking || ctx.facts().has(item)) {
                return null;
            }
            OverworldConfig cfg = ctx.cfg().overworld;
            // the backstop alone decides if one is owed. the debounce only delays the start, a phase that ends inside it
            // would otherwise leave the station behind (the craft that just closed its menu is the last thing it does)
            if (!OwnTables.mayStartRecovery(now, OwnTables.NEVER, use.lastRecoveredTick, false, 0, cfg.tableRecoverCooldownSeconds)) {
                return null;
            }
            List<RunState.Pos> own = placed(ctx);
            // somebody (us, a creeper) already took it: not a candidate, not worth remembering
            own.removeIf(pos -> {
                BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
                return mod.getChunkTracker().isChunkLoaded(at) && !mod.getWorld().getBlockState(at).is(block);
            });
            Vec3 player = mod.getPlayer().position();
            RunState.Pos found = OwnTables.nearest(own, pos -> {
                BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
                return !tried.contains(at) && WorldHelper.canBreak(mod, at) && !busy(mod, at) && !jobOwns(ctx, pos);
            }, player.x, player.y, player.z, ctx.cfg().overworld.tableRecoverRadius);
            if (found == null) {
                return null;
            }
            // from here on a phase that runs out of needs has to wait for us (see StationPickup.owed)
            owed = true;
            if (!wants(ctx, need, use)
                    || !OwnTables.mayStartRecovery(now, use.lastUseTick, use.lastRecoveredTick, open, cfg.tableUseCooldownSeconds, cfg.tableRecoverCooldownSeconds)) {
                return null;
            }
            target = new BlockPos(found.x, found.y, found.z);
            startTick = now;
            broken = false;
            pickup = new DestroyBlockTask(target);
            return pickup;
        }

        private boolean wants(GamerContext ctx, String need, RunState.StationUse use) {
            if (isTable) {
                return OwnTables.wantsTableBack(use.useNeed, need);
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
