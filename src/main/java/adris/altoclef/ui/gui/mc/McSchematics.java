package adris.altoclef.ui.gui.mc;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.library.InventoryCounts;
import baritone.utils.schematic.library.MaterialLine;
import baritone.utils.schematic.library.MaterialList;
import baritone.utils.schematic.library.SchematicImporter;
import baritone.utils.schematic.library.SchematicInfo;
import baritone.utils.schematic.library.SchematicLibrary;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

// the real SchematicsAccess. the folder scan and every parse run on one daemon thread, the gui only ever reads the
// latest finished snapshot. one instance is shared by every gui screen (a gui scale change rebuilds the screen and its
// facade), so the parsed list survives that and a rebuild doesn't re-read the whole folder
public final class McSchematics implements SchematicsAccess {

    private static McSchematics shared;

    public static synchronized McSchematics shared() {
        if (shared == null) {
            File gameDir = Minecraft.getInstance().gameDirectory;
            shared = new McSchematics(SchematicLibrary.forGameDir(gameDir), worker(), McSchematics::playerItems,
                McSchematics::catalogueKnows, runnable -> Minecraft.getInstance().execute(runnable), FilePicker::pick);
        }
        return shared;
    }

    private static ExecutorService worker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "soprano-schematics");
            t.setDaemon(true);
            return t;
        });
    }

    private static final int MATERIAL_CACHE = 12;

    // what the file picker is: extensions in, a choice out. a seam so the tests don't open dialogs
    interface Picker {
        FilePicker.Choice pick(List<String> extensions, String title);
    }

    private static final class Cached {
        final long lastModified;
        final long size;
        volatile boolean done;
        volatile MaterialList list;
        volatile String error;
        // the last answer handed out and the inventory it was made for, so a steady inventory costs nothing per frame
        Map<String, Integer> mergedFor;
        Materials merged;

        Cached(SchematicInfo info) {
            this.lastModified = info.lastModified();
            this.size = info.fileSize();
        }
    }

    private final SchematicLibrary library;
    private final Executor background;
    private final Supplier<Map<String, Integer>> inventory;
    private final Predicate<String> gettable;
    private final Executor gameThread;
    private final Picker picker;

    private final Map<String, Cached> materials = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MATERIAL_CACHE;
        }
    };
    private final AtomicBoolean pickerOpen = new AtomicBoolean();

    // guarded by this
    private boolean started;
    private boolean loading;
    private boolean again;
    private Listing listing = new Listing(State.LOADING, List.of());
    private Map<String, SchematicInfo> infos = Map.of();

    McSchematics(SchematicLibrary library, Executor background, Supplier<Map<String, Integer>> inventory,
                 Predicate<String> gettable, Executor gameThread, Picker picker) {
        this.library = library;
        this.background = background;
        this.inventory = inventory;
        this.gettable = gettable;
        this.gameThread = gameThread;
        this.picker = picker;
    }

    // ----- the list

    @Override
    public Listing list() {
        boolean first;
        synchronized (this) {
            first = !started;
            started = true;
        }
        if (first) {
            refresh();
        }
        synchronized (this) {
            return listing;
        }
    }

    @Override
    public void refresh() {
        synchronized (this) {
            started = true;
            if (loading) {
                // a scan is already running, it'll go around once more when it's done in case the folder changed since
                again = true;
                return;
            }
            loading = true;
            listing = new Listing(State.LOADING, listing.rows());
        }
        background.execute(this::scan);
    }

    private void scan() {
        while (true) {
            List<SchematicInfo> found = null;
            try {
                found = library.list();
            } catch (Throwable t) {
                Safe.report("gui schematics list", t);
            }
            synchronized (this) {
                if (found != null) {
                    Map<String, SchematicInfo> byPath = new LinkedHashMap<>();
                    List<Row> rows = new ArrayList<>(found.size());
                    for (SchematicInfo info : found) {
                        byPath.put(info.relativePath(), info);
                        rows.add(row(info));
                    }
                    infos = Collections.unmodifiableMap(byPath);
                    listing = new Listing(State.LOADING, List.copyOf(rows));
                }
                if (again) {
                    again = false;
                    continue;
                }
                loading = false;
                listing = new Listing(State.READY, listing.rows());
                break;
            }
        }
        // a failed parse (out of memory, a file that was half copied) gets another go after a refresh
        synchronized (materials) {
            materials.values().removeIf(c -> c.done && c.error != null);
        }
    }

    private static Row row(SchematicInfo info) {
        return new Row(info.name(), info.relativePath(), info.sizeX(), info.sizeY(), info.sizeZ(), info.blockCount(),
            info.format(), info.version(), info.fileSize(), info.error(), info.unknownSummary());
    }

    // ----- the cost of a build

    @Override
    public Materials materials(String relativePath) {
        list();
        SchematicInfo info;
        State state;
        synchronized (this) {
            info = infos.get(relativePath);
            state = listing.state();
        }
        if (info == null) {
            return state == State.LOADING ? Materials.loading(relativePath) : failed(relativePath, "Not in the library, refresh the list");
        }
        if (!info.ok()) {
            return failed(relativePath, info.error());
        }
        Cached entry;
        boolean fresh = false;
        synchronized (materials) {
            entry = materials.get(relativePath);
            if (entry == null || entry.lastModified != info.lastModified() || entry.size != info.fileSize()) {
                entry = new Cached(info);
                materials.put(relativePath, entry);
                fresh = true;
            }
        }
        if (fresh) {
            Cached target = entry;
            background.execute(() -> count(info, target));
        }
        if (!entry.done) {
            return Materials.loading(relativePath);
        }
        if (entry.error != null) {
            return failed(relativePath, entry.error);
        }
        return merge(relativePath, entry);
    }

    private void count(SchematicInfo info, Cached entry) {
        try {
            IStaticSchematic parsed = library.load(info);
            entry.list = MaterialList.of(parsed);
        } catch (OutOfMemoryError e) {
            entry.error = "Too big to count (out of memory)";
        } catch (Throwable t) {
            Safe.report("gui schematic materials", t);
            entry.error = "Couldn't read this file";
        } finally {
            entry.done = true;
        }
    }

    private static Materials failed(String relativePath, String error) {
        return new Materials(State.READY, relativePath, List.of(), List.of(), List.of(), List.of(), error);
    }

    // the needs never change for a file, what the player carries does, so only this half is redone per inventory
    private Materials merge(String relativePath, Cached entry) {
        Map<String, Integer> have = inventory.get();
        synchronized (entry) {
            if (entry.merged != null && have.equals(entry.mergedFor)) {
                return entry.merged;
            }
            MaterialList list = entry.list;
            Materials out = new Materials(State.READY, relativePath, lines(list.items(), Kind.ITEM, have),
                lines(list.cantPlace(), Kind.CANT_PLACE, have), lines(list.notCounted(), Kind.NOT_COUNTED, have),
                list.notes(), null);
            entry.mergedFor = new HashMap<>(have);
            entry.merged = out;
            return out;
        }
    }

    private List<Line> lines(List<MaterialLine> source, Kind kind, Map<String, Integer> have) {
        List<Line> out = new ArrayList<>(source.size());
        for (MaterialLine line : source) {
            String id = line.item();
            boolean item = kind == Kind.ITEM;
            out.add(new Line(id, item ? id : iconFor(id), item ? itemName(id) : blockName(id), line.count(),
                item ? have.getOrDefault(id, 0) : 0, kind, line.note(), item && gettable.test(id)));
        }
        return List.copyOf(out);
    }

    // ----- names and icons

    private static Item itemOf(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(key);
    }

    private static String itemName(String id) {
        try {
            Item item = itemOf(id);
            if (item != Items.AIR) {
                String name = item.getDefaultInstance().getItemName().getString();
                // a name that is still a translation key means the language isn't loaded, which is no help to anyone
                if (!name.isEmpty() && !name.startsWith("item.") && !name.startsWith("block.")) {
                    return name;
                }
            }
        } catch (Throwable t) {
            // fall through to the id
        }
        return ItemEntry.titleCase(id);
    }

    private static String blockName(String id) {
        try {
            Identifier key = Identifier.tryParse(id);
            Block block = key == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(key);
            if (block != Blocks.AIR) {
                String name = block.getName().getString();
                if (!name.isEmpty() && !name.startsWith("block.")) {
                    return name;
                }
            }
        } catch (Throwable t) {
            // fall through to the id
        }
        return ItemEntry.titleCase(id);
    }

    // the rows that aren't items have a block id. a few of those still have something to draw
    private static String iconFor(String blockId) {
        if (itemOf(blockId) != Items.AIR) {
            return blockId;
        }
        return switch (blockId) {
            case "minecraft:water" -> "minecraft:water_bucket";
            case "minecraft:lava" -> "minecraft:lava_bucket";
            case "minecraft:fire" -> "minecraft:flint_and_steel";
            case "minecraft:soul_fire" -> "minecraft:flint_and_steel";
            default -> "";
        };
    }

    // ----- the game side

    private static Map<String, Integer> playerItems() {
        try {
            var player = Minecraft.getInstance().player;
            return player == null ? Map.of() : InventoryCounts.of(player.getInventory());
        } catch (Throwable t) {
            Safe.report("gui schematics inventory", t);
            return Map.of();
        }
    }

    private static boolean catalogueKnows(String itemId) {
        try {
            Item item = itemOf(itemId);
            return item != Items.AIR && TaskCatalogue.isObtainable(item);
        } catch (Throwable t) {
            // the catalogue builds itself on first touch, and a tab that throws every frame is worse than no flag
            Safe.report("gui schematics catalogue", t);
            return false;
        }
    }

    @Override
    public Result build(String relativePath) {
        try {
            File file = fileFor(relativePath);
            if (file == null) {
                return Result.fail("Can't find that schematic, refresh the list");
            }
            var player = Minecraft.getInstance().player;
            if (player == null) {
                return Result.fail("Join a world first");
            }
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            BetterBlockPos origin = baritone.getPlayerContext().playerFeet();
            // same call the build command makes
            if (!baritone.getBuilderProcess().build(file.getName(), file, origin)) {
                return Result.fail("Couldn't load that schematic");
            }
            String command = relativePath.indexOf(' ') < 0 ? "build " + relativePath : null;
            return new Result(true, "Building " + file.getName() + " at your feet", command);
        } catch (Throwable t) {
            Safe.report("gui schematic build", t);
            return Result.fail("Could not start building");
        }
    }

    // a listed file, or failing that a file that is inside the folder and exists. a relative path with .. in it is not
    private File fileFor(String relativePath) {
        SchematicInfo info;
        synchronized (this) {
            info = infos.get(relativePath);
        }
        if (info != null) {
            return info.file();
        }
        Path root = library.directory().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        return resolved.startsWith(root) && Files.isRegularFile(resolved) ? resolved.toFile() : null;
    }

    @Override
    public void importFiles(Consumer<ImportReport> done) {
        if (!pickerOpen.compareAndSet(false, true)) {
            gameThread.execute(() -> done.accept(ImportReport.failed("The file picker is already open")));
            return;
        }
        Runnable job = () -> {
            ImportReport report;
            try {
                FilePicker.Choice choice = picker.pick(SchematicImporter.extensions(), "Import schematics");
                if (choice.error() != null) {
                    report = ImportReport.failed(choice.error());
                } else if (choice.wasCancelled()) {
                    report = ImportReport.nothingChosen();
                } else {
                    report = importDropped(choice.files());
                }
            } catch (Throwable t) {
                Safe.report("gui schematic import", t);
                report = ImportReport.failed("Import failed");
            } finally {
                pickerOpen.set(false);
            }
            ImportReport result = report;
            gameThread.execute(() -> Safe.run("gui schematic import done", () -> done.accept(result)));
        };
        // the helper thread, not the scan thread: a dialog can sit open for minutes and the list shouldn't wait on it
        Thread thread = new Thread(job, "soprano-schematic-picker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public ImportReport importDropped(List<Path> files) {
        try {
            SchematicImporter.Report report = new SchematicImporter(library.directory().toPath()).importAll(files);
            if (!report.imported().isEmpty()) {
                refresh();
            }
            return new ImportReport(report.imported(), report.skipped(), report.errors(), false);
        } catch (Throwable t) {
            Safe.report("gui schematic drop", t);
            return ImportReport.failed("Import failed");
        }
    }

    @Override
    public Result openFolder() {
        try {
            Path dir = library.directory().toPath();
            Files.createDirectories(dir);
            Blaze3D.openPath(dir);
            return Result.ok("Opened the schematics folder");
        } catch (Throwable t) {
            Safe.report("gui schematic folder", t);
            return Result.fail("Couldn't open the folder");
        }
    }

    @Override
    public String folderPath() {
        return library.directory().getPath();
    }
}
