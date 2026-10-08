package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.AsyncSmelting;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltInSmokerTask;
import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.CookTrip;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.FoodHelper;
import net.minecraft.world.item.Item;

// cooks the raw meat in the bag, one kind at a time (the input slot only holds one). this is the gamer's own cook: CollectFoodTask
// only cooks inside its own need, and that need is met the moment raw meat counts at its cooked value. with async smelting on it
// loads and walks away, the phases come back for the output like any other furnace job
public class CookRawFoodTask extends Task {
    // no change in the bag for this long and the cook is not going anywhere (no fuel to be found, no room for a smoker)
    private static final long GIVE_UP_TICKS = 150 * 20;

    private final boolean smoker;
    private Task smelt;
    private Item smelting;
    private int lastRaw;
    private int lastCooked;
    private long lastChange;
    private boolean gaveUp;
    // the smelt loads and walks away (AsyncSmelting) instead of standing there until it is cooked. then "finished" means the one
    // input slot is full and the next kind waits for this batch to come out. the job lands in the gamer's list a tick later, so
    // this is decided up front and not by looking at the pending food
    private boolean async;

    public CookRawFoodTask(boolean smoker) {
        this.smoker = smoker;
    }

    @Override
    protected void onStart(AltoClef mod) {
        smelt = null;
        smelting = null;
        lastRaw = -1;
        lastCooked = -1;
        gaveUp = false;
        lastChange = mod.getWorld().getGameTime();
    }

    // the kind with the most pieces, one trip cooks the biggest pile
    private static Item pickRaw(AltoClef mod) {
        Item best = null;
        int bestCount = 0;
        for (Item raw : CookGate.RAW_MEAT) {
            int n = mod.getItemStorage().getItemCount(raw);
            if (n > bestCount) {
                best = raw;
                bestCount = n;
            }
        }
        return best;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (gaveUp || (smelt != null && async && smelt.isFinished(mod))) {
            CookTrip.release();
            return null;
        }
        CookTrip.commit(smoker, now);
        if (smelt != null && smelt.isFinished(mod)) {
            // cooked in place (async cooking off): that kind is done and the slot is empty again, on to the next pile. stopping
            // here used to leave the need up with a finished task under it, and the bot stood there until the watchdog
            smelt = null;
            lastRaw = -1;
            lastCooked = -1;
            lastChange = now;
        }
        if (smelt == null) {
            smelting = pickRaw(mod);
            if (smelting == null) {
                // nothing left to cook and nothing loading: let go of the station, or a sync cook (async cooking off) kept the
                // cook need alive through the stamp until the watchdog
                CookTrip.release();
                return null;
            }
            smelt = make(mod, smelting);
        }
        // a sync cook empties the bag as it goes and fills it with the cooked kind, either is the bag moving
        int raw = mod.getItemStorage().getItemCount(smelting);
        int cooked = mod.getItemStorage().getItemCount(FoodHelper.cookedForm(smelting));
        if (raw != lastRaw || cooked != lastCooked) {
            lastRaw = raw;
            lastCooked = cooked;
            lastChange = now;
        }
        if (now - lastChange > GIVE_UP_TICKS) {
            Debug.logMessage("Cooking the " + smelting.getDescriptionId() + " is going nowhere, leaving it raw for a while.");
            CookTrip.suspend(now);
            gaveUp = true;
            smelt = null;
            return null;
        }
        setDebugState("Cooking " + smelting.getDescriptionId());
        return smelt;
    }

    private Task make(AltoClef mod, Item raw) {
        int rawCount = mod.getItemStorage().getItemCount(raw);
        Item cooked = FoodHelper.cookedForm(raw);
        // total of the cooked kind we want to end up holding, same sum CollectFoodTask uses
        int toSmelt = rawCount + mod.getItemStorage().getItemCount(cooked);
        SmeltTarget target = new SmeltTarget(new ItemTarget(cooked, toSmelt), new ItemTarget(raw, rawCount));
        async = AsyncSmelting.wants(target.getItem());
        if (smoker) {
            SmeltInSmokerTask task = new SmeltInSmokerTask(target);
            task.ignoreMaterials();
            return task;
        }
        SmeltInFurnaceTask task = new SmeltInFurnaceTask(target);
        task.ignoreMaterials();
        return task;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // the smelt task closes its own screen. a chain taking over for a bit is not the end of the cook, the station stays picked
        if (!isInterrupting()) {
            CookTrip.release();
        }
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        CookTrip.release();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // only reads state, FurnaceWatch asks this between ticks
        if (gaveUp) {
            return true;
        }
        if (smelt != null && async && smelt.isFinished(mod)) {
            return true;
        }
        // nothing raw left and nothing half cooked in front of us
        return isActive() && pickRaw(mod) == null && (smelt == null || smelt.isFinished(mod));
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CookRawFoodTask task && task.smoker == smoker;
    }

    @Override
    protected String toDebugString() {
        return "Cook the raw meat in a " + (smoker ? "smoker" : "furnace");
    }

    @Override
    protected String toHudString() {
        return "Cooking the raw meat";
    }
}
