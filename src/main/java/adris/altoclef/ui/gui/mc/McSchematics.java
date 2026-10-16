package adris.altoclef.ui.gui.mc;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.SchematicTransforms;
import baritone.api.utils.Helper;
import baritone.cache.WorldData;
import baritone.utils.schematic.library.InventoryCounts;
import baritone.utils.schematic.library.MaterialLine;
import baritone.utils.schematic.library.MaterialList;
import baritone.utils.schematic.library.PlacementStore;
import baritone.utils.schematic.library.SchematicImporter;
import baritone.utils.schematic.library.SchematicInfo;
import baritone.utils.schematic.library.SchematicLibrary;
import baritone.utils.schematic.library.SchematicPlacement;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

// the real SchematicsAccess. the folder scan and every parse run on one daemon thread, the gui only ever reads the
// latest finished snapshot. one instance is shared by every gui screen (a gui scale change rebuilds the screen and its
// facade), so the parsed list survives that and a rebuild doesn't re-read the whole folder
public final class McSchematics implements SchematicsAccess {

    private static McSchematics shared;

    public static synchronized McSchematics shared() {
        if (shared == null) {
            File gameDir = Minecraft.getInstance().gameDirectory;
            shared = new McSchematics(SchematicLibrary.forGameDir(gameDir), worker(), McSchematics::playerItems,
                McSchematics::catalogueKnows, runnable -> Minecraft.getInstance().execute(runnable), FilePicker::pick,
                new LiveGame());
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

    // the world side of placing and building, a seam so the tests run without a game. game thread only, all of it
    interface Game {
        // the level id the player is in ("minecraft:overworld"), null when there's no world
        String dimension();

        // null when there's no player
        BlockPos feet();

        // where this world's placements live, null when it has no folder (replay), then they only live in memory
        Path placementsFile();

        // anything that changes with the level, so the terrain cache knows to start over. null without a world
        Object level();

        TerrainMap.Source terrain();

        void build(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror);

        // a line in chat, for when a build fails after the click (the tab may be closed by then)
        void say(String message);
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

    private static final class Parsed {
        final long lastModified;
        final long size;
        volatile IStaticSchematic schematic;
        volatile boolean failed;
        // game thread only
        ISchematic turned;
        Rotation rotation;
        Mirror mirror;

        Parsed(SchematicInfo info) {
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
    private final Game game;
    private final TerrainMap terrainMap = new TerrainMap(System::currentTimeMillis);

    private final Map<String, Cached> materials = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MATERIAL_CACHE;
        }
    };
    private final AtomicBoolean pickerOpen = new AtomicBoolean();

    // the parsed file behind transformed(), kept strong while the preview wants it. two, a preview shows one at a time
    private final Map<String, Parsed> parsed = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
            return size() > 2;
        }
    };

    // game thread only: this world's placements. placements = chosen or built at (saved), defaults = made up at the
    // feet and not saved
    private final Map<String, SchematicPlacement> placements = new LinkedHashMap<>();
    private final Map<String, SchematicPlacement> defaults = new HashMap<>();
    private boolean placementsLoaded;
    private boolean saveBlocked;
    private Path placementsFile;
    private int buildTicket;
    private volatile Building building = Building.IDLE;

    // guarded by this
    private boolean started;
    private boolean loading;
    private boolean again;
    private Listing listing = new Listing(State.LOADING, List.of());
    private Map<String, SchematicInfo> infos = Map.of();

    McSchematics(SchematicLibrary library, Executor background, Supplier<Map<String, Integer>> inventory,
                 Predicate<String> gettable, Executor gameThread, Picker picker, Game game) {
        this.library = library;
        this.background = background;
        this.inventory = inventory;
        this.gettable = gettable;
        this.gameThread = gameThread;
        this.picker = picker;
        this.game = game;
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

    // ----- placements. game thread only, the gui and the tick are the only callers

    @Override
    public Placement placement(String relativePath) {
        return view(current(relativePath));
    }

    @Override
    public Placement move(String relativePath, int dx, int dy, int dz) {
        return edit(relativePath, p -> p.moved(dx, dy, dz));
    }

    @Override
    public Placement rotate(String relativePath, boolean clockwise) {
        SchematicInfo info = info(relativePath);
        return info == null ? null : edit(relativePath, p -> p.rotated(clockwise, info.sizeX(), info.sizeY(), info.sizeZ()));
    }

    @Override
    public Placement mirror(String relativePath) {
        return edit(relativePath, SchematicPlacement::flipped);
    }

    @Override
    public Placement placeAtFeet(String relativePath) {
        BlockPos feet = game.feet();
        String dimension = game.dimension();
        if (feet == null || dimension == null) {
            return null;
        }
        return edit(relativePath, p -> p.movedTo(feet, dimension));
    }

    @Override
    public Footprint footprint(String relativePath) {
        SchematicPlacement p = current(relativePath);
        SchematicInfo info = info(relativePath);
        if (p == null || info == null) {
            return null;
        }
        Vec3i size = p.size(info.sizeX(), info.sizeY(), info.sizeZ());
        return new Footprint(p.origin().getX(), p.origin().getY(), p.origin().getZ(), size.getX(), size.getY(), size.getZ());
    }

    // the placement the gui sees and the build uses, without the minecraft types
    private Placement view(SchematicPlacement p) {
        if (p == null) {
            return null;
        }
        return new Placement(p.origin().getX(), p.origin().getY(), p.origin().getZ(), p.turns(), p.isMirrored(),
            p.dimension(), p.dimension().equals(game.dimension()));
    }

    private Placement edit(String relativePath, UnaryOperator<SchematicPlacement> change) {
        try {
            SchematicPlacement p = current(relativePath);
            if (p == null) {
                return null;
            }
            SchematicPlacement next = change.apply(p);
            pin(relativePath, next);
            return view(next);
        } catch (Throwable t) {
            Safe.report("gui schematic placement", t);
            return null;
        }
    }

    // a listed row that parsed, the only kind with a size to turn
    private SchematicInfo info(String relativePath) {
        list();
        SchematicInfo info;
        synchronized (this) {
            info = infos.get(relativePath);
        }
        return info != null && info.ok() ? info : null;
    }

    // this world's placement for the path, making the feet default when there is none yet
    private SchematicPlacement current(String relativePath) {
        if (relativePath == null || !syncWorld() || info(relativePath) == null) {
            return null;
        }
        SchematicPlacement saved = placements.get(relativePath);
        if (saved != null) {
            return saved;
        }
        String dimension = game.dimension();
        SchematicPlacement made = defaults.get(relativePath);
        // an unsaved default follows the player through a portal, nobody chose the old spot
        if (made == null || !made.dimension().equals(dimension)) {
            BlockPos feet = game.feet();
            if (feet == null) {
                return null;
            }
            made = SchematicPlacement.at(feet, dimension);
            defaults.put(relativePath, made);
        }
        return made;
    }

    // the file the placements come from follows the world. a new world drops what the old one had in memory
    private boolean syncWorld() {
        if (game.dimension() == null) {
            return false;
        }
        Path file = game.placementsFile();
        if (!placementsLoaded || !Objects.equals(file, placementsFile)) {
            placementsLoaded = true;
            placementsFile = file;
            defaults.clear();
            placements.clear();
            Map<String, SchematicPlacement> loaded = PlacementStore.load(file, this::exists);
            saveBlocked = loaded == null;
            if (loaded != null) {
                placements.putAll(loaded);
            }
        }
        return true;
    }

    private boolean exists(String relativePath) {
        return fileFor(relativePath) != null;
    }

    // a placement somebody chose (or built at) is saved, the made-up ones aren't
    private void pin(String relativePath, SchematicPlacement p) {
        defaults.remove(relativePath);
        placements.put(relativePath, p);
        if (placementsFile == null) {
            return;
        }
        if (saveBlocked) {
            // the file was there but unreadable when the world loaded. saving now would wipe everything else in it,
            // so read it again first and only save once that works. until then the edits live in memory
            Map<String, SchematicPlacement> loaded = PlacementStore.load(placementsFile, this::exists);
            if (loaded == null) {
                return;
            }
            Map<String, SchematicPlacement> merged = new LinkedHashMap<>(loaded);
            merged.putAll(placements);
            placements.clear();
            placements.putAll(merged);
            saveBlocked = false;
        }
        PlacementStore.save(placementsFile, placements);
    }

    // ----- building

    @Override
    public Result build(String relativePath) {
        try {
            if (game.dimension() == null || game.feet() == null) {
                return Result.fail("Join a world first");
            }
            SchematicInfo info = info(relativePath);
            if (info == null) {
                return Result.fail(fileFor(relativePath) == null ? "Can't find that schematic, refresh the list"
                    : "That schematic didn't load");
            }
            SchematicPlacement p = current(relativePath);
            if (p == null) {
                return Result.fail("Join a world first");
            }
            if (!p.dimension().equals(game.dimension())) {
                return Result.fail("It's placed in another dimension, move it here first");
            }
            // building somewhere is choosing it
            pin(relativePath, p);
            int ticket = ++buildTicket;
            Path world = placementsFile;
            building = new Building(BuildState.PARSING, relativePath, "Reading " + info.name());
            background.execute(() -> read(ticket, world, info, p));
            BlockPos o = p.origin();
            return Result.ok("Building " + info.name() + " at " + o.getX() + ", " + o.getY() + ", " + o.getZ());
        } catch (Throwable t) {
            Safe.report("gui schematic build", t);
            return Result.fail("Could not start building");
        }
    }

    @Override
    public Building building() {
        return building;
    }

    // the background half: the same parse (and the same soft cache) the material count used, then back to the game thread
    private void read(int ticket, Path world, SchematicInfo info, SchematicPlacement p) {
        IStaticSchematic parsedFile = null;
        String error = null;
        try {
            parsedFile = library.load(info);
        } catch (OutOfMemoryError e) {
            error = "Too big to build (out of memory)";
        } catch (Throwable t) {
            Safe.report("gui schematic build read", t);
            error = "Couldn't read " + info.name();
        }
        IStaticSchematic schematic = parsedFile;
        String failure = error;
        gameThread.execute(() -> Safe.run("gui schematic build start", () -> start(ticket, world, info, p, schematic, failure)));
    }

    private void start(int ticket, Path world, SchematicInfo info, SchematicPlacement p, IStaticSchematic schematic, String error) {
        // a second click while the first was reading wins
        if (ticket != buildTicket) {
            return;
        }
        String why = error;
        if (why == null && (!p.dimension().equals(game.dimension()) || !Objects.equals(world, game.placementsFile()))) {
            why = "The world changed before " + info.name() + " was read, nothing built";
        }
        if (why != null) {
            building = new Building(BuildState.ERROR, info.relativePath(), why);
            game.say(why);
            return;
        }
        try {
            game.build(info.file().getName(), schematic, p.origin(), p.rotation(), p.mirror());
            building = new Building(BuildState.STARTED, info.relativePath(), "Building " + info.name());
        } catch (Throwable t) {
            Safe.report("gui schematic build", t);
            building = new Building(BuildState.ERROR, info.relativePath(), "Could not start building");
            game.say("Could not start building " + info.name());
        }
    }

    // ----- the turned file, for drawing the build where it goes

    // the file as it will be built at its placement: SchematicTransforms.apply with the placement's turn and flip, the
    // same wrappers the build gets. read in the background on first ask, null until then (and when there's no
    // placement or the file won't read, see previewFailed). local 0,0,0 is the footprint's min corner. it's the plain
    // turned file: map art, buildOnlySelection, substitutes and skip blocks are build settings and aren't in it.
    // game thread only
    public ISchematic transformed(String relativePath) {
        try {
            SchematicPlacement p = current(relativePath);
            SchematicInfo info = info(relativePath);
            if (p == null || info == null) {
                return null;
            }
            Parsed entry;
            boolean fresh = false;
            synchronized (parsed) {
                entry = parsed.get(relativePath);
                if (entry == null || entry.lastModified != info.lastModified() || entry.size != info.fileSize()) {
                    entry = new Parsed(info);
                    parsed.put(relativePath, entry);
                    fresh = true;
                }
            }
            if (fresh) {
                Parsed target = entry;
                background.execute(() -> {
                    try {
                        target.schematic = library.load(info);
                    } catch (Throwable t) {
                        Safe.report("gui schematic preview", t);
                        target.failed = true;
                    }
                });
            }
            IStaticSchematic raw = entry.schematic;
            if (raw == null) {
                return null;
            }
            if (entry.turned == null || entry.rotation != p.rotation() || entry.mirror != p.mirror()) {
                entry.turned = SchematicTransforms.apply(raw, p.rotation(), p.mirror());
                entry.rotation = p.rotation();
                entry.mirror = p.mirror();
            }
            return entry.turned;
        } catch (Throwable t) {
            Safe.report("gui schematic preview", t);
            return null;
        }
    }

    // the background read behind transformed() threw. it isn't retried until the file changes on disk (and a refresh
    // notices), so the preview can say so instead of waiting forever
    public boolean previewFailed(String relativePath) {
        synchronized (parsed) {
            Parsed entry = parsed.get(relativePath);
            return entry != null && entry.failed;
        }
    }

    // ----- the ghost in the world (SchematicGhostRenderer draws it)

    // game thread only. the gui sets it, it stays after the gui closes so you can walk around the thing
    private String ghost;

    @Override
    public void setGhost(String relativePath) {
        ghost = relativePath;
    }

    // null once the world is gone, a ghost doesn't follow you into the next world you join
    @Override
    public String ghost() {
        if (ghost != null && game.dimension() == null) {
            ghost = null;
        }
        return ghost;
    }

    public void clearGhost() {
        ghost = null;
    }

    // ----- the map

    @Override
    public Terrain terrain(int centerX, int centerZ, int radiusChunks) {
        try {
            Object level = game.level();
            TerrainMap.Source source = game.terrain();
            if (level == null || source == null) {
                terrainMap.reset();
                return null;
            }
            return terrainMap.snapshot(level, source, centerX, centerZ, radiusChunks);
        } catch (Throwable t) {
            Safe.report("gui schematic terrain", t);
            return null;
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

    // ----- the real world

    private static final class LiveGame implements Game {

        @Override
        public String dimension() {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : level.dimension().identifier().toString();
        }

        @Override
        public BlockPos feet() {
            if (Minecraft.getInstance().player == null) {
                return null;
            }
            return BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerFeet().immutable();
        }

        // next to gamer.json: two up from baritone's per dimension folder, the same walk RunStateStore.resolvePath does.
        // singleplayer that's <save>/baritone/altoclef/, a server <gameDir>/baritone/<ip>/altoclef/
        @Override
        public Path placementsFile() {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (!(primary instanceof Baritone baritone)) {
                return null;
            }
            WorldData world = baritone.getWorldProvider().getCurrentWorld();
            if (world == null || world.directory.getParent() == null || world.directory.getParent().getParent() == null) {
                return null;
            }
            return world.directory.getParent().getParent().resolve("altoclef").resolve(PlacementStore.FILE_NAME);
        }

        @Override
        public Object level() {
            return Minecraft.getInstance().level;
        }

        @Override
        public TerrainMap.Source terrain() {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : new LevelSource(level);
        }

        @Override
        public void build(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().build(name, schematic, origin, rotation, mirror);
        }

        @Override
        public void say(String message) {
            Helper.HELPER.logDirect(message);
        }
    }

    // the client level as the map sees it. WORLD_SURFACE is one of the heightmaps the server sends, so it's there for
    // every loaded chunk, and it counts water as the top (which is what a map wants)
    private static final class LevelSource implements TerrainMap.Source {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        LevelSource(ClientLevel level) {
            this.level = level;
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            // not level.hasChunk, ClientLevel's is a flat 'return true'. the chunk source says null for what never arrived
            return level.getChunkSource().hasChunk(chunkX, chunkZ);
        }

        @Override
        public int surfaceY(int x, int z) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        }

        @Override
        public int minY() {
            return level.getMinY();
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }

        @Override
        public MapColor color(BlockState state, int x, int y, int z) {
            return state.getMapColor(level, pos.set(x, y, z));
        }
    }
}
