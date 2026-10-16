package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import baritone.utils.schematic.library.SchematicImporter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// a schematics folder with a few builds in it, enough variety for a preview to show every state of the tab: a clean
// file, one with unrecognized blocks, one that didn't parse, one in a sub folder. nothing touches the disk. every action
// records itself in the facade's `actions` like the other fakes. the load states are driven by hand: loadingPolls is
// how many calls to list() / materials() answer LOADING after a refresh, so a tab test can watch it flip
public final class FakeSchematics implements SchematicsAccess {

    private final List<String> actions;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Materials> materials = new LinkedHashMap<>();
    // what the player is carrying, applied on top of the stored lists on every call like the real thing does
    public final Map<String, Integer> have = new LinkedHashMap<>();

    public int loadingPolls;
    public boolean loadingForever;
    // set to make build() fail with this message
    public String buildError;
    // what the next importFiles() call hands back. null means "pick the sample file and import it"
    public ImportReport nextPick;
    // the pretend world: no world = every placement / footprint / terrain is null and build fails
    public boolean inWorld = true;
    public String dimension = "minecraft:overworld";
    public int feetX = 100;
    public int feetY = 64;
    public int feetZ = -40;
    // how many building() calls stay PARSING after a build, and what it turns into after that (null = STARTED)
    public int parsePolls = 1;
    public String buildFailsLater;

    private final Map<String, Placement> placements = new LinkedHashMap<>();
    private Building building = Building.IDLE;
    private int parsing;

    private int pending;

    public FakeSchematics(List<String> actions) {
        this.actions = actions;
        row("Small Castle", "Small Castle.litematic", 24, 18, 24, 5230, "Litematica", 6, 48_211, null, null);
        row("tower", "tower.schem", 9, 40, 9, 2310, "Sponge", 3, 9_120, null,
            "12 blocks unrecognized: minecraft:mangrove_vibes (10), minecraft:weird_slab (2)");
        row("broken", "broken.litematic", 0, 0, 0, 0, "Litematica", 0, 120, "EOFException: unexpected end of file", null);
        row("farm", "villages/farm.schematic", 15, 6, 12, 640, "MCEdit", 0, 1_904, null, null);
        have.put("minecraft:oak_log", 40);
        have.put("minecraft:glass", 3);
        have.put("minecraft:torch", 64);
        materials.put("Small Castle.litematic", castle());
        materials.put("tower.schem", simple("tower.schem"));
        materials.put("villages/farm.schematic", simple("villages/farm.schematic"));
    }

    private void row(String name, String path, int x, int y, int z, long blocks, String format, int version, long size,
                     String error, String unknown) {
        rows.add(new Row(name, path, x, y, z, blocks, format, version, size, error, unknown));
    }

    private static Line need(String id, String name, int needed, Kind kind, String note, boolean gettable) {
        return new Line(id, id, name, needed, 0, kind, note, gettable);
    }

    // what the list says before the player's inventory is applied: have is zero everywhere
    private static Materials castle() {
        return new Materials(State.READY, "Small Castle.litematic", List.of(
            need("minecraft:stone_bricks", "Stone Bricks", 3120, Kind.ITEM, "", true),
            need("minecraft:oak_log", "Oak Log", 212, Kind.ITEM, "", true),
            need("minecraft:glass", "Glass", 96, Kind.ITEM, "", true),
            need("minecraft:oak_stairs", "Oak Stairs", 88, Kind.ITEM, "", true),
            need("minecraft:torch", "Torch", 24, Kind.ITEM, "wall and standing", true),
            need("minecraft:farmland", "Farmland", 16, Kind.ITEM, "no survival item, till dirt with a hoe", false),
            need("minecraft:beacon", "Beacon", 1, Kind.ITEM, "", false)
        ), List.of(
            new Line("minecraft:fire", "minecraft:flint_and_steel", "Fire", 3, 0, Kind.CANT_PLACE,
                "light it with flint and steel", false)
        ), List.of(
            new Line("minecraft:water", "minecraft:water_bucket", "Water", 40, 0, Kind.NOT_COUNTED,
                "source blocks, 6 flowing not counted", false)
        ), List.of("entities (item frames, paintings, armor stands) are not counted"), null);
    }

    private static Materials simple(String path) {
        return new Materials(State.READY, path, List.of(
            need("minecraft:cobblestone", "Cobblestone", 900, Kind.ITEM, "", true),
            need("minecraft:oak_planks", "Oak Planks", 410, Kind.ITEM, "", true)
        ), List.of(), List.of(), List.of("entities (item frames, paintings, armor stands) are not counted"), null);
    }

    private boolean stillLoading() {
        if (loadingForever) {
            return true;
        }
        if (pending > 0) {
            pending--;
            return true;
        }
        return false;
    }

    @Override
    public Listing list() {
        return new Listing(stillLoading() ? State.LOADING : State.READY, List.copyOf(rows));
    }

    @Override
    public void refresh() {
        actions.add("schematics refresh");
        pending = loadingPolls;
    }

