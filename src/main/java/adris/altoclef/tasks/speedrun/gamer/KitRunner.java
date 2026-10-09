package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.CookRawFoodTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// runs the FIRST unsatisfied need of a list as one catalogue task and keeps that task object until the need changes
// (a new object per tick restarts its sub state, and the catalogue tasks have a lot of it). tells the watchdog when
// we got closer to what we are chasing
public final class KitRunner {
    // the seam the tests use, the real one asks the catalogue
    public interface Builder {
        Task build(KitNeed need, List<Item> equip, int foodTarget);
    }

    private final Builder builder;
    private KitNeed key;
    // game tick the current key started at, for the dwell
    private long keySince;
    // game tick the key dropped out of the plan, HeadLatch.NEVER while it is in it
    private long missingSince = HeadLatch.NEVER;
    private List<Item> equipKey = List.of();
    private int foodKey;
    private Task task;
    // progressOf per need as of the last time that need was the head
    private final Map<String, Integer> lastProgress = new HashMap<>();
    private String hud;
    // one line per phase entry (reset() is the entry) with everything the planner wants, first non empty plan only
    private boolean planLogged;
    // the food need was in the last list, so watchFood only speaks when that changes
    private boolean foodOn;
    // the list we were last handed and the need picked out of it, for the card (GamerHud). the key above is the same
    // need but it is also the task cache's key, keep the two jobs apart
    private List<KitNeed> needs = List.of();
    private KitNeed current;

    public KitRunner() {
        this(KitRunner::build);
    }

    public KitRunner(Builder builder) {
        this.builder = builder;
    }

    public void reset() {
        key = null;
        keySince = 0;
        missingSince = HeadLatch.NEVER;
        equipKey = List.of();
        foodKey = 0;
        task = null;
        lastProgress.clear();
        hud = null;
        planLogged = false;
        foodOn = false;
        needs = List.of();
        current = null;
    }

    // the plan as of the last run(), empty when there was nothing to do
    public List<KitNeed> needs() {
        return needs;
    }

    // the need the task is for, null when there was nothing to do
    public KitNeed current() {
        return current;
    }

    // CollectFoodTask counts anything edible (rotten flesh, spider eyes...) but our facts only count what we would eat,
    // so with a bag of junk it thinks it is done and wanders. ask it for the junk on top (FoodPlan.collect)
    public static int foodTarget(KitNeed need, FoodPlan food) {
        return KitNeed.FOOD.equals(need.catalogueName()) ? food.collect(need.count()) : need.count();
    }

