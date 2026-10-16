package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.schematic.CountStep;
import adris.altoclef.tasks.construction.schematic.GatherGuard;
import adris.altoclef.tasks.construction.schematic.GatherPlan;
import adris.altoclef.tasks.construction.schematic.SchematicDiff;
import adris.altoclef.tasks.construction.schematic.SkipItems;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.utils.Dimension;
import baritone.api.utils.Helper;
import baritone.process.BuilderProcess;
import baritone.utils.schematic.library.InventoryCounts;
import baritone.utils.schematic.library.MaterialLine;
import baritone.utils.schematic.library.MaterialList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

// "build here" in survival: count what the rest of the build takes, go get it, let the builder place it, and when the
// builder runs dry go get the next lot. the builder itself is the stock one, this only feeds it
public class SchematicBuildTask extends Task {

    // negative so they never meet a builder serial (those count up from 1) or the builder's "nothing" (0). the tab
    // tells our build from a chat #build by this number, see McSchematics.building
    private static final AtomicLong SERIALS = new AtomicLong();
    // cells the diff looks at per tick. a 64^3 build is 8 ticks, nobody waits on that
    private static final int DIFF_BUDGET = 32_768;
    // chat lines list this many items, then "and 4 more"
    private static final int SAY_AT_MOST = 6;
    // how often the builder's progress gets counted while it works: a watch diff starts this many ticks after the last
    // one finished. GatherGuard only believes the diff, and an hour long build shouldn't look stuck for want of a count
    private static final int WATCH_EVERY = 30 * 20;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private enum Phase {
        COUNT,
        GATHER,
        BUILD
    }

    private final long serial = -SERIALS.incrementAndGet();
    private final String name;
    private final IStaticSchematic schematic;
    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    private final ISchematic placed;
    private final GatherGuard guard = new GatherGuard();
    // items nothing in the catalogue makes and the bag has none of. the builder gets the file with them cut out
    // (SkipItems). one we hold some of stays in, the builder places what there is and the pause says what's short
    private final Set<String> skipped = new LinkedHashSet<>();

    // volatile: the footprint rules below are read by the path search off the game thread
    private volatile Phase phase = Phase.COUNT;
    // set on the game thread every tick, so the footprint rules don't ask the level from the path thread
    private volatile boolean away;
    // cells already right as of the last count, BlockPos.asLong. swapped whole, never changed in place
    private volatile LongOpenHashSet built = new LongOpenHashSet();
    // the dimension the build is in, set on the first start. a gather may take us to the nether, the count and the
    // builder only ever run back here
    private Dimension home;
    private Task goHome;
    private SchematicDiff diff;
    private SchematicDiff watch;
    private int watchWait;
    // the builder's missing items that sent us back to counting, null when it wasn't a pause
    private Map<String, Integer> missing;
    // the builder let go without a pause (done, or something took it): the count decides which
    private boolean builderEnded;
    // what the rest of the build takes by item, as of the last count
    private Map<String, Integer> lastLeft = Map.of();
    private boolean toldUngettable;
    private int batches;
    private Task gather;
    // item -> how many the bag should hold once the batch is in
    private Map<String, Integer> batchTargets = Map.of();
    // the serial the builder gave our build, 0 while it has none of ours
    private long builderSerial;
    // our level of the behaviour stack, null while we hold none (always while the builder runs)
    private BotBehaviour.State level;
    private boolean done;

    public SchematicBuildTask(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror) {
        this.name = name;
        this.schematic = schematic;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
        this.placed = BuilderProcess.placed(schematic, rotation, mirror);
    }

    public long serial() {
        return serial;
    }

    // false once it has decided it's done, a tick before the chain gets round to dropping it
    public boolean running() {
        return !done;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (done) {
            return;
        }
        if (home == null) {
            home = WorldHelper.getCurrentDimension();
            warnAboutSettings();
        }
        // back from an interrupt (a fight, a meal). onStop let go of the builder, so it gets the build again, fresh
        if (phase == Phase.BUILD) {
            if (atHome()) {
                startBuilder(mod);
                return;
            }
            phase = Phase.COUNT;
        }
        protect(mod);
    }

    // these make the builder call cells done (or build them from something else) that the count still prices, so the
    // count asks for more than the builder will use. it still works, it just fetches too much
    private void warnAboutSettings() {
        Settings s = Baritone.settings();
        if (s.buildOnlySelection.value || !s.okIfAir.value.isEmpty() || s.okIfWater.value || s.buildIgnoreExisting.value
            || !s.buildSubstitutes.value.isEmpty() || !s.buildValidSubstitutes.value.isEmpty()) {
            say("Some builder settings may skip cells; gathering may overshoot");
        }
    }

