package adris.altoclef;

import adris.altoclef.tasks.speedrun.gamer.GamerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.trace.CursorWatch;
import adris.altoclef.trace.Lines;
import adris.altoclef.trace.MonitorErrors;
import adris.altoclef.trace.Trace;
import adris.altoclef.trace.Vitals;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

// the two things the log keeps an eye on that no task owns: a line of vitals every few seconds while we drive, and an
// item that has been on the cursor for too long. both only ever flag, nothing here touches the bot. every tick is one
// integer compare, the reading and formatting happen when a line is due
public final class Monitors {

    // 5 s of ticks
    private static final int VITALS_EVERY = 100;
    // 1 s of ticks
    private static final int CURSOR_EVERY = 20;

    private int vitalsTicks;
    private int cursorTicks;
    private final CursorWatch cursorWatch = new CursorWatch();

    // while the task runner is active, after it has ticked so the chain's task list is this tick's
    public void onActiveTick(AltoClef mod) {
        if (++vitalsTicks < VITALS_EVERY) {
            return;
        }
        vitalsTicks = 0;
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                Debug.logInternal(vitals(mod, player));
            }
        } catch (RuntimeException e) {
            // a log line is never worth a crash in the tick, but a monitor that always throws should not look like a quiet one
            broke("vitals", e);
        }
    }

    private static final MonitorErrors ERRORS = new MonitorErrors();

    // the first throw from each monitor is said once, after that it stays swallowed. the saying can't throw either
    public static void broke(String monitor, RuntimeException error) {
        try {
            String line = ERRORS.first(monitor, error);
            if (line != null) {
                Debug.logInternal(line);
            }
        } catch (RuntimeException ignored) {
            // nothing left to tell
        }
    }

    // every tick, runner or not: the cursor can get stuck while we are idle too
    public void onTick() {
        if (++cursorTicks < CURSOR_EVERY) {
            return;
        }
        cursorTicks = 0;
        try {
            watchCursor();
        } catch (RuntimeException e) {
            broke("cursor", e);
        }
    }

    private void watchCursor() {
        ItemStack carried = StorageHelper.getItemStackInCursorSlot();
        boolean screen = Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>;
        long now = System.currentTimeMillis();
        if (cursorWatch.tick(!carried.isEmpty(), screen, now)) {
            Debug.logInternal(Lines.cursorStuck(name(carried.getItem()), carried.getCount(), cursorWatch.stuckMs(now) / 1000));
            Trace.dump("monitor:cursor");
        }
    }

    private static String vitals(AltoClef mod, LocalPlayer player) {
        ItemStack carried = StorageHelper.getItemStackInCursorSlot();
        ItemStack held = player.getMainHandItem();
        BlockPos at = player.blockPosition();
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        String task = "";
        if (chain != null) {
            List<Task> tasks = chain.getTasks();
            if (!tasks.isEmpty()) {
                task = String.valueOf(tasks.get(0));
            }
        }
        Vitals.Gamer gamer = mod.getUserTaskChain().getCurrentTask() instanceof GamerTask g ? g.vitals() : null;
        return Vitals.format(player.getHealth(), player.getAbsorptionAmount(), player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(), player.getArmorValue(), held.isEmpty() ? null : name(held.getItem()),
                carried.isEmpty() ? null : name(carried.getItem()), carried.getCount(), at.getX(), at.getY(), at.getZ(),
                WorldHelper.getCurrentDimension().name().toLowerCase(Locale.ROOT), chain == null ? null : chain.getClass().getSimpleName(), task, gamer);
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
