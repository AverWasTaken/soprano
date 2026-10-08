package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasks.speedrun.gamer.FurnaceJobs;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

// walks to ONE specific furnace we loaded earlier (AsyncSmelting) and empties it. it never places or crafts a furnace and
// never picks a different one, that is the difference from the smelt tasks: a furnace that is gone is the caller's problem
// (FurnaceWatch checks before it hands this out). the furnace's own slots are the truth about what is left to cook
public class CollectFromFurnaceTask extends Task {
    public enum Mode {
        // take what is done. wait if the rest is nearly done, otherwise leave it cooking and report how much is left
        NORMAL,
        // nothing else to do, stay until it is all out (or the cap runs out)
        WAIT_ALL,
        // we are leaving: wait if nearly done, otherwise take the unfinished input back out as well
        TAKE_ALL
    }

    private final BlockPos pos;
    private final Block block;
    private final Mode mode;
    private final String kind;
    private final long waitTicks;
    private final long capTicks;
    private InteractWithBlockTask open;
    private boolean done;
    private int inputLeft;
    // ticks until that input is out, read off the cook arrow when we let go. the job gets re-stamped with it
    private long leftTicks;
    private long waitingSince = -1;
    // WAIT_ALL stands with the screen closed until this game tick, -1 = not idling
    private long idleUntil = -1;
    private static final long REOPEN_TICKS = 200;
    private static final long MIN_IDLE_TICKS = 20;
    private static final long CAPPED_REVISIT_TICKS = 600;

    // waitTicks: "nearly done" for NORMAL and TAKE_ALL. capTicks: the most we stand there once we started waiting, a furnace
    // that never finishes (no fuel, a chunk that stopped ticking) must not hold the bot for ever
    public CollectFromFurnaceTask(BlockPos pos, Block block, String kind, Mode mode, long waitTicks, long capTicks) {
        this.pos = pos;
        this.block = block;
        this.kind = kind;
        this.mode = mode;
        this.waitTicks = waitTicks;
        this.capTicks = capTicks;
    }

    public BlockPos pos() {
        return pos;
    }

    // input still in the furnace when we let go of it, 0 = nothing left to come back for. only meaningful once finished
    public int inputLeft() {
        return inputLeft;
    }

