package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeCoverHook;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// walk to a spot the blazes cannot see and stand there. with a shield and a blaze winding up a volley, the shield goes up
// toward it. the shield is down the rest of the time on purpose: the food chain refuses to chew while we are blocking
public final class BlazeCoverTask extends Task {

    // a spot we cannot get to in this long gets handed back so the picker can find a different one
    private static final int STUCK_TICKS = 120;
    // no step closer than this for this long is a walk that is going nowhere, and then the food chain may eat after all
    private static final double PROGRESS_BLOCKS = 0.25;
    private static final int STALL_TICKS = 40;

    private final BlockPos spot;
    private final boolean allowShield;
    private final Supplier<Blaze> shieldAt;
    private final Consumer<BlockPos> onStuck;
    // our own aura object just for its shield handling (food in hand, sneak, pausing the path), same code mob defense uses
    // the shield keys under our name, like a plain hold from onTick always was: letGoOfKeys on a finished root task lifts them
    private final KillAura shield = new KillAura(this);
    private final BooleanSupplier threatened;
    private int ticksTrying;
    private double closest;
    private int ticksNoProgress;

    public BlazeCoverTask(BlockPos spot, boolean allowShield, Supplier<Blaze> shieldAt, Consumer<BlockPos> onStuck,
                          BooleanSupplier threatened) {
        this.spot = spot;
        this.allowShield = allowShield;
        this.shieldAt = shieldAt;
        this.onStuck = onStuck;
        this.threatened = threatened;
    }

    private boolean atSpot(LocalPlayer player) {
        return Math.abs(player.getX() - (spot.getX() + 0.5)) < 0.7
                && Math.abs(player.getZ() - (spot.getZ() + 0.5)) < 0.7
                && Math.abs(player.getY() - spot.getY()) < 1.2;
    }

    @Override
    protected void onStart(AltoClef mod) {
        ticksTrying = 0;
        closest = Double.POSITIVE_INFINITY;
        ticksNoProgress = 0;
    }

    // tells the food chain a walk is on and how it is going. eating slows the walk to a crawl, so it waits for the spot
    private void noteWalk(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        double distance = player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(spot));
        if (distance < closest - PROGRESS_BLOCKS) {
            closest = distance;
            ticksNoProgress = 0;
        } else {
            ticksNoProgress++;
        }
        BlazeCoverHook.set(mod.getWorld().getGameTime(), distance, ticksNoProgress >= STALL_TICKS, threatened.getAsBoolean());
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!atSpot(mod.getPlayer())) {
            noteWalk(mod);
            shield.stopShielding(mod);
            if (++ticksTrying > STUCK_TICKS) {
                ticksTrying = 0;
                onStuck.accept(spot);
            }
            setDebugState("Walking to cover");
            return new GetToBlockTask(spot);
        }
        ticksTrying = 0;
        BlazeCoverHook.clear();
        Blaze target = allowShield ? shieldAt.get() : null;
        boolean hasShield = mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
        ItemStack offhand = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
        if (target == null || !hasShield || mod.getPlayer().getCooldowns().isOnCooldown(offhand)) {
            shield.stopShielding(mod);
            setDebugState("Hiding from blazes");
            return null;
        }
        setDebugState("Shielding from a blaze");
        LookHelper.lookAt(mod, target.getEyePosition());
        if (offhand.getItem() != Items.SHIELD) {
            mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
        } else {
            shield.startShielding(mod);
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        BlazeCoverHook.clear();
        shield.stopShielding(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BlazeCoverTask task && task.spot.equals(spot) && task.allowShield == allowShield;
    }

    @Override
    protected String toDebugString() {
        return "Taking cover from blazes at " + spot;
    }

    @Override
    protected String toHudString() {
        return "Hiding from blazes";
    }
}
