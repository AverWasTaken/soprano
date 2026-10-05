package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

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
    private List<Item> equipKey = List.of();
    private int foodKey;
    private Task task;
    private int lastProgress;
    private String hud;

    public KitRunner() {
        this(KitRunner::build);
    }

    public KitRunner(Builder builder) {
        this.builder = builder;
    }

    public void reset() {
        key = null;
        equipKey = List.of();
        foodKey = 0;
        task = null;
        lastProgress = 0;
        hud = null;
    }

    // CollectFoodTask counts anything edible (rotten flesh, spider eyes...) but our facts only count what we would eat,
    // so with a bag of junk it thinks it is done and wanders. ask it for the junk on top
    public static int foodTarget(KitNeed need, GamerFacts f) {
        return KitNeed.FOOD.equals(need.catalogueName()) ? need.count() + f.junkFoodUnits() : need.count();
    }

    // plain words for what the current need is, null when there is nothing to do
    public String hud() {
        return hud;
    }

    public Task run(GamerContext ctx, List<KitNeed> needs) {
        if (needs.isEmpty()) {
            hud = null;
            return null;
        }
        GamerFacts f = ctx.facts();
        KitNeed need = needs.get(0);
        List<Item> equip = KitNeed.EQUIP_ARMOR.equals(need.catalogueName()) ? KitPlanner.toEquip(f, ctx.cfg().overworld) : List.of();
        int food = foodTarget(need, f);
        watchProgress(ctx, need);
        if (!need.equals(key) || !equip.equals(equipKey) || food != foodKey) {
            key = need;
            equipKey = equip;
            foodKey = food;
            task = builder.build(need, equip, food);
            if (task == null) {
                ctx.fail("no way to get " + need.catalogueName());
            }
        }
        hud = words(need, f);
        return task;
    }

    private void watchProgress(GamerContext ctx, KitNeed need) {
        int now = KitPlanner.progressOf(ctx.facts(), need);
        if (key != null && (!need.equals(key) || now > lastProgress)) {
            ctx.progress(need.catalogueName());
        }
        lastProgress = now;
    }

    private static Task build(KitNeed need, List<Item> equip, int foodTarget) {
        return switch (need.catalogueName()) {
            case KitNeed.FOOD -> new CollectFoodTask(foodTarget);
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
            case "iron_ingot" -> f.has(Items.RAW_IRON) ? "Smelting iron" : "Looking for iron";
            case "wool" -> f.has(Items.SHEARS) ? "Shearing sheep" : "Collecting wool";
            case "stone_pickaxe", "stone_sword" -> KitPlanner.have(f, "stone_pickaxe") > 0 || f.has(Items.WOODEN_PICKAXE) ? "Mining stone" : "Chopping wood";
            case "furnace" -> "Making a furnace";
            case "water_bucket" -> "Filling a bucket with water";
            default -> "Making " + need.catalogueName().replace('_', ' ');
        };
    }
}