    // "log x13, wooden_axe x1, ..." so a kit item that never shows up in the plan is obvious in the log
    static String describe(List<KitNeed> needs) {
        StringBuilder sb = new StringBuilder();
        for (KitNeed n : needs) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(n.catalogueName()).append(" x").append(n.count());
        }
        return sb.toString();
    }

    // plain words for what the current need is, null when there is nothing to do
    public String hud() {
        return hud;
    }

    public Task run(GamerContext ctx, List<KitNeed> needs) {
        this.needs = needs;
        if (needs.isEmpty()) {
            // nothing to run, so whatever we were on is let go. kept, it would sit out a whole dwell against the next plan's head
            key = null;
            task = null;
            missingSince = HeadLatch.NEVER;
            watchFood(ctx, needs, null);
            hud = null;
            current = null;
            return null;
        }
        if (!planLogged) {
            planLogged = true;
            Debug.logMessage("Kit plan: " + describe(needs));
        }
        GamerFacts f = ctx.facts();
        watchGone(f, needs);
        // the need we are running keeps the head for a few seconds, see HeadLatch
        KitNeed need = HeadLatch.pick(key, keySince, missingSince, f.gameTime(), needs, f);
        if (missingSince != HeadLatch.NEVER && !need.equals(key)) {
            Debug.logInternal("kit: " + key.catalogueName() + " x" + key.count() + " handed over to " + need.catalogueName() + " x" + need.count()
                    + (HeadLatch.satisfied(key, f) ? ", we hold it" : " after " + (f.gameTime() - missingSince) + " ticks out of the plan"));
            missingSince = HeadLatch.NEVER;
        }
        watchFood(ctx, needs, need);
        List<Item> equip = KitNeed.EQUIP_ARMOR.equals(need.catalogueName()) ? KitPlanner.toEquip(f, ctx.cfg().overworld) : List.of();
        int food = foodTarget(need, ctx.food());
        watchProgress(ctx, need);
        if (!need.equals(key) || !equip.equals(equipKey) || food != foodKey) {
            if (!need.equals(key)) {
                keySince = f.gameTime();
            }
            key = need;
            equipKey = equip;
            foodKey = food;
            task = builder.build(need, equip, food);
            if (task == null) {
                ctx.fail("no way to get " + need.catalogueName());
            }
        }
        hud = words(need, f);
        current = need;
        return task;
    }

    // the food need showing up in the list or dropping out of it, one line per change with the numbers it was made of. a need that
    // flips with the same bag every few seconds is only visible as a pair of these (the smoker screen satisfying the need that
    // opened it was, see FoodPlan.leftover). a second food need replacing the first (minimum, then target) is not a change
    private void watchFood(GamerContext ctx, List<KitNeed> list, KitNeed running) {
        KitNeed food = null;
        for (KitNeed n : list) {
            if (KitNeed.FOOD.equals(n.catalogueName())) {
                food = n;
                break;
            }
        }
        boolean on = food != null;
        if (on == foodOn) {
            return;
        }
        foodOn = on;
        FoodPlan plan = ctx.food();
        Debug.logInternal(plan.line(on, on ? food.count() : plan.overworldMinimum(), running == null ? null : running.catalogueName()));
    }

    // the running need dropping out of the plan and coming back, one line per change with what the planner counted. a need that
    // trades the head every few seconds is a planner input wobbling, and these lines say which one
    private void watchGone(GamerFacts f, List<KitNeed> list) {
        if (key == null) {
            return;
        }
        KitNeed same = HeadLatch.sameJob(list, key);
        if (same == null && missingSince == HeadLatch.NEVER) {
            // done (we hold it, or a special need) is just done, no clock and no line
            if (HeadLatch.keepWhileGone(key, f.gameTime(), f.gameTime(), f)) {
                missingSince = f.gameTime();
                Debug.logInternal("kit: " + key.catalogueName() + " x" + key.count() + " dropped out of the plan but we do not hold it ("
                        + goneWhy(f, key) + "), keeping at it for up to " + HeadLatch.DWELL_TICKS + " ticks");
            }
        } else if (same != null && missingSince != HeadLatch.NEVER) {
            Debug.logInternal("kit: " + key.catalogueName() + " back in the plan as x" + same.count() + " after "
                    + (f.gameTime() - missingSince) + " ticks (" + goneWhy(f, key) + ")");
            missingSince = HeadLatch.NEVER;
        }
    }

    // the numbers the planner had for this need. logs get the table too, a table that counts or not swings the log need
    static String goneWhy(GamerFacts f, KitNeed need) {
        String line = "hold " + KitPlanner.have(f, need.catalogueName());
        if (need.catalogueName().equals("log")) {
            line += ", planks " + f.count(ItemHelper.PLANKS) + ", sticks " + f.count(Items.STICK)
                    + ", table held " + KitPlanner.tableHeld(f) + ", table planks " + KitPlanner.tablePlanks(f);
        }
        return line;
    }

    // a need only counts as progress when its own number went up since we last looked at THAT need. the head changing says
    // nothing (two needs trading the head every few seconds never get anywhere), and a finished need is already covered
    // by the engine seeing the inventory change
    private void watchProgress(GamerContext ctx, KitNeed need) {
        int now = KitPlanner.progressOf(ctx.facts(), need, ctx.food());
        Integer before = lastProgress.put(need.catalogueName(), now);
        if (before != null && now > before) {
            ctx.progress(need.catalogueName());
        }
    }

    private static Task build(KitNeed need, List<Item> equip, int foodTarget) {
        return switch (need.catalogueName()) {
            case KitNeed.FOOD -> new CollectFoodTask(foodTarget);
            case KitNeed.COOK_SMOKER -> new CookRawFoodTask(true);
            case KitNeed.COOK_FURNACE -> new CookRawFoodTask(false);
            case KitNeed.BUILD_BLOCKS -> new GetBuildingMaterialsTask(need.count());
            case KitNeed.EQUIP_ARMOR -> equip.isEmpty() ? null : new EquipArmorTask(equip.toArray(new Item[0]));
            default -> TaskCatalogue.taskExists(need.catalogueName()) ? TaskCatalogue.getItemTask(need.catalogueName(), need.count()) : null;
        };
    }

    // the catalogue hides which sub step it is on, so these are a best guess from what we hold
    public static String words(KitNeed need, GamerFacts f) {
        return switch (need.catalogueName()) {
            case KitNeed.FOOD -> "Getting food";
            case KitNeed.BUILD_BLOCKS -> "Collecting building blocks";
            case KitNeed.EQUIP_ARMOR -> "Putting on armor";
            case KitNeed.COOK_SMOKER, KitNeed.COOK_FURNACE -> "Cooking the raw meat";
            // with a batch already in a furnace the iron need is us digging for the next one, not smelting
            case "iron_ingot" -> f.pendingOutput(Items.IRON_INGOT) > 0 ? "Mining more iron"
                    : f.has(Items.RAW_IRON) ? "Smelting iron" : "Looking for iron";
            case "wool" -> f.has(Items.SHEARS) ? "Shearing sheep" : "Collecting wool";
            case "stone_pickaxe", "stone_axe", "stone_sword" -> KitPlanner.have(f, "stone_pickaxe") > 0 || f.has(Items.WOODEN_PICKAXE) ? "Mining stone" : "Chopping wood";
            case "furnace" -> "Making a furnace";
            case "flint" -> "Looking for flint";
            case "planks" -> "Making planks";
            case "log" -> "Chopping logs";
            case "cobblestone" -> "Mining stone";
            case "water_bucket" -> "Filling a bucket with water";
            default -> "Making " + need.catalogueName().replace('_', ' ');
        };
    }
}
