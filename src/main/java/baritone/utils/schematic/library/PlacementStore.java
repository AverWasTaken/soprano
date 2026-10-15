package baritone.utils.schematic.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

// <save>/altoclef/schematic-placements.json, one placement per library path. same load/save shape as RunStateStore:
// plain json a human can read, tmp file then move, and nothing here throws. only takes a Path so tests use a temp dir
public final class PlacementStore {

    public static final String FILE_NAME = "schematic-placements.json";

    private static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // what's on disk. plain fields and enum names, no minecraft types, so gson needs no adapters
    private static final class FileData {
        int schema = SCHEMA;
        Map<String, Entry> placements = new LinkedHashMap<>();
    }

    private static final class Entry {
        // boxed so a missing one is null, not a silent 0
        Integer x;
        Integer y;
        Integer z;
        String rotation;
        String mirror;
        String dimension;
    }

    private PlacementStore() {
    }

    // every usable entry whose file still exists. a broken file is moved aside and reads as empty, a broken entry (a
    // typo'd number, a missing coordinate, a turn we don't know) is dropped on its own. exists gets the relative path,
    // so a renamed or deleted schematic stops haunting the file. null = the file is there but couldn't be read right
    // now (locked by a virus scanner, say): the caller must not save over it, that would wipe every other placement
    public static Map<String, SchematicPlacement> load(Path file, Predicate<String> exists) {
        Map<String, SchematicPlacement> out = new LinkedHashMap<>();
        if (file == null || !Files.exists(file)) {
            return out;
        }
        if (!Files.isRegularFile(file)) {
            // a folder (or something stranger) squatting on the name. not ours to delete, and a save couldn't land anyway
            return null;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // it read fine, it's just not text. that's corrupt, not locked
            moveAside(file);
            return out;
        } catch (IOException e) {
            System.out.println("schematic placements: could not read " + file + " (" + e.getMessage() + ")");
            return null;
        }
        JsonObject placements = placementsOf(text);
        if (placements == null) {
            moveAside(file);
            return out;
        }
        for (Map.Entry<String, JsonElement> e : placements.entrySet()) {
            SchematicPlacement p = parse(e.getValue());
            if (p != null && safeTest(exists, e.getKey())) {
                out.put(e.getKey(), p);
            }
        }
        return out;
    }

    // the placements object, or null for a file that's corrupt, empty, or from a newer soprano (which might know
    // things we'd throw away on the next save)
    private static JsonObject placementsOf(String text) {
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            JsonElement schema = obj.get("schema");
            if (schema != null && (!schema.isJsonPrimitive() || schema.getAsInt() > SCHEMA)) {
                return null;
            }
            JsonElement placements = obj.get("placements");
            return placements != null && placements.isJsonObject() ? placements.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static boolean safeTest(Predicate<String> exists, String path) {
        try {
            return exists.test(path);
        } catch (RuntimeException e) {
            // a path windows can't even make sense of is as gone as a deleted one
            return false;
        }
    }

    private static SchematicPlacement parse(JsonElement json) {
        Entry e;
        try {
            e = GSON.fromJson(json, Entry.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e2) {
            return null;
        }
        if (e == null || e.x == null || e.y == null || e.z == null || e.dimension == null || e.dimension.isEmpty()) {
            return null;
        }
        Rotation rotation = enumOr(Rotation.class, e.rotation, Rotation.NONE);
        Mirror mirror = enumOr(Mirror.class, e.mirror, Mirror.NONE);
        if (rotation == null || mirror == null) {
            return null;
        }
        return new SchematicPlacement(new BlockPos(e.x, e.y, e.z), rotation, mirror, e.dimension);
    }

    // missing = the default, a name we don't know = null (the entry is dropped rather than built the wrong way round)
    private static <T extends Enum<T>> T enumOr(Class<T> type, String name, T missing) {
        if (name == null) {
            return missing;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String toJson(Map<String, SchematicPlacement> placements) {
        FileData data = new FileData();
        placements.forEach((path, p) -> {
            Entry e = new Entry();
            e.x = p.origin().getX();
            e.y = p.origin().getY();
            e.z = p.origin().getZ();
            e.rotation = p.rotation().name();
            e.mirror = p.mirror().name();
            e.dimension = p.dimension();
            data.placements.put(path, e);
        });
        return GSON.toJson(data);
    }

    // tmp file then move, a crash halfway leaves the old file instead of half a new one
    public static boolean save(Path file, Map<String, SchematicPlacement> placements) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, toJson(placements), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.out.println("schematic placements: could not save " + file + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private static void moveAside(Path file) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        try {
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("schematic placements: " + file + " is unusable, moved it to " + bad.getFileName());
        } catch (IOException e) {
            System.out.println("schematic placements: " + file + " is unusable and could not be moved aside (" + e.getMessage() + ")");
        }
    }
}