    private boolean atHome() {
        return WorldHelper.getCurrentDimension() == home;
    }

    private Task goHome() {
        if (goHome == null) {
            goHome = new DefaultGoToDimensionTask(home);
        }
        setDebugState("Going back to the build", "Going back to " + name);
        return goHome;
    }

    // our own level of the behaviour stack while we count and gather, never while the builder runs: the builder has
    // to break and place inside the footprint and pillar with the build's own blocks, and these rules reach its path
    // costs too. the gather's pickaxe stays off the cells that are already right, its scaffolding out of the cells
    // that should stay empty (not the whole box: "build here" starts with you standing in it, and a box you can't
    // dig or pillar out of is a trap), and what the rest of the build needs is a reserve floor: the paths may use what
    // is held above it, the junk dump and the throwaway picks leave the rest alone. whatever the builder burns on
    // scaffolding the next count simply asks for again
    private void protect(AltoClef mod) {
        if (level != null) {
            return;
        }
        level = mod.getBehaviour().push();
        // only at home: in the nether the same coordinates are somebody else's blocks
        mod.getBehaviour().avoidBlockBreaking(pos -> phase != Phase.BUILD && !away && built.contains(pos.asLong()));
        mod.getBehaviour().avoidBlockPlacing(pos -> phase != Phase.BUILD && !away && wantsAir(pos));
        mod.getBehaviour().setReserveFloor(level, floor());
    }

    // pop only our own level. the child (gather, the trip home) is stopped first so it pops its own, otherwise our pop
    // would take the child's level and the child's late pop would take whatever sits under ours
    private void unprotect(AltoClef mod) {
        if (level == null) {
            return;
        }
        dropChild(mod);
        if (mod.getBehaviour().isTop(level)) {
            mod.getBehaviour().pop();
        } else {
            // can't pop it without taking somebody else's, so at least it stops holding our items back
            mod.getBehaviour().setReserveFloor(level, Map.of());
            Debug.logInternal("SchematicBuildTask: our behaviour level isn't on top, left it alone");
        }
        level = null;
    }

    private Map<Item, Integer> floor() {
        Map<Item, Integer> out = new LinkedHashMap<>();
        lastLeft.forEach((id, n) -> {
            Item item = item(id);
            if (item != Items.AIR && !skipped.contains(id)) {
                out.merge(item, n, Integer::sum);
            }
        });
        return out;
    }

