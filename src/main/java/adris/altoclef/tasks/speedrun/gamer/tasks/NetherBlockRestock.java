package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;

import java.util.List;

// the netherrack dig that keeps a bag of throwaways topped up, one home for the sweep and the death trip. NetherRestock
// decides when, this owns the task that does it
public class NetherBlockRestock {
    private final NetherRestock restock = new NetherRestock();
    private MineAndCollectTask task;

    public boolean isActive() {
        return restock.isActive();
    }

    public int exhaustedEnds() {
        return restock.exhaustedEnds();
    }

    public void clearExhaustedEnds() {
        restock.clearExhaustedEnds();
    }

    // the count the restock goes by, for the phase that decides on a trip home when the dig keeps coming up empty
    public static int heldThrowaways(AltoClef mod) {
        return mod.getItemStorage().getItemCount(placeableOnly(mod.getClientBaritoneSettings().acceptableThrowawayItems.value));
    }

    // the whole bag, counted with the list baritone places from, and the dig goes for exactly that list too (the alto build
    // list also has soul sand and magma in it, which baritone never places, so a bag of those would end the dig and stay
    // "empty"). MineAndCollectTask breaks through baritone, so its lava checks apply to every block. no pick (the same check
    // MineAndCollectTask makes) = no restock, a hand cannot dig netherrack. returns the dig to run, or null for "not now"
    public Task tick(AltoClef mod, boolean gaveUp, double now) {
        return tick(mod, gaveUp ? NetherRestock.GIVE_UP_LOW : NetherRestock.LOW, now);
    }

    // startBelow: see NetherRestock.update
    public Task tick(AltoClef mod, int startBelow, double now) {
        return tick(mod, startBelow, NetherRestock.TARGET, now);
    }

    // stopAt: how full the bag has to get before the dig ends (a walk home only wants a short top up)
    public Task tick(AltoClef mod, int startBelow, int stopAt, double now) {
        Item[] placeable = placeableOnly(mod.getClientBaritoneSettings().acceptableThrowawayItems.value);
        int held = mod.getItemStorage().getItemCount(placeable);
        boolean exhausted = task != null && task.wandering();
        boolean on = restock.update(held, startBelow, stopAt, StorageHelper.miningRequirementMet(mod, MiningRequirement.WOOD),
                exhausted, now);
        if (on && task == null) {
            // the band is frozen where the dig starts, following live feet would walk it down its own shaft
            BlockPos origin = mod.getPlayer().blockPosition();
            // the same array counts and digs, two lists would disagree on done and hand a finished task back every tick
            task = new MineAndCollectTask(new ItemTarget[]{new ItemTarget(placeable, stopAt)},
                    MiningRequirement.WOOD).onlyWhere(p -> NetherRestock.inRestockBand(origin, p) && !touchesLava(mod, p)
                            && NetherRestock.keepsFooting(feetY(mod), p, solidBelow(mod, p)));
        } else if (!on) {
            task = null;
        }
        return on ? task : null;
    }

    // the block row the feet are in, not the block the y rounds down to: on soul sand (0.875 tall) the floor row would
    // otherwise read as level with the feet and skip the solid under check
    private static int feetY(AltoClef mod) {
        return (int) Math.ceil(mod.getPlayer().getY() - 0.01);
    }

    private static boolean solidBelow(AltoClef mod, BlockPos p) {
        BlockPos under = p.below();
        var state = mod.getWorld().getBlockState(under);
        return state.getFluidState().isEmpty() && state.isFaceSturdy(mod.getWorld(), under, Direction.UP);
    }

    // a block with lava on any side is either holding the lava back or holding us up over it (a bridge is all lava
    // underneath). neither is ours to mine for a few spare blocks
    private static boolean touchesLava(AltoClef mod, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (mod.getWorld().getFluidState(p.relative(d)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    // baritone's list has items it counts but can never place (nether brick the item, leaves), and the merge can list an
    // item twice. distinct and the shared build block rule only
    static Item[] placeableOnly(List<Item> items) {
        return items.stream().distinct()
                .filter(AltoSettings::isBuildBlock)
                .toArray(Item[]::new);
    }
}
