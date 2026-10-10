package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.resources.CollectPlanksTask;
import adris.altoclef.tasks.speedrun.gamer.NetherPick;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Bag;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Stage;
import adris.altoclef.tasks.speedrun.gamer.NetherPick.Tier;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import net.minecraft.world.item.Items;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// one pickaxe out of what the bag holds, stage by stage (planks, sticks, table, pick). every stage is the no-collect form of its
// craft, and the bag is asked again every tick: the moment an input is short the task is over (failed()) instead of letting the
// catalogue walk off to find it, because in the nether there is nothing to find
public class NetherPickTask extends Task {
    // a craft that is still going after this long is stuck, the phase tries again later
    private static final double GIVE_UP_SECONDS = 90;

    private final Tier tier;
    private final Supplier<Bag> bag;
    private final BooleanSupplier hasPick;

    private Stage stage;
    private double lastPickAt = Double.NEGATIVE_INFINITY;
    private Task step;
    private boolean failed;

    public NetherPickTask(Tier tier, Supplier<Bag> bag, BooleanSupplier hasPick) {
        this.tier = tier;
        this.bag = bag;
        this.hasPick = hasPick;
    }

    public boolean failed() {
        return failed;
    }

    @Override
    protected void onStart(AltoClef mod) {
        stage = null;
        lastPickAt = Double.NEGATIVE_INFINITY;
        step = null;
        failed = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (hasPick.getAsBoolean()) {
            return null;
        }
        if (overTimeOnTask(GIVE_UP_SECONDS)) {
            failed = true;
            return null;
        }
        double now = timeOnTaskSeconds();
        Bag raw = bag.get();
        // (the grace runs from the last tick the book really had a table, so it cannot hold the craft up for ever)
        if (raw.table() && NetherPick.stage(raw, tier) == Stage.PICK) {
            lastPickAt = now;
        }
        Bag b = NetherPick.graceTable(raw, stage == Stage.PICK, now - lastPickAt);
        Stage next = NetherPick.stage(b, tier);
        if (next == Stage.ABORT) {
            failed = true;
            return null;
        }
        // a new task object only when the stage changes, or its pathing/crafting would restart every tick
        if (next != stage || step == null) {
            stage = next;
            step = build(next, b);
        }
        setDebugState("Making a " + tier.name().toLowerCase() + " pickaxe: " + next.name().toLowerCase(), "Making a pickaxe");
        return step;
    }

    private Task build(Stage s, Bag b) {
        return switch (s) {
            // (the planks task crafts held logs first and only chops when it is out of them, and covered() said we are not)
            case PLANKS -> new CollectPlanksTask(NetherPick.woodNeeded(b, tier));
            case STICKS -> new CraftInInventoryTask(new RecipeTarget(Items.STICK, NetherPick.STICKS, STICKS_RECIPE), false, true);
            case TABLE -> new CraftInInventoryTask(TaskCatalogue.recipeOf("crafting_table"), false, true);
            default -> new CraftInTableTask(TaskCatalogue.recipeOf(NetherPick.catalogueName(tier)), false, true);
        };
    }

    // the same one CollectSticksTask uses: two planks make four sticks
    private static final CraftingRecipe STICKS_RECIPE = CraftingRecipe.newShapedRecipe("sticks",
            new ItemTarget[]{new ItemTarget("planks"), null, new ItemTarget("planks"), null}, 4);

    @Override
    public boolean isFinished(AltoClef mod) {
        return failed || hasPick.getAsBoolean();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NetherPickTask task && task.tier == tier;
    }

    @Override
    protected String toDebugString() {
        return "Making a " + tier.name().toLowerCase() + " pickaxe from the bag";
    }

    @Override
    protected String toHudString() {
        return "Making a pickaxe";
    }
}
