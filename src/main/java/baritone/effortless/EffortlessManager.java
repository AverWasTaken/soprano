/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.effortless;

import baritone.Baritone;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBuilderProcess;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.Dimension;
import baritone.api.utils.EffortlessMaterials;
import baritone.api.utils.EffortlessStorageFull;
import baritone.api.utils.IPlayerContext;
import baritone.utils.IRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// the state of the build mode for one baritone: the shape picked, the clicks so far, and the jobs it starts. the
// command drives it for now, the radial and the clicks will later. it also draws the outline of what you're setting up
public final class EffortlessManager implements AbstractGameEventListener {

    private static final double GROW = 0.002;

    private final Baritone baritone;
    private ShapeSpec spec = ShapeSpec.of(ShapeKind.CUBOID);
    private final List<BlockPos> points = new ArrayList<>();
    // the last shape drawn, so the outline only rebuilds the mask when the clicks or the crosshair move
    private List<BlockPos> previewKey;
    private ShapeSpec previewSpec;
    private AreaMask preview;
    private EffortlessJob running;
    private JobSchematic runningSchematic;
    // the jobs waiting their turn, saved per world and dimension
    private JobQueue queue = new JobQueue();
    private java.nio.file.Path queueFile;
    private Object queueWorld;
    // the builder's serial for the entry it's on, 0 while it isn't on one of ours
    private long entrySerial;
    // the errands (AltoClef's, handed in by its bridge), whether one is out, and when the full output storage is worth
    // another look
    private EffortlessTrips trips;
    private boolean tripOut;
    private long tripStarted;
    // a deposit or a fetch that hasn't come back in this long is called off: a container task that can't make up its
    // mind must not hold the job forever. a gather can take as long as it takes
    private static final long TRIP_TIMEOUT_MS = 60_000;
    private boolean tripTimed;
    // we called the errand off ourselves (timeout). a stop that isn't ours is the player's #stop
    private boolean tripCalledOff;
    private long storageRetryAt;
    private static final int MAX_TRIPS = 5;
    // terrain the running entry's paths dug through outside its area, cell -> what was there, and whether the entry is
    // in its put-it-back build now
    private final Map<Long, BlockState> dug = new java.util.LinkedHashMap<>();
    private boolean restoring;
    // the shown entries as one schematic for the preview, rebuilt when what's shown changes
    private Object shownKey;
    private JobSchematic shownSchematic;
    // what the next job will be, for the preview: place or break and the options it was set up with. null mode is
    // "no idea yet", the outline still shows but no cells do
    private EffortlessJob.Mode draftMode;
    private EffortlessOptions draftOptions;
    // the preview job, rebuilt only when what it depends on changes
    private Object previewJobKey;
    private JobSchematic previewSchematic;

    public EffortlessManager(Baritone baritone) {
        this.baritone = baritone;
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public ShapeSpec spec() {
        return spec;
    }

    public void spec(ShapeSpec newSpec) {
        this.spec = newSpec;
    }

    public List<BlockPos> points() {
        return List.copyOf(points);
    }

    // index 0 based. setting point 2 with no point 1 is fine, the gaps get the crosshair until they're set
    public void point(int index, BlockPos pos) {
        while (points.size() <= index) {
            points.add(null);
        }
        points.set(index, pos.immutable());
    }

    public void clearPoints() {
        points.clear();
    }

    // the points the shape gets: the set ones in order, and when the shape wants more, the block under the crosshair
    // for the next one, so the outline follows your look. empty when there's nothing to go on
    public List<BlockPos> effectivePoints(boolean fillWithCrosshair) {
        List<BlockPos> out = new ArrayList<>();
        Optional<BlockPos> look = fillWithCrosshair ? crosshair() : Optional.empty();
        for (BlockPos p : points) {
            if (p != null) {
                out.add(p);
            } else if (look.isPresent()) {
                out.add(look.get());
            } else {
                return out;
            }
        }
        if (!out.isEmpty() && out.size() < spec.kind().clicks(Baritone.settings().effortlessThreeClickHeight.value) && look.isPresent()) {
            out.add(look.get());
        }
        return out;
    }

    public Optional<BlockPos> crosshair() {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx.player() == null) {
            return Optional.empty();
        }
        // in build mode the next corner is where a click would land: in front of the face for a place, in the air
        // when there's nothing in reach
        if (BuildMode.active()) {
            return BuildMode.crosshair();
        }
        return ctx.getSelectedBlock();
    }