    @Override
    public Materials materials(String relativePath) {
        Materials stored = materials.get(relativePath);
        if (stored == null) {
            boolean known = rows.stream().anyMatch(r -> r.relativePath().equals(relativePath) && r.ok());
            return new Materials(State.READY, relativePath, List.of(), List.of(), List.of(), List.of(),
                known ? "Couldn't read this file" : "Not in the library, refresh the list");
        }
        if (stillLoading()) {
            return Materials.loading(relativePath);
        }
        List<Line> items = new ArrayList<>();
        for (Line line : stored.items()) {
            items.add(new Line(line.itemId(), line.iconItemId(), line.displayName(), line.needed(),
                have.getOrDefault(line.itemId(), 0), line.kind(), line.note(), line.gettable()));
        }
        return new Materials(State.READY, relativePath, List.copyOf(items), stored.cantPlace(), stored.notCounted(),
            stored.notes(), null);
    }

    // ----- placements, the same rules as SchematicPlacement with plain ints so the fake stays minecraft free

    private Row okRow(String relativePath) {
        return rows.stream().filter(r -> r.relativePath().equals(relativePath) && r.ok()).findFirst().orElse(null);
    }

    @Override
    public Placement placement(String relativePath) {
        if (!inWorld || okRow(relativePath) == null) {
            return null;
        }
        Placement p = placements.get(relativePath);
        if (p == null) {
            // the real one doesn't save this default, the fake doesn't care
            p = new Placement(feetX, feetY, feetZ, 0, false, dimension, true);
            placements.put(relativePath, p);
        }
        return new Placement(p.x(), p.y(), p.z(), p.turns(), p.mirrored(), p.dimension(), p.dimension().equals(dimension));
    }

    private Placement put(String relativePath, Placement p) {
        placements.put(relativePath, p);
        return placement(relativePath);
    }

    @Override
    public Placement move(String relativePath, int dx, int dy, int dz) {
        Placement p = placement(relativePath);
        if (p == null) {
            return null;
        }
        actions.add("schematics move " + relativePath + " " + dx + " " + dy + " " + dz);
        return put(relativePath, new Placement(p.x() + dx, p.y() + dy, p.z() + dz, p.turns(), p.mirrored(), p.dimension(), true));
    }

    @Override
    public Placement rotate(String relativePath, boolean clockwise) {
        Placement p = placement(relativePath);
        if (p == null) {
            return null;
        }
        actions.add("schematics rotate " + relativePath + (clockwise ? " cw" : " ccw"));
        Row row = okRow(relativePath);
        int turns = Math.floorMod(p.turns() + (clockwise ? 1 : -1), 4);
        int beforeX = p.turns() % 2 == 0 ? row.sizeX() : row.sizeZ();
        int beforeZ = p.turns() % 2 == 0 ? row.sizeZ() : row.sizeX();
        // a quarter turn swaps the sizes, the corner moves half the difference so the middle stays put
        int dx = (beforeX - beforeZ) / 2;
        int dz = (beforeZ - beforeX) / 2;
        return put(relativePath, new Placement(p.x() + dx, p.y(), p.z() + dz, turns, p.mirrored(), p.dimension(), true));
    }

    @Override
    public Placement mirror(String relativePath) {
        Placement p = placement(relativePath);
        if (p == null) {
            return null;
        }
        actions.add("schematics mirror " + relativePath);
        return put(relativePath, new Placement(p.x(), p.y(), p.z(), (4 - p.turns()) % 4, !p.mirrored(), p.dimension(), true));
    }

    @Override
    public Placement placeAtFeet(String relativePath) {
        Placement p = placement(relativePath);
        if (p == null) {
            return null;
        }
        actions.add("schematics feet " + relativePath);
        return put(relativePath, new Placement(feetX, feetY, feetZ, p.turns(), p.mirrored(), dimension, true));
    }

    @Override
    public Footprint footprint(String relativePath) {
        Placement p = placement(relativePath);
        if (p == null) {
            return null;
        }
        Row row = okRow(relativePath);
        boolean swap = p.turns() % 2 == 1;
        return new Footprint(p.x(), p.y(), p.z(), swap ? row.sizeZ() : row.sizeX(), row.sizeY(), swap ? row.sizeX() : row.sizeZ());
    }

    @Override
    public Result build(String relativePath) {
        if (buildError != null) {
            return Result.fail(buildError);
        }
        if (!inWorld) {
            return Result.fail("Join a world first");
        }
        if (rows.stream().noneMatch(r -> r.relativePath().equals(relativePath))) {
            return Result.fail("Can't find that schematic, refresh the list");
        }
        Placement p = placement(relativePath);
        if (p == null) {
            return Result.fail("That schematic didn't load");
        }
        if (!p.inThisDimension()) {
            return Result.fail("It's placed in another dimension, move it here first");
        }
        actions.add("schematics build " + relativePath);
        building = new Building(BuildState.PARSING, relativePath, "Reading " + relativePath);
        parsing = parsePolls;
        return Result.ok("Building " + relativePath + " at " + p.x() + ", " + p.y() + ", " + p.z());
    }

