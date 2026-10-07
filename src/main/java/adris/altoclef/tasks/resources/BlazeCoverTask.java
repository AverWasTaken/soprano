package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.control.KillAura;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
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

    private final BlockPos spot;
    private final boolean allowShield;
    private final Supplier<Blaze> shieldAt;
    private final Consumer<BlockPos> onStuck;
    // our own aura object just for its shield handling (food in hand, sneak, pausing the path), same code mob defense uses
    private final KillAura shield = new KillAura();
    private int ticksTrying;

    public BlazeCoverTask(BlockPos spot, boolean allowShield, Supplier<Blaze> shieldAt, Consumer<BlockPos> onStuck) {
        this.spot = spot;
        this.allowShield = allowShield;
        this.shieldAt = shieldAt;
        this.onStuck = onStuck;
    }

    private boolean atSpot(LocalPlayer player) {
        return Math.abs(player.getX() - (spot.getX() + 0.5)) < 0.7
                && Math.abs(player.getZ() - (spot.getZ() + 0.5)) < 0.7
                && Math.abs(player.getY() - spot.getY()) < 1.2;
    }

    @Override
    protected void onStart(AltoClef mod) {
        ticksTrying = 0;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!atSpot(mod.getPlayer())) {
            shield.stopShielding(mod);
            if (++ticksTrying > STUCK_TICKS) {
                ticksTrying = 0;
                onStuck.accept(spot);
            }
            setDebugState("Walking to cover");
            return new GetToBlockTask(spot);
        }
        ticksTrying = 0;
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