    // the cells of the shape as set up right now, null when there aren't enough points
    public AreaMask currentMask(boolean fillWithCrosshair) {
        List<BlockPos> pts = effectivePoints(fillWithCrosshair);
        if (pts.isEmpty()) {
            return null;
        }
        if (pts.equals(previewKey) && spec.equals(previewSpec)) {
            return preview;
        }
        previewKey = pts;
        previewSpec = spec;
        try {
            preview = ShapeGeometry.build(spec, pts);
        } catch (ShapeGeometry.TooBig e) {
            preview = null;
        }
        return preview;
    }

    // ----- making jobs

    public static final class NotReady extends Exception {
        public NotReady(String message) {
            super(message);
        }
    }

    // the set points only, no crosshair: a job is what you clicked, not where you happen to be looking
    public List<BlockPos> jobPoints() throws NotReady {
        List<BlockPos> pts = new ArrayList<>();
        for (BlockPos p : points) {
            if (p == null) {
                break;
            }
            pts.add(p);
        }
        if (pts.isEmpty()) {
            throw new NotReady("set the first point first (#ef p1)");
        }
        if (pts.size() < spec.kind().clicks) {
            throw new NotReady("a " + spec.kind().title.toLowerCase() + " needs " + spec.kind().clicks + " points, you've set " + pts.size());
        }
        return pts;
    }

    public void checkSize(EffortlessJob job) throws NotReady {
        int max = Baritone.settings().effortlessMaxBlocks.value;
        if (job.mask.count() > max) {
            throw new NotReady("that's " + job.mask.count() + " blocks, more than effortlessMaxBlocks (" + max + ")");
        }
        if (job.mask.isEmpty()) {
            throw new NotReady("that shape has no blocks in it");
        }
    }