    @Override
    public Building building() {
        if (building.state() == BuildState.PARSING) {
            if (parsing > 0) {
                parsing--;
            } else {
                building = buildFailsLater != null
                    ? new Building(BuildState.ERROR, building.relativePath(), buildFailsLater)
                    : new Building(BuildState.STARTED, building.relativePath(), "Building " + building.relativePath());
            }
        }
        return building;
    }

    // a made-up landscape: grass, a river running north to south with sand banks, a stone hill, a few trees. the two
    // east chunk columns of the three north rows are "not loaded", so a preview shows the edge too
    @Override
    public Terrain terrain(int centerX, int centerZ, int radiusChunks) {
        if (!inWorld) {
            return null;
        }
        int r = Math.max(1, Math.min(Terrain.MAX_RADIUS_CHUNKS, radiusChunks));
        int minX = (((centerX + 8) >> 4) - r) << 4;
        int minZ = (((centerZ + 8) >> 4) - r) << 4;
        int span = 2 * r;
        int size = span << 4;
        int[] argb = new int[size * size];
        int loaded = 0;
        for (int cz = 0; cz < span; cz++) {
            for (int cx = 0; cx < span; cx++) {
                if (cx >= span - 2 && cz < 3) {
                    continue;
                }
                loaded++;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        argb[((cz << 4) + z) * size + (cx << 4) + x] = fakeColumn(minX + (cx << 4) + x, minZ + (cz << 4) + z);
                    }
                }
            }
        }
        return new Terrain(minX, minZ, size, argb, loaded, span * span, 1);
    }

    private static int fakeColumn(int x, int z) {
        int river = Math.abs(x - 60 - (int) (Math.sin(z / 23.0) * 18));
        if (river < 5) {
            return ((x + z) & 1) == 0 ? 0xFF3F76E4 : 0xFF3A6CD2;
        }
        if (river < 7) {
            return 0xFFF7E9A3;
        }
        int hx = x - 150;
        int hz = z + 10;
        if (hx * hx + hz * hz < 30 * 30) {
            return Math.floorMod(hx + hz, 7) == 0 ? 0xFF8A8A8A : 0xFF707070;
        }
        if (Math.floorMod(x * 31 + z * 17, 97) == 0) {
            return 0xFF007C00;
        }
        return ((x ^ z) & 3) == 0 ? 0xFF7FB238 : 0xFF6E9A30;
    }

    @Override
    public void importFiles(Consumer<ImportReport> done) {
        actions.add("schematics pick");
        ImportReport report = nextPick;
        if (report == null) {
            report = dropInto(List.of(Path.of("picked.litematic")));
        } else if (!report.imported().isEmpty()) {
            for (String name : report.imported()) {
                add(name);
            }
        }
        done.accept(report);
    }

    @Override
    public ImportReport importDropped(List<Path> files) {
        actions.add("schematics drop " + files.size());
        return dropInto(files);
    }

    // the real copy rules live in SchematicImporter and have their own tests. this only needs the shape: extension check,
    // "name (2).ext" on a clash, one list row per import
    private ImportReport dropInto(List<Path> files) {
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!SchematicImporter.isSchematic(file)) {
                skipped.add(name + ": not a schematic");
                continue;
            }
            String path = name;
            int dot = name.lastIndexOf('.');
            for (int n = 2; has(path); n++) {
                path = name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
            }
            add(path);
            imported.add(path);
        }
        return new ImportReport(imported, skipped, List.of(), false);
    }

    private boolean has(String path) {
        return rows.stream().anyMatch(r -> r.relativePath().equalsIgnoreCase(path));
    }

    private void add(String path) {
        int dot = path.lastIndexOf('.');
        row(path.substring(0, dot), path, 10, 10, 10, 100, "Litematica", 6, 2_048, null, null);
        materials.put(path, simple(path));
    }

    // a file deleted behind the gui's back, and a folder with nothing in it, for the tab tests
    public void remove(String relativePath) {
        rows.removeIf(r -> r.relativePath().equals(relativePath));
        materials.remove(relativePath);
        placements.remove(relativePath);
    }

    // the row stays, the count goes: materials() then answers with the unreadable file error
    public void forgetMaterials(String relativePath) {
        materials.remove(relativePath);
    }

    public void clear() {
        rows.clear();
        materials.clear();
        placements.clear();
    }

    @Override
    public Result openFolder() {
        actions.add("schematics folder");
        return Result.ok("Opened the schematics folder");
    }

    @Override
    public String folderPath() {
        return ".minecraft/schematics";
    }

    // same lifecycle as McSchematics: kept until changed, gone without a world
    private String ghost;

    @Override
    public void setGhost(String relativePath) {
        ghost = relativePath;
    }

    @Override
    public String ghost() {
        if (!inWorld) {
            ghost = null;
        }
        return ghost;
    }
}