    // how long the input we left behind still needs. only meaningful once finished with inputLeft above 0
    public long leftTicks() {
        return leftTicks;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        // the furnace is the one thing here we must not mine, whatever path we take to it
        mod.getBehaviour().avoidBlockBreaking(pos);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done) {
            return null;
        }
        if (mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu) {
            return atFurnace(mod);
        }
        if (idleUntil >= 0) {
            if (mod.getWorld().getGameTime() < idleUntil) {
                setDebugState("Waiting by the furnace, screen closed");
                return null;
            }
            idleUntil = -1;
        }
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Eating first");
            return null;
        }
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty()) {
            Optional<Slot> fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            }
            mod.getSlotHandler().clickSlot(fit.get(), 0, ClickType.PICKUP);
            return null;
        }
        setDebugState("Walking to the furnace");
        if (open == null) {
            open = new InteractWithBlockTask(pos);
        }
        return open;
    }

    private Task atFurnace(AltoClef mod) {
        ItemStack output = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
        ItemStack input = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
        ItemStack fuel = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
        if (!output.isEmpty()) {
            return takeOut(mod, FurnaceSlot.OUTPUT_SLOT, output, "Taking what is done");
        }
        if (!input.isEmpty()) {
            boolean lit = StorageHelper.getFurnaceFuel() > 0;
            // not lit and no fuel to light it with is a furnace that will never finish. not lit WITH fuel is one tick from lit
            boolean stalled = !lit && fuel.isEmpty();
            boolean nearly = lit && remainingTicks(input) <= waitTicks;
            // it did not finish when it should have. what is in there stays in there (the job keeps its own count), or comes
            // back out when we are leaving
            boolean capped = waitingSince >= 0 && mod.getWorld().getGameTime() - waitingSince > capTicks;
            if (!stalled && !capped && (mode == Mode.WAIT_ALL || nearly)) {
                return waitHere(mod, mode == Mode.WAIT_ALL);
            }
            if (capped && mode != Mode.TAKE_ALL) {
                inputLeft = input.getCount();
                // it should have been done by now and was not, so the arrow is no use: come back in half a minute, not now
                leftTicks = Math.max(remainingTicks(input), CAPPED_REVISIT_TICKS);
                done = true;
                return null;
            }
            if (stalled || capped || mode == Mode.TAKE_ALL) {
                // the raw stuff goes back in the bag, the planner sees it there and smelts it again
                return takeOut(mod, FurnaceSlot.INPUT_SLOT_MATERIALS, input, "Taking the unfinished input back");
            }
            // not close to done (it may have sat in an unloaded chunk and barely cooked): leave it, the job gets the real time
            inputLeft = input.getCount();
            leftTicks = remainingTicks(input);
            done = true;
            return null;
        }
        // all out. the leftover fuel is worth carrying, the next smelt needs it
        if (!fuel.isEmpty() && AltoSettings.isSupportedFuel(fuel.getItem())) {
            return takeOut(mod, FurnaceSlot.INPUT_SLOT_FUEL, fuel, "Taking the spare fuel");
        }
        inputLeft = 0;
        done = true;
        return null;
    }

    // closed = let go of the screen and stand next to the furnace until the next output is due (or the reopen timer, whichever
    // is first). the gui open for 6 minutes was a bot that could not eat, could not see a creeper and looked afk to the
    // server. the short "nearly done" wait of the other modes keeps the screen, it is a few seconds
    private Task waitHere(AltoClef mod, boolean closed) {
        long now = mod.getWorld().getGameTime();
        if (waitingSince < 0) {
            waitingSince = now;
        }
        if (!closed) {
            setDebugState("Waiting for the furnace");
            return null;
        }
        // the arrow is already 0..1 (StorageHelper.getFurnaceCookPercent), dividing it by 24 read every item as just started
        double arrow = Math.min(1.0, Math.max(0, StorageHelper.getFurnaceCookPercent()));
        long nextOutput = Math.round(FurnaceJobs.ticksPerItem(kind) * (1.0 - arrow));
        idleUntil = now + idleTicks(nextOutput);
        StorageHelper.closeScreen();
        setDebugState("Waiting by the furnace, screen closed");
        return null;
    }

    // how long to stand with the screen closed: until the next item is out, but never longer than the reopen timer (the
    // furnace may have stalled) and never so short that we flicker the screen open every other tick
    public static long idleTicks(long ticksUntilNextOutput) {
        return Math.max(MIN_IDLE_TICKS, Math.min(REOPEN_TICKS, ticksUntilNextOutput + 10));
    }

    // shift click, the furnace menu sends it to the inventory in one go. a full bag would make that a silent no-op for ever
    private Task takeOut(AltoClef mod, Slot slot, ItemStack stack, String why) {
        if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, false).isEmpty()) {
            return new EnsureFreeInventorySlotTask();
        }
        setDebugState(why);
        mod.getSlotHandler().clickSlot(slot, 0, ClickType.QUICK_MOVE);
        return null;
    }

    // cook progress of the item that is cooking right now, 0..1. it used to be 24 pixels and the /24 outlived that,
    // which made every wake up and re-stamp up to a whole item late (the ~5 s off on a smoker)
    private long remainingTicks(ItemStack input) {
        double arrow = Math.min(1.0, Math.max(0, StorageHelper.getFurnaceCookPercent()));
        return FurnaceJobs.remainingTicks(kind, input.getCount(), arrow);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        if (mod.getPlayer().containerMenu instanceof AbstractFurnaceMenu) {
            StorageHelper.closeScreen();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CollectFromFurnaceTask task && task.pos.equals(pos) && task.mode == mode && task.block == block;
    }

    @Override
    protected String toHudString() {
        return "Collecting from the furnace";
    }

    @Override
    protected String toDebugString() {
        return "Collecting from the furnace at " + pos.toShortString();
    }
}