    // the block in the main hand (or offhand), as the state it places by default. empty when it isn't a block
    public Optional<BlockState> heldBlock(boolean offhand) {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx.player() == null) {
            return Optional.empty();
        }
        ItemStack stack = offhand ? ctx.player().getOffhandItem() : ctx.player().getMainHandItem();
        if (stack.getItem() instanceof BlockItem bi) {
            return Optional.of(bi.getBlock().defaultBlockState());
        }
        return Optional.empty();
    }

    // hands the job to the builder right away, through the queue so it shows there like the rest
    public void start(EffortlessJob job) {
        JobQueue.Entry e = queue.add(job, System.currentTimeMillis());
        e.materials(BuildMode.materials());
        // something of ours already going is put back in line, the new one goes first
        JobQueue.Entry was = queue.runningEntry();
        if (was != null) {
            was.state(JobQueue.State.QUEUED, null);
        }
        startEntry(e);
        save();
    }

    private void startEntry(JobQueue.Entry e) {
        if (restoring) {
            // back from the exact restore's gather: it's the terrain build that goes on, not the job again
            if (!buildRestore(e)) {
                finishEntry(e, null);
            }
            return;
        }
        JobSchematic schematic = e.schematic();
        running = e.jobs().get(e.jobs().size() - 1);
        runningSchematic = schematic;
        e.state(JobQueue.State.RUNNING, null);
        baritone.getBuilderProcess().buildInPlace(e.name(), schematic, schematic.origin(), Baritone.settings().effortlessJumpOff.value);
        // what the job leaves alone inside its area stays standing: a path that digs through the dirt under "break only
        // stone" is the builder breaking the one thing you told it not to. kept blocks price like finished ones
        baritone.getBuilderProcess().setKeep(keepUntouched(schematic));
        entrySerial = baritone.getBuilderProcess().buildSerial();
    }

    // ----- the queue

    public JobQueue queue() {
        return queue;
    }

    public void trips(EffortlessTrips t) {
        this.trips = t;
    }

    public boolean tripOut() {
        return tripOut;
    }

    public void addStorage(StorageArea area) {
        queue.storage().add(area);
        save();
    }

    // after anything changes the queue: written straight away, it's a small file
    public void save() {
        JobStore.save(queueFile, queue);
        shownKey = null;
    }

    // starts on the first waiting entry, and keeps going down the list until it's empty or stopped
    public String startQueue() {
        if (queue.next() == null && queue.runningEntry() == null) {
            return "Nothing waiting in the queue";
        }
        queue.running(true);
        return null;
    }

    // stops after taking the current entry off the builder. it goes back to waiting, first in line
    public void stopQueue() {
        queue.running(false);
        JobQueue.Entry e = queue.runningEntry();
        if (e != null) {
            e.state(JobQueue.State.QUEUED, null);
            if (baritone.getBuilderProcess().buildSerial() == entrySerial && entrySerial != 0) {
                baritone.getPathingBehavior().cancelEverything();
            }
            entrySerial = 0;
        }
        save();
    }

    private void tickQueue() {
        if (tripOut) {
            // an errand has the bot, the entry waits for it to come back
            if (tripTimed && System.currentTimeMillis() - tripStarted > TRIP_TIMEOUT_MS && trips != null) {
                tripTimed = false;
                tripCalledOff = true;
                trips.cancel();
            }
            return;
        }
        JobQueue.Entry e = queue.runningEntry();
        if (e != null) {
            var builder = baritone.getBuilderProcess();
            if (builder.finished(entrySerial)) {
                entrySerial = 0;
                if (!restoring && startRestore(e)) {
                    return;
                }
                finishEntry(e, null);
            } else if (builder.buildSerial() != entrySerial) {
                // knocked off: #stop, another build, the builder letting go. back in line, the queue waits for a start
                e.state(JobQueue.State.QUEUED, null);
                entrySerial = 0;
                if (queue.running()) {
                    queue.running(false);
                    BuildMode.say("Effortless queue paused");
                }
                save();
                return;
            } else if (builder.isPaused() && restoring) {
                // the job itself is done, only some of the terrain couldn't go back
                baritone.getPathingBehavior().cancelEverything();
                entrySerial = 0;
                finishEntry(e, "couldn't put all the terrain back");
            } else if (builder.isPaused()) {
                Map<BlockState, Integer> missing = builder.missingAtPause();
                if (!missing.isEmpty() && fetchOrGather(e, bill(missing))) {
                    return;
                }
                // "unable to do it", or out of blocks with nowhere to get more: this one's stuck, the rest may not be
                e.state(JobQueue.State.FAILED, missing.isEmpty() ? "the builder couldn't finish it" : "ran out of " + names(missing.keySet()));
                entrySerial = 0;
                baritone.getPathingBehavior().cancelEverything();
                save();
            } else {
                if (e.breaks() && depositDue()) {
                    deposit(e);
                }
                return;
            }
        }
        if (!queue.running()) {
            return;
        }
        JobQueue.Entry next = queue.next();
        if (next == null) {
            queue.running(false);
            BuildMode.say("Effortless queue done");
            save();
            return;
        }
        int[] at = queue.progress(next);
        BuildMode.say("Effortless " + at[0] + "/" + at[1] + ": " + next.name());
        next.trips(0);
        // a storage or gather entry gets its bill in before the builder starts on it
        if (next.places() && next.materials() != EffortlessMaterials.INVENTORY && !creative()) {
            Map<Item, Integer> bill = billFor(next);
            if (!bill.isEmpty()) {
                next.state(JobQueue.State.RUNNING, null);
                if (fetchOrGather(next, bill)) {
                    save();
                    return;
                }
            }
        }
        startEntry(next);
        save();
    }

    private void finishEntry(JobQueue.Entry e, String note) {
        e.state(JobQueue.State.DONE, note);
        restoring = false;
        dug.clear();
        save();
    }

    // every block one of our own breaks took out. while an entry is building (not on an errand, not putting terrain
    // back) one outside its area is terrain a path dug through, and it's noted with what it was
    public void broke(BlockPos pos, BlockState before, String who) {
        if (before == null || before.isAir() || tripOut || restoring || !Baritone.settings().effortlessRestoreTerrain.value) {
            return;
        }
        JobQueue.Entry e = queue.runningEntry();
        if (e == null || runningSchematic == null || runningSchematic.owner(pos.getX(), pos.getY(), pos.getZ()) != null) {
            return;
        }
        // a liquid isn't something to put back with a block, and the first state is the one that was there
        if (before.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return;
        }
        dug.putIfAbsent(pos.asLong(), before);
    }

    // after the job: put back what its paths dug out, with the block it was if we have it, else with what it drops if we
    // have that (stone comes back as cobblestone), else the hole stays. effortlessRestoreExact has AltoClef make the
    // originals first. false when there's nothing to do
    private boolean startRestore(JobQueue.Entry e) {
        if (!Baritone.settings().effortlessRestoreTerrain.value || dug.isEmpty()) {
            return false;
        }
        if (Baritone.settings().effortlessRestoreExact.value && !creative() && trips != null && trips.available() && e.trips() < MAX_TRIPS) {
            Map<Item, Integer> want = new java.util.LinkedHashMap<>();
            dug.values().forEach(st -> {
                Item item = st.getBlock().asItem();
                if (item != net.minecraft.world.item.Items.AIR) {
                    want.merge(item, 1, Integer::sum);
                }
            });
            Map<Item, Integer> bill = lessCarried(want);
            if (!bill.isEmpty()) {
                e.trips(e.trips() + 1);
                tripOut = true;
                restoring = true;
                BuildMode.say("Making " + total(bill) + " blocks to put the terrain back exactly");
                trips.gather(bill, why -> {
                    tripOut = false;
                    if (playerStopped(why, e)) {
                        restoring = false;
                        return;
                    }
                    if (why != null) {
                        BuildMode.say("Gathering: " + why);
                    }
                    if (!buildRestore(e)) {
                        finishEntry(e, null);
                    }
                });
                return true;
            }
        }
        return buildRestore(e);
    }

    private boolean buildRestore(JobQueue.Entry e) {
        Map<BlockPos, BlockState> plan = new java.util.LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<Long, BlockState> d : dug.entrySet()) {
            BlockState with = restoreWith(d.getValue());
            if (with == null) {
                skipped++;
            } else {
                plan.put(BlockPos.of(d.getKey()), with);
            }
        }
        if (plan.isEmpty()) {
            if (skipped > 0) {
                BuildMode.say("Left " + skipped + " dug blocks open, nothing to fill them with");
            }
            return false;
        }
        restoring = true;
        e.state(JobQueue.State.RUNNING, null);
        RestoreSchematic rs = new RestoreSchematic(plan);
        BuildMode.say("Putting back " + rs.size() + " blocks of terrain" + (skipped > 0 ? " (" + skipped + " left open)" : ""));
        baritone.getBuilderProcess().buildInPlace("Restoring terrain", rs, rs.origin(), Baritone.settings().effortlessJumpOff.value);
        entrySerial = baritone.getBuilderProcess().buildSerial();
        save();
        return true;
    }

    // the block a dug cell gets back: the original if we carry it (or anything in creative), else the first thing it
    // drops that's a block we carry, else null
    private BlockState restoreWith(BlockState original) {
        if (creative() || carried(original.getBlock().asItem())) {
            return original;
        }
        for (Item drop : BlockOptionalMeta.plainDrops(original.getBlock())) {
            if (drop instanceof BlockItem bi && carried(drop)) {
                return bi.getBlock().defaultBlockState();
            }
        }
        return null;
    }

    private boolean carried(Item item) {
        var p = baritone.getPlayerContext().player();
        if (p == null || item == net.minecraft.world.item.Items.AIR) {
            return false;
        }
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private boolean creative() {
        var p = baritone.getPlayerContext().player();
        return p != null && p.hasInfiniteMaterials();
    }

    // sends the bot for the bill the entry's source says: the input storage or a gather. false when it can't (no
    // AltoClef, no input storage, inventory source, too many trips already), the caller decides what that means
    private boolean fetchOrGather(JobQueue.Entry e, Map<Item, Integer> bill) {
        if (bill.isEmpty() || e.materials() == EffortlessMaterials.INVENTORY || trips == null || !trips.available()) {
            return false;
        }
        if (e.trips() >= MAX_TRIPS) {
            e.state(JobQueue.State.FAILED, "still short after " + MAX_TRIPS + " trips");
            return false;
        }
        StorageArea input = queue.input();
        if (e.materials() == EffortlessMaterials.STORAGE && input == null) {
            BuildMode.say("No input storage marked (empty hand, right click two corners), building with what you carry");
            return false;
        }
        e.trips(e.trips() + 1);
        e.state(JobQueue.State.RUNNING, null);
        tripOut = true;
        tripStarted = System.currentTimeMillis();
        tripTimed = e.materials() == EffortlessMaterials.STORAGE;
        // the builder lets go for the errand, it starts fresh on the entry when the bot is back
        stopBuilderFor(e);
        java.util.function.Consumer<String> back = why -> {
            tripOut = false;
            if (playerStopped(why, e)) {
                return;
            }
            if (why != null) {
                BuildMode.say(e.materials() == EffortlessMaterials.STORAGE ? "Storage: " + why : "Gathering: " + why);
            }
            if (e.state() == JobQueue.State.RUNNING && queue.entries().contains(e)) {
                startEntry(e);
                save();
            }
        };
        if (e.materials() == EffortlessMaterials.STORAGE) {
            BuildMode.say("Fetching " + total(bill) + " blocks from the storage");
            trips.fetch(input.range(dimension()), bill, back);
        } else {
            BuildMode.say("Gathering " + total(bill) + " blocks");
            trips.gather(bill, back);
        }
        return true;
    }

    // the bag is nearly full, there's an output storage, and it isn't known to be full right now
    private boolean depositDue() {
        if (trips == null || !Baritone.settings().effortlessReturnToStorage.value || queue.output() == null
                || System.currentTimeMillis() < storageRetryAt || creative()) {
            return false;
        }
        return freeSlots() <= Baritone.settings().effortlessFreeSlotsLow.value && trips.available();
    }

    private void deposit(JobQueue.Entry e) {
        tripOut = true;
        tripStarted = System.currentTimeMillis();
        tripTimed = true;
        stopBuilderFor(e);
        BuildMode.say("Inventory full, emptying it into the storage");
        trips.deposit(queue.output().range(dimension()), why -> {
            tripOut = false;
            if (playerStopped(why, e)) {
                return;
            }
            // a deposit we had to call off is treated like a full storage: carry on, look later
            if (EffortlessTrips.FULL.equals(why) || "stopped".equals(why)) {
                if (EffortlessTrips.FULL.equals(why) && Baritone.settings().effortlessStorageFull.value == EffortlessStorageFull.STOP) {
                    // stay at the storage, the rest of the queue waits
                    e.state(JobQueue.State.FAILED, "the output storage is full");
                    queue.running(false);
                    BuildMode.say("The storage is full, stopped there");
                    save();
                    return;
                }
                // a hopper may empty it, so try again later, and keep working meanwhile with the bag as it is
                storageRetryAt = System.currentTimeMillis() + 1000L * Baritone.settings().effortlessStorageRetrySeconds.value;
                BuildMode.say("The storage is full, carrying on and trying again later");
            } else if (why != null) {
                BuildMode.say("Storage: " + why);
            }
            if (e.state() == JobQueue.State.RUNNING && queue.entries().contains(e)) {
                startEntry(e);
                save();
            }
        });
    }

    // an errand came back "stopped" and it wasn't our timeout: the player said #stop. the entry waits, the queue stops
    private boolean playerStopped(String why, JobQueue.Entry e) {
        boolean ours = tripCalledOff;
        tripCalledOff = false;
        if (!"stopped".equals(why) || ours) {
            return false;
        }
        e.state(JobQueue.State.QUEUED, null);
        queue.running(false);
        BuildMode.say("Effortless queue paused");
        save();
        return true;
    }

    private void stopBuilderFor(JobQueue.Entry e) {
        if (entrySerial != 0 && baritone.getBuilderProcess().buildSerial() == entrySerial) {
            baritone.getBuilderProcess().onLostControl();
        }
        entrySerial = 0;
    }

    private Dimension dimension() {
        var level = baritone.getPlayerContext().world();
        if (level == null || level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            return Dimension.OVERWORLD;
        }
        return level.dimension() == net.minecraft.world.level.Level.NETHER ? Dimension.NETHER : Dimension.END;
    }

    private int freeSlots() {
        var p = baritone.getPlayerContext().player();
        if (p == null) {
            return 36;
        }
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    // what the entry still needs placed, as items, less what the inventory already holds
    Map<Item, Integer> billFor(JobQueue.Entry e) {
        var level = baritone.getPlayerContext().world();
        Map<Item, Integer> need = new java.util.LinkedHashMap<>();
        if (level == null) {
            return need;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (EffortlessJob j : e.jobs()) {
            if (j.mode != EffortlessJob.Mode.PLACE) {
                continue;
            }
            j.mask.forEach((x, y, z) -> {
                EffortlessJob.Outcome o = j.outcome(x, y, z, level.getBlockState(at.set(x, y, z)));
                if (o == EffortlessJob.Outcome.PLACE || o == EffortlessJob.Outcome.REPLACE) {
                    Item item = j.desired(x, y, z).getBlock().asItem();
                    if (item != net.minecraft.world.item.Items.AIR) {
                        need.merge(item, 1, Integer::sum);
                    }
                }
            });
        }
        return lessCarried(need);
    }

    private Map<Item, Integer> bill(Map<BlockState, Integer> missing) {
        Map<Item, Integer> need = new java.util.LinkedHashMap<>();
        missing.forEach((st, n) -> {
            Item item = st.getBlock().asItem();
            if (item != net.minecraft.world.item.Items.AIR) {
                need.merge(item, n, Integer::sum);
            }
        });
        return need;
    }

    private Map<Item, Integer> lessCarried(Map<Item, Integer> need) {
        var p = baritone.getPlayerContext().player();
        Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        need.forEach((item, n) -> {
            int have = 0;
            if (p != null) {
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    var st = p.getInventory().getItem(i);
                    if (st.getItem() == item) {
                        have += st.getCount();
                    }
                }
            }
            if (n > have) {
                out.put(item, n - have);
            }
        });
        return out;
    }

    private static int total(Map<Item, Integer> bill) {
        return bill.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String names(java.util.Collection<BlockState> states) {
        List<String> out = new ArrayList<>();
        for (BlockState s : states) {
            out.add(s.getBlock().getName().getString());
        }
        return String.join(", ", out);
    }

    // the queued entries with show on, as one schematic for the preview. null when none are
    public JobSchematic shownSchematic() {
        List<EffortlessJob> jobs = new ArrayList<>();
        List<Object> key = new ArrayList<>();
        for (JobQueue.Entry e : queue.entries()) {
            if (e.shown() && e.state() != JobQueue.State.RUNNING && e.state() != JobQueue.State.DONE) {
                jobs.addAll(e.jobs());
                key.add(e);
            }
        }
        if (jobs.isEmpty()) {
            return null;
        }
        if (key.equals(shownKey)) {
            return shownSchematic;
        }
        shownKey = key;
        shownSchematic = new JobSchematic(jobs);
        return shownSchematic;
    }

    // the queue file belongs to the world and dimension you're in. a new one loads its own
    private void followWorld() {
        var world = baritone.getWorldProvider().getCurrentWorld();
        if (world == queueWorld) {
            return;
        }
        if (queue.runningEntry() != null) {
            queue.runningEntry().state(JobQueue.State.QUEUED, null);
        }
        queueWorld = world;
        queueFile = world == null ? null : world.directory.resolve(JobStore.FILE_NAME);
        queue = JobStore.load(queueFile);
        entrySerial = 0;
        shownKey = null;
    }

    // what the preview should show for the next job, or null when there's nothing set up to show
    public void draft(EffortlessJob.Mode mode, EffortlessOptions options) {
        this.draftMode = mode;
        this.draftOptions = options;
    }

    public EffortlessJob.Mode draftMode() {
        return draftMode;
    }

    // the job the current points (plus the crosshair for the missing ones) and the draft would make. null when there
    // are no points, no draft, or the draft can't make a job here (no block in hand, too big)
    public JobSchematic previewSchematic() {
        if (points.isEmpty() || draftMode == null || draftOptions == null) {
            return null;
        }
        AreaMask mask = currentMask(true);
        if (mask == null || mask.isEmpty() || mask.count() > Baritone.settings().effortlessMaxBlocks.value) {
            return null;
        }
        List<BlockPos> pts = effectivePoints(true);
        Optional<BlockState> hand = heldBlock(false);
        Optional<BlockState> off = heldBlock(true);
        List<Object> key = List.of(pts, spec, draftMode, draftOptions, hand, off);
        if (key.equals(previewJobKey)) {
            return previewSchematic;
        }
        previewJobKey = key;
        try {
            EffortlessJob job = draftMode == EffortlessJob.Mode.PLACE
                    ? draftOptions.withSpec(spec).placeJob(pts, hand, off)
                    : draftOptions.withSpec(spec).breakJob(pts, hand, off);
            previewSchematic = new JobSchematic(job);
        } catch (EffortlessOptions.Bad | RuntimeException e) {
            previewSchematic = null;
        }
        return previewSchematic;
    }

    // the job the builder is on, while it is
    public JobSchematic runningSchematic() {
        return queue.runningEntry() != null ? runningSchematic : null;
    }

    // a block in the area the job won't change. pure: the builder asks from its path thread
    static IBuilderProcess.Keep keepUntouched(JobSchematic schematic) {
        return (x, y, z, current) -> {
            if (current.isAir()) {
                return false;
            }
            EffortlessJob job = schematic.owner(x, y, z);
            return job != null && job.outcome(x, y, z, current) == EffortlessJob.Outcome.UNTOUCHED;
        };
    }

    // a finished shape from build mode: started right away, or into the queue (the default)
    public void submit(EffortlessJob job) {
        if (Baritone.settings().effortlessStartRightAway.value) {
            start(job);
            return;
        }
        queue.add(job, System.currentTimeMillis()).materials(BuildMode.materials());
        save();
        int waiting = 0;
        for (JobQueue.Entry e : queue.entries()) {
            if (e.state() == JobQueue.State.QUEUED) {
                waiting++;
            }
        }
        BuildMode.say("Queued " + job.name + " (" + waiting + " waiting, #ef queue start or the Effortless tab)");
    }

    public EffortlessJob running() {
        return queue.runningEntry() != null ? running : null;
    }

    // ----- the outline

    // marked storage, a green box each, while build mode is on (that's when you're thinking about them)
    private void drawStorage(RenderEvent event) {
        if (!BuildMode.active() || queue.storage().isEmpty()) {
            return;
        }
        PoseStack stack = event.getModelViewStack();
        BufferBuilder buf = IRenderer.startLines(new java.awt.Color(0x5BD27A), 0.9f);
        for (StorageArea a : queue.storage()) {
            IRenderer.emitAABB(buf, stack, new AABB(Math.min(a.a().getX(), a.b().getX()), Math.min(a.a().getY(), a.b().getY()),
                    Math.min(a.a().getZ(), a.b().getZ()), Math.max(a.a().getX(), a.b().getX()) + 1, Math.max(a.a().getY(), a.b().getY()) + 1,
                    Math.max(a.a().getZ(), a.b().getZ()) + 1), 0.02, Baritone.settings().selectionLineWidth.value);
        }
        IRenderer.endLines(buf, false);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && event.getState() == EventState.PRE) {
            followWorld();
            BuildMode.tick();
            tickQueue();
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        drawStorage(event);
        if (points.isEmpty()) {
            return;
        }
        AreaMask mask = currentMask(true);
        PoseStack stack = event.getModelViewStack();
        float width = Baritone.settings().selectionLineWidth.value;
        BufferBuilder buf = IRenderer.startLines(Baritone.settings().colorEffortlessOutline.value, 0.9f);
        if (mask != null && !mask.isEmpty()) {
            IRenderer.emitAABB(buf, stack, new AABB(mask.minX, mask.minY, mask.minZ, mask.maxX() + 1, mask.maxY() + 1, mask.maxZ() + 1), GROW, width);
        }
        for (BlockPos p : points) {
            if (p != null) {
                IRenderer.emitAABB(buf, stack, new AABB(p), 0.01, width);
            }
        }
        IRenderer.endLines(buf, false);
    }
}