    // a cell of the file that is meant to stay empty. pure schematic lookups, safe from the path thread
    private boolean wantsAir(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= placed.widthX() || y >= placed.heightY() || z >= placed.lengthZ()
            || !placed.inSchematic(x, y, z, AIR)) {
            return false;
        }
        BlockState want = placed.desiredState(x, y, z, AIR, List.of());
        return want != null && want.isAir();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done) {
            return null;
        }
        away = !atHome();
        if (phase == Phase.GATHER) {
            guard.gathering(towardBatch(have(mod)));
        }
        if (guard.tick()) {
            finish(mod, "No progress on " + name + " for " + GatherGuard.MAX_IDLE_TICKS / 1200 + " minutes, stopped");
            return null;
        }
        if (phase == Phase.BUILD && away) {
            // a respawn somewhere else. the builder would build the same coordinates in the wrong world
            stopBuilder(mod);
            phase = Phase.COUNT;
        }
        if (phase != Phase.BUILD) {
            protect(mod);
        }
        return switch (phase) {
            case COUNT -> away ? goHome() : count(mod);
            case GATHER -> gather(mod);
            case BUILD -> build(mod);
        };
    }

    private long towardBatch(Map<String, Integer> have) {
        long sum = 0;
        for (Map.Entry<String, Integer> e : batchTargets.entrySet()) {
            sum += Math.min(have.getOrDefault(e.getKey(), 0), e.getValue());
        }
        return sum;
    }

    // what the count left that we can't get, priced, for the last line
    private String skippedLine() {
        Map<String, Integer> out = new LinkedHashMap<>();
        lastLeft.forEach((id, n) -> {
            if (skipped.contains(id)) {
                out.put(id, n);
            }
        });
        return out.isEmpty() ? null : "Built " + name + " except " + list(out);
    }

    private SchematicDiff newDiff(boolean watching) {
        Predicate<Block> skip = block -> Baritone.settings().buildSkipBlocks.value.contains(block);
        return watching ? SchematicDiff.watching(placed, origin.getX(), origin.getY(), origin.getZ(), skip)
            : new SchematicDiff(placed, origin.getX(), origin.getY(), origin.getZ(), skip);
    }

    private Task count(AltoClef mod) {
        setDebugState("Counting what is left", "Counting what " + name + " still needs");
        ClientLevel world = mod.getWorld();
        if (world == null) {
            return null;
        }
        if (diff == null) {
            diff = newDiff(false);
        }
        if (!diff.step(world(world), DIFF_BUDGET)) {
            return null;
        }
        Map<String, Integer> left = byItem(diff.result().items());
        long correct = diff.correct();
        long wrong = diff.wrong();
        built = diff.built();
        diff = null;
        boolean ended = builderEnded;
        builderEnded = false;
        Map<String, Integer> have = have(mod);
        GatherPlan.Plan plan = GatherPlan.plan(GatherPlan.need(left, missing), have, SchematicBuildTask::gettable,
            slots(mod), SchematicBuildTask::stackSize);
        plan.ungettable().keySet().forEach(id -> {
            if (have.getOrDefault(id, 0) == 0) {
                skipped.add(id);
            }
        });
        lastLeft = left;
        // the whole rest of the build, not just this batch: the stone for round three burns as well as round one's
        mod.getBehaviour().setReserveFloor(level, floor());
        if (!toldUngettable && !plan.ungettable().isEmpty()) {
            toldUngettable = true;
            say("Can't get " + list(plan.ungettable()) + " for " + name + ", building around them");
        }
        Map<String, Integer> rest = new LinkedHashMap<>(left);
        rest.keySet().removeAll(skipped);
        // every count is one round for the guard, whatever sent us here (a pause, the builder letting go, the start)
        boolean stuck = guard.round(correct);
        switch (CountStep.next(ended, rest.isEmpty(), stuck, plan.bagFull(), plan.nothingToGet(), missing != null)) {
            case DONE -> finish(mod, skippedLine());
            case DONE_BUT_DIFFERS -> finish(mod, "The builder says " + name + " is done; " + wrong
                + " cells differ (likely builder settings)");
            case STUCK -> finish(mod, "No progress on " + name + " after " + GatherGuard.MAX_IDLE_ROUNDS
                + " rounds, stopped. Still missing " + list(rest));
            case BAG_FULL -> finish(mod, "Inventory is full, make some room and build " + name + " again");
            // the builder said it's out of these, but the bag has them. that's not a gathering problem
            case CANT_PLACE -> finish(mod, "The builder can't place " + list(missing) + " even with them in the inventory, stopped " + name);
            case BUILD -> startBuilder(mod);
            case GATHER -> {
                return startGather(plan, have);
            }
        }
        return null;
    }

    private Task startGather(GatherPlan.Plan plan, Map<String, Integer> have) {
        batches++;
        List<ItemTarget> targets = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        plan.batch().forEach((id, more) -> {
            int total = have.getOrDefault(id, 0) + more;
            totals.put(id, total);
            targets.add(new ItemTarget(item(id), total));
        });
        batchTargets = totals;
        guard.newBatch();
        gather = TaskCatalogue.getSquashedItemTask(targets.toArray(new ItemTarget[0]));
        say("Gathering " + list(plan.batch()) + " for " + name + " (batch " + batches + ")");
        phase = Phase.GATHER;
        return gather;
    }

    private Task gather(AltoClef mod) {
        if (!gather.isFinished(mod)) {
            setDebugState("Gathering batch " + batches, "Gathering for " + name);
            return gather;
        }
        if (!atHome()) {
            return goHome();
        }
        gather = null;
        startBuilder(mod);
        return null;
    }

    private Task build(AltoClef mod) {
        IBuilderProcess builder = builder(mod);
        long now = builder.buildSerial();
        if (now == 0) {
            // the builder let go without pausing. usually it's done ("Done building"), but it can also have been
            // knocked off, so a fresh count says which
            builderSerial = 0;
            watch = null;
            builderEnded = true;
            missing = null;
            phase = Phase.COUNT;
            return null;
        }
        if (now != builderSerial) {
            // a #build from chat took the builder. it's theirs now, hands off
            builderSerial = 0;
            finish(mod, "Another build took over, stopped " + name);
            return null;
        }
        if (builder.isPaused()) {
            onPause(mod, builder);
            return null;
        }
        setDebugState("Building", "Building " + name);
        watch(mod);
        return null;
    }

    // a quiet count while the builder works, only so the guard sees the build getting further
    private void watch(AltoClef mod) {
        ClientLevel world = mod.getWorld();
        if (world == null) {
            return;
        }
        // the wait only starts over when a watch gets to the end. a builder restarted every few seconds drops the watch
        // half way, and a wait that started over each time would never let one finish
        if (watch == null) {
            if (++watchWait < WATCH_EVERY) {
                return;
            }
            watch = newDiff(true);
        }
        if (watch.step(world(world), DIFF_BUDGET)) {
            guard.seen(watch.correct());
            watchWait = 0;
            watch = null;
        }
    }

    private void onPause(AltoClef mod, IBuilderProcess builder) {
        Map<String, Integer> lacking = SchematicDiff.priced(builder.missingAtPause());
        if (lacking.isEmpty()) {
            finish(mod, "The builder stopped on " + name + " and it isn't for materials, it can't reach or place what's left");
            return;
        }
        if (lacking.keySet().stream().noneMatch(SchematicBuildTask::gettable)) {
            // SkipItems cuts these out of what the builder gets, so this is one we hold a few of and ran out
            finish(mod, "Built what I can get for " + name + ", still missing " + list(lacking));
            return;
        }
        // a paused builder still holds on to its process slot, and the gather's own pathing would win it off the
        // builder and wipe it anyway. so it goes now on purpose and comes back fresh once the next batch is in. the
        // count that follows is the guard's round
        stopBuilder(mod);
        missing = lacking;
        phase = Phase.COUNT;
    }

    private void startBuilder(AltoClef mod) {
        // the builder gets the whole bag, the reserve floor and the footprint rules go first
        unprotect(mod);
        IBuilderProcess builder = builder(mod);
        builder.build(name, SkipItems.of(schematic, skipped), origin, rotation, mirror);
        builderSerial = builder.buildSerial();
        watch = null;
        // whatever the pause or the gather was about, it's in the past now
        missing = null;
        phase = Phase.BUILD;
    }

    // only ever our own build, a chat #build that took over is left alone
    private void stopBuilder(AltoClef mod) {
        watch = null;
        if (builderSerial != 0) {
            IBuilderProcess builder = builder(mod);
            if (builder.buildSerial() == builderSerial) {
                builder.onLostControl();
            }
            builderSerial = 0;
        }
    }

    // the chain drops a finished task without calling onStop, so everything onStop would undo happens here
    private void finish(AltoClef mod, String why) {
        if (why != null) {
            say(why);
        }
        stopBuilder(mod);
        unprotect(mod);
        done = true;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // stop, cancel, another task or an interrupt: the builder goes too. a builder left running under a fight
        // would fight the fight for the wheel. onStart puts it back after an interrupt
        stopBuilder(mod);
        unprotect(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SchematicBuildTask task && task.serial == serial;
    }

    @Override
    protected String toHudString() {
        return switch (phase) {
            case COUNT -> "Counting what " + name + " needs";
            case GATHER -> "Gathering for " + name;
            case BUILD -> "Building " + name;
        };
    }

    @Override
    protected String toDebugString() {
        return "Schematic build " + name + " at " + origin.toShortString();
    }

    // ----- the game side

    private static IBuilderProcess builder(AltoClef mod) {
        return mod.getClientBaritone().getBuilderProcess();
    }

    private static SchematicDiff.World world(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return new SchematicDiff.World() {
            @Override
            public boolean loaded(int chunkX, int chunkZ) {
                // not level.hasChunk, ClientLevel's is a flat 'return true'
                return level.getChunkSource().hasChunk(chunkX, chunkZ);
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return level.getBlockState(pos.set(x, y, z));
            }
        };
    }

    private static Map<String, Integer> have(AltoClef mod) {
        return InventoryCounts.of(mod.getPlayer().getInventory());
    }

    private static List<GatherPlan.Slot> slots(AltoClef mod) {
        List<GatherPlan.Slot> out = new ArrayList<>(36);
        List<ItemStack> main = mod.getPlayer().getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(36, main.size()); i++) {
            ItemStack stack = main.get(i);
            out.add(stack.isEmpty() ? GatherPlan.Slot.empty()
                : new GatherPlan.Slot(MaterialList.itemId(stack.getItem()), stack.getCount(), stack.getMaxStackSize()));
        }
        return out;
    }

    private static Map<String, Integer> byItem(List<MaterialLine> lines) {
        Map<String, Integer> out = new LinkedHashMap<>();
        lines.forEach(line -> out.merge(line.item(), line.count(), Integer::sum));
        return out;
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(key);
    }

    // taskExists too, not just isObtainable: an item only reachable through a group entry (no single item task)
    // would hand CataloguedResourceTask a null
    static boolean gettable(String id) {
        Item item = item(id);
        return item != Items.AIR && TaskCatalogue.isObtainable(item) && TaskCatalogue.taskExists(item);
    }

    private static int stackSize(String id) {
        Item item = item(id);
        return item == Items.AIR ? 64 : item.getDefaultInstance().getMaxStackSize();
    }

    private static String list(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (shown++ == SAY_AT_MOST) {
                parts.add((counts.size() - SAY_AT_MOST) + " more");
                break;
            }
            parts.add(HudText.count(e.getValue(), HudText.item(item(e.getKey()))));
        }
        return HudText.list(parts);
    }

    private static void say(String message) {
        Helper.HELPER.logDirect(message);
    }
}
