package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Dimension;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class GetSmithingTemplateTask extends ResourceTask {

    // plain blackstone is half of basalt deltas, so it says nothing. these two only show up in bastions (same pick as NetherPhase)
    private static final Block[] BASTION = {Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE};
    // a bastion is big but not that big, a chest further than this from any of its bricks is someone's base
    private static final double BASTION_CHEST_RANGE = 40;
    // one boot is enough to keep piglins calm when we open their chest, and it costs the least armor to swap in
    private static final Item[] GOLD_PIECES = {Items.GOLDEN_BOOTS, Items.GOLDEN_HELMET, Items.GOLDEN_LEGGINGS, Items.GOLDEN_CHESTPLATE};
    private static final Item TEMPLATE = Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE;

    private final Task _searcher = new SearchChunkForBlockTask(BASTION);
    private final int _count;
    // chests we already opened. hoglin stable bastions often have no template at all, and walking back to an empty
    // chest forever is not a plan
    private final Set<BlockPos> _visited = new HashSet<>();
    // a chest we can't reach would hold the loot task hostage, so each one gets a couple minutes
    private final TimerGame _chestTimeout = new TimerGame(120);
    private LootContainerTask _lootTask;
    private Task _dupeTask;
    private int _dupeTarget = -1;

    public GetSmithingTemplateTask(int count) {
        super(TEMPLATE, count);
        _count = count;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.CHEST);
        mod.getBlockTracker().trackBlock(BASTION);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        Task dupe = duplicateTask(mod);
        if (dupe != null) {
            setDebugState("Copying a template with diamonds");
            return dupe;
        }
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        if (_lootTask != null) {
            if (_lootTask.isFinished(mod) || !mod.getBlockTracker().blockIsValid(_lootTask.chest, Blocks.CHEST) || _chestTimeout.elapsed()) {
                _visited.add(_lootTask.chest);
                _lootTask = null;
            } else {
                Task gold = goldTask(mod);
                if (gold != null) {
                    setDebugState("Putting on gold so the piglins stay chill");
                    return gold;
                }
                setDebugState("Looting a bastion chest");
                // same instance every tick, LootContainerTask compares its target list by identity and would restart otherwise
                return _lootTask;
            }
        }
        Optional<BlockPos> chest = nextBastionChest(mod);
        if (chest.isPresent()) {
            _lootTask = new LootContainerTask(chest.get(), List.of(TEMPLATE));
            _chestTimeout.reset();
            return null;
        }
        // nothing left to open around here. the searcher marks the bastion it already walked as explored and wanders
        // off until a fresh one loads in
        setDebugState("Searching for a bastion");
        return _searcher;
    }

    // vanilla 26.3: 7 diamonds + 1 netherrack + 1 template -> 2 templates. only done with everything already in the bag.
    // the upgrade collects its diamond piece before it asks for the template (CataloguedResourceTask puts the craft first),
    // so loose diamonds here are spares. we never go mining for them, a bastion trip is the fallback
    private Task duplicateTask(AltoClef mod) {
        int have = mod.getItemStorage().getItemCount(TEMPLATE);
        if (have < 1 || have >= _count || mod.getItemStorage().getItemCount(Items.DIAMOND) < 7) {
            return null;
        }
        if (mod.getItemStorage().getItemCount(Items.NETHERRACK) < 1) {
            // netherrack is a nether trip for an overworld bot, still way cheaper than a bastion
            return TaskCatalogue.getItemTask("netherrack", 1);
        }
        // one copy at a time, each one eats a template and seven diamonds, and we look again once it lands
        if (_dupeTask == null || _dupeTarget != have + 1) {
            _dupeTarget = have + 1;
            _dupeTask = new CraftInTableTask(new RecipeTarget(TEMPLATE, _dupeTarget, dupeRecipe()), false, true);
        } else if (_dupeTask.isFinished(mod)) {
            // the craft gave up without the copy landing. handing it back would just sit there, go loot instead
            return null;
        }
        return _dupeTask;
    }

    private static CraftingRecipe dupeRecipe() {
        ItemTarget d = new ItemTarget(Items.DIAMOND, 1);
        ItemTarget s = new ItemTarget(TEMPLATE, 1);
        ItemTarget c = new ItemTarget(Items.NETHERRACK, 1);
        return CraftingRecipe.newShapedRecipe("netherite_upgrade_smithing_template", new ItemTarget[]{d, s, d, d, c, d, d, d, d}, 2);
    }

    // only gold we already own. EquipArmorTask goes shopping for anything missing, so it only gets pieces in the bag
    private static Task goldTask(AltoClef mod) {
        if (StorageHelper.isArmorEquipped(mod, GOLD_PIECES)) {
            return null;
        }
        for (Item piece : GOLD_PIECES) {
            if (mod.getItemStorage().getItemCountInventoryOnly(piece) > 0) {
                return new EquipArmorTask(piece);
            }
        }
        return null;
    }

    private Optional<BlockPos> nextBastionChest(AltoClef mod) {
        BlockPos me = mod.getPlayer().blockPosition();
        return mod.getBlockTracker().getKnownLocations(Blocks.CHEST).stream()
                .filter(pos -> !_visited.contains(pos) && mod.getBlockTracker().blockIsValid(pos, Blocks.CHEST))
                .filter(pos -> mod.getBlockTracker().getNearestWithinRange(pos, BASTION_CHEST_RANGE, BASTION).isPresent())
                .min(Comparator.comparingDouble(pos -> pos.distSqr(me)));
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.CHEST);
        mod.getBlockTracker().stopTracking(BASTION);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof GetSmithingTemplateTask;
    }

    @Override
    protected String toHudString() {
        return "Looking for a smithing template in a bastion";
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " smithing templates";
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
