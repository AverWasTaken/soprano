package adris.altoclef.commands;

import adris.altoclef.util.helpers.ConfigHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// CustomTasks.json, for #custom and the commands tab both. not ConfigHelper: its load writes the file back every time
// and a parse error hands out the defaults, so the first save after a stray comma would wipe the user's file. here a
// bad file is left alone, read only, until a person fixes it
public final class CommandsStore {

    public static final int VERSION = 1;
    static final String PATH = "configs/CustomTasks.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ok = the tab may edit. routines = every entry that is fine (a bad file still runs its good ones)
    public record Snapshot(boolean ok, String error, List<StoredRoutine> routines) {
        public Snapshot {
            routines = List.copyOf(routines);
        }
    }

    public record Result(boolean ok, String error) {
        static Result done() {
            return new Result(true, null);
        }

        static Result fail(String why) {
            return new Result(false, why);
        }
    }

    // what a read of the file came to. lines is #custom's view: every entry with a name, even one the tab can't edit,
    // so a file that worked before the tab existed keeps working the way it did
    record Parsed(Snapshot snapshot, boolean legacy, String prefix, Map<String, List<String>> lines) {
    }

    private static CommandsStore shared;

    // the folder is asked for every time, the game dir doesn't exist when this class loads
    private final Supplier<Path> file;

    private boolean loaded;
    private boolean readExists;
    private FileTime readTime;
    private long readSize = -1;
    private byte[] readBytes;
    private Parsed parsed;
    // what the file was when we last wrote it (or first read it, or turned a save down over it). ordinary reads don't
    // move this: the tab refreshing its list must not make a hand edit look like something it already knew about
    private boolean baselineSet;
    private boolean baselineExists;
    private byte[] baselineBytes;

    CommandsStore(Supplier<Path> file) {
        this.file = file;
    }

    public static synchronized CommandsStore get() {
        if (shared == null) {
            shared = new CommandsStore(() -> ConfigHelper.getConfigFolder().resolve(PATH));
        }
        return shared;
    }

    public synchronized Snapshot snapshot() {
        refresh();
        return parsed.snapshot();
    }

    public synchronized List<StoredRoutine> list() {
        return snapshot().routines();
    }

    public synchronized StoredRoutine find(String id) {
        for (StoredRoutine r : list()) {
            if (r.id().equalsIgnoreCase(id)) {
                return r;
            }
        }
        return null;
    }

    // what #custom <id> runs, null when there is no such entry
    public synchronized List<String> customLines(String id) {
        refresh();
        for (Map.Entry<String, List<String>> e : parsed.lines().entrySet()) {
            if (e.getKey().equalsIgnoreCase(id)) {
                return e.getValue();
            }
        }
        return null;
    }

    public synchronized List<String> customIds() {
        refresh();
        return List.copyOf(parsed.lines().keySet());
    }

    // previousId = the id the routine had when the tab opened it (null for a new one). a different name is a rename:
    // new id, same spot in the list, the old one gone
    public synchronized Result save(StoredRoutine routine, String previousId) {
        if (routine == null) {
            return Result.fail("nothing to save");
        }
        Result blocked = writeBlocked();
        if (blocked != null) {
            return blocked;
        }
        String id = RoutineLines.idFor(routine.name());
        if (id == null) {
            return Result.fail("give it a name with a letter or a number in it");
        }
        for (int i = 0; i < routine.steps().size(); i++) {
            RoutineStep s = routine.steps().get(i);
            String why = s == null || s.kind() == null ? "this step has no type" : stepShape(s.kind(), s.line());
            if (why != null) {
                return Result.fail("step " + (i + 1) + ": " + why);
            }
        }
        if (routine.steps().size() > RoutineLines.MAX_STEPS) {
            return Result.fail("that is more than " + RoutineLines.MAX_STEPS + " steps");
        }
        List<StoredRoutine> all = new ArrayList<>(parsed.snapshot().routines());
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            String other = all.get(i).id();
            if (previousId != null && other.equalsIgnoreCase(previousId)) {
                at = i;
            } else if (other.equalsIgnoreCase(id)) {
                return Result.fail("there is already one called " + other);
            }
        }
        String name = routine.name().trim().replaceAll("\\s+", " ");
        String description = routine.description() == null ? "" : routine.description().trim();
        List<RoutineStep> steps = new ArrayList<>();
        for (RoutineStep s : routine.steps()) {
            steps.add(new RoutineStep(s.kind(), RoutineLines.normalize(s.line(), null)));
        }
        StoredRoutine clean = new StoredRoutine(id, name, description, steps);
        if (at >= 0) {
            all.set(at, clean);
        } else {
            all.add(clean);
        }
        return write(all);
    }

    public synchronized boolean delete(String id) {
        if (id == null || writeBlocked() != null) {
            return false;
        }
        List<StoredRoutine> all = new ArrayList<>(parsed.snapshot().routines());
        boolean removed = all.removeIf(r -> r.id().equalsIgnoreCase(id));
        return removed && write(all).ok();
    }

    // null when a write may go ahead. a bad file stays as it is, and a file somebody changed since we read it is read
    // again first (the save that would have gone over it is turned down, the next one is fine)
    private Result writeBlocked() {
        if (changedOnDisk()) {
            reload();
            markBaseline();
            return Result.fail("CustomTasks.json changed on disk, it was read again. check it and save again");
        }
        refresh();
        if (!parsed.snapshot().ok()) {
            return Result.fail("CustomTasks.json has a problem, fix it by hand first: " + parsed.snapshot().error());
        }
        return null;
    }

    // a byte compare and not the timestamp, an edit within the same second with the same length is still an edit
    private boolean changedOnDisk() {
        if (!baselineSet) {
            return false;
        }
        Path p = file.get();
        if (!Files.exists(p)) {
            return baselineExists;
        }
        try {
            byte[] now = Files.readAllBytes(p);
            return !baselineExists || !Arrays.equals(now, baselineBytes);
        } catch (IOException e) {
            return true;
        }
    }

    private void markBaseline() {
        baselineSet = true;
        baselineExists = readExists;
        baselineBytes = readBytes;
    }

    private Result write(List<StoredRoutine> routines) {
        Path p = file.get();
        String text = GSON.toJson(toConfig(routines, parsed.prefix())) + "\n";
        Path tmp = null;
        try {
            Path dir = p.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            // the first save over a file from before the tab migrates it, the original is kept in case that goes wrong
            if (readExists && parsed.legacy()) {
                Files.copy(p, p.resolveSibling(p.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = Files.createTempFile(dir, "CustomTasks", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            // atomic or not at all: a half written file is exactly what this store exists to never leave behind
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (AtomicMoveNotSupportedException e) {
            return Result.fail("couldn't save CustomTasks.json: this folder can't replace a file in one go, nothing was changed");
        } catch (IOException | RuntimeException e) {
            return Result.fail("couldn't save CustomTasks.json: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a stray temp file is not worth a second error
                }
            }
        }
        reload();
        markBaseline();
        return Result.done();
    }

    private static CustomTaskConfig toConfig(List<StoredRoutine> routines, String prefix) {
        CustomTaskConfig c = new CustomTaskConfig();
        c.version = VERSION;
        c.prefix = prefix;
        c.customTasks = new CustomTaskConfig.CustomTaskEntry[routines.size()];
        for (int i = 0; i < routines.size(); i++) {
            StoredRoutine r = routines.get(i);
            CustomTaskConfig.CustomTaskEntry e = new CustomTaskConfig.CustomTaskEntry();
            e.name = r.id();
            e.displayName = r.name().equals(r.id()) ? null : r.name();
            e.description = r.description().isEmpty() ? null : r.description();
            e.tasks = new CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry[r.steps().size()];
            for (int j = 0; j < r.steps().size(); j++) {
                RoutineStep s = r.steps().get(j);
                CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry sub = new CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry();
                sub.type = s.kind().name().toLowerCase(Locale.ROOT);
                sub.line = s.line();
                // the old spelling next to it, so an older soprano's #custom still runs the same line
                sub.command = RoutineLines.label(s.line());
                sub.parameters = RoutineLines.legacyParameters(s.line());
                e.tasks[j] = sub;
            }
            c.customTasks[i] = e;
        }
        return c;
    }

    private void refresh() {
        Path p = file.get();
        boolean exists = Files.exists(p);
        FileTime time = null;
        long size = -1;
        if (exists) {
            try {
                time = Files.getLastModifiedTime(p);
                size = Files.size(p);
            } catch (IOException e) {
                exists = false;
            }
        }
        if (!loaded || exists != readExists || size != readSize || (time != null && !time.equals(readTime))) {
            reload();
            if (!baselineSet) {
                markBaseline();
            }
        }
    }

    private void reload() {
        Path p = file.get();
        loaded = true;
        // exists first: a missing folder is NoSuchFile on windows but "not a directory" on linux
        if (!Files.exists(p)) {
            readExists = false;
            readBytes = null;
            readTime = null;
            readSize = -1;
            parsed = new Parsed(new Snapshot(true, null, List.of()), false, "custom2", Map.of());
            return;
        }
        try {
            readTime = Files.getLastModifiedTime(p);
            readSize = Files.size(p);
            readBytes = Files.readAllBytes(p);
            readExists = true;
        } catch (NoSuchFileException e) {
            readExists = false;
            readBytes = null;
            readTime = null;
            readSize = -1;
            parsed = new Parsed(new Snapshot(true, null, List.of()), false, "custom2", Map.of());
            return;
        } catch (IOException e) {
            // unreadable is not empty: nothing gets written over a file we couldn't look at
            readExists = true;
            readBytes = null;
            parsed = bad("couldn't read CustomTasks.json: " + e.getMessage());
            return;
        }
        parsed = parse(new String(readBytes, StandardCharsets.UTF_8));
    }

    private static Parsed bad(String why) {
        return new Parsed(new Snapshot(false, why, List.of()), false, "custom2", Map.of());
    }

    // the whole file, checked. pure so the tests can throw any text at it
    static Parsed parse(String text) {
        if (text.isBlank()) {
            // what the old loader did with an empty file too: nothing in it, nothing lost
            return new Parsed(new Snapshot(true, null, List.of()), true, "custom2", Map.of());
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return bad("CustomTasks.json is not valid json: " + e.getMessage());
        }
        if (!root.isJsonObject()) {
            return bad("CustomTasks.json should be one { ... } object");
        }
        JsonObject o = root.getAsJsonObject();
        JsonElement v = o.get("version");
        boolean legacy = v == null || v.isJsonNull();
        if (!legacy) {
            if (!(v instanceof JsonPrimitive prim) || !prim.isNumber() || prim.getAsDouble() != Math.floor(prim.getAsDouble())) {
                return bad("CustomTasks.json has a version that isn't a whole number");
            }
            int version = prim.getAsInt();
            if (version > VERSION) {
                return bad("CustomTasks.json is from a newer Soprano (version " + version + "), this one only knows " + VERSION);
            }
            if (version < 1) {
                return bad("CustomTasks.json has version " + version + ", which never existed");
            }
        }
        CustomTaskConfig config;
        try {
            config = GSON.fromJson(o, CustomTaskConfig.class);
        } catch (RuntimeException e) {
            return bad("CustomTasks.json doesn't have the right shape: " + e.getMessage());
        }
        String prefix = config.prefix == null ? "custom2" : config.prefix;
        CustomTaskConfig.CustomTaskEntry[] entries = config.customTasks == null ? new CustomTaskConfig.CustomTaskEntry[0] : config.customTasks;
        List<String> problems = new ArrayList<>();
        List<StoredRoutine> routines = new ArrayList<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.length; i++) {
            readEntry(entries[i], i, legacy, problems, routines, lines, aliases, seen);
        }
        // the names #custom always took win, the made up ones only fill in where nothing else answers
        aliases.forEach(lines::putIfAbsent);
        String error = null;
        if (!problems.isEmpty()) {
            error = problems.get(0) + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : "");
        }
        return new Parsed(new Snapshot(problems.isEmpty(), error, routines), legacy, prefix, Collections.unmodifiableMap(lines));
    }

    private static void readEntry(CustomTaskConfig.CustomTaskEntry e, int i, boolean legacy, List<String> problems,
                                  List<StoredRoutine> routines, Map<String, List<String>> lines,
                                  Map<String, List<String>> aliases, Set<String> seen) {
        if (e == null) {
            problems.add("entry " + (i + 1) + " is empty");
            return;
        }
        // a file from before the tab may have names with spaces or dots in them. they get an id out of the name and
        // keep the name for show, and #custom still answers to the old name
        String id = legacy ? RoutineLines.idFor(e.name) : e.name;
        String where = e.name == null ? "entry " + (i + 1) : "\"" + e.name + "\"";
        if (e.tasks == null) {
            problems.add(where + " has no tasks list");
            return;
        }
        List<String> raw = new ArrayList<>();
        List<RoutineStep> steps = new ArrayList<>();
        String problem = null;
        for (int j = 0; j < e.tasks.length; j++) {
            CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry sub = e.tasks[j];
            String line = subLine(sub);
            String why = line == null ? subProblem(sub) : null;
            if (line != null) {
                raw.add(line);
                RoutineStep.Kind kind = sub.type == null ? RoutineLines.kindOf(line) : RoutineLines.kindNamed(sub.type);
                why = kind == null ? "\"" + sub.type + "\" is not a step type" : stepShape(kind, line);
                if (why == null) {
                    steps.add(new RoutineStep(kind, line));
                }
            }
            if (why != null && problem == null) {
                problem = where + " step " + (j + 1) + ": " + why;
            }
        }
        if (problem == null && steps.size() > RoutineLines.MAX_STEPS) {
            problem = where + " has more than " + RoutineLines.MAX_STEPS + " steps";
        }
        // #custom keeps every entry whose lines could be built, the old executor never cared about the tab's rules. a
        // legacy entry answers to the name it always had, a migrated one to its display name (that's the old name)
        if (raw.size() == e.tasks.length) {
            List<String> built = List.copyOf(raw);
            String old = legacy ? e.name : e.displayName;
            if (legacy && old != null && !old.isBlank()) {
                lines.putIfAbsent(old.trim(), built);
            } else if (RoutineLines.isId(id)) {
                lines.putIfAbsent(id, built);
            }
            if (id != null && legacy) {
                aliases.putIfAbsent(id, built);
            }
            if (!legacy && old != null && !old.isBlank() && !old.trim().contains(" ")) {
                aliases.putIfAbsent(old.trim(), built);
            }
        }
        if (id == null || !RoutineLines.isId(id)) {
            problems.add(where + ": a name is one word of letters, numbers, _ and -");
            return;
        }
        if (!seen.add(id.toLowerCase(Locale.ROOT))) {
            problems.add("there are two called " + id);
            return;
        }
        if (problem != null) {
            problems.add(problem);
            return;
        }
        String name = e.displayName == null || e.displayName.isBlank() ? (legacy && e.name != null ? e.name.trim() : id) : e.displayName.trim();
        routines.add(new StoredRoutine(id, name, e.description == null ? "" : e.description, steps));
    }

    // the step's line: line wins, the legacy fields are the fallback, both there and saying different things is an error
    private static String subLine(CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry sub) {
        if (sub == null || subProblem(sub) != null) {
            return null;
        }
        String legacy = sub.command == null ? null : RoutineLines.legacyLine(sub.command, sub.parameters);
        if (sub.line != null && legacy != null && !RoutineLines.sameLine(sub.line, legacy)) {
            return null;
        }
        return RoutineLines.normalize(sub.line != null ? sub.line : legacy, null);
    }

    private static String subProblem(CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry sub) {
        if (sub == null) {
            return "is empty";
        }
        if (sub.line == null && (sub.command == null || sub.command.isBlank())) {
            return "has no command";
        }
        if (sub.parameters != null) {
            for (String[] row : sub.parameters) {
                if (row == null) {
                    return "has an empty parameters row";
                }
                for (String word : row) {
                    if (word == null) {
                        return "has a null in its parameters";
                    }
                }
            }
        }
        if (sub.line != null && sub.command != null && !RoutineLines.sameLine(sub.line, RoutineLines.legacyLine(sub.command, sub.parameters))) {
            return "line and command+parameters say different things, keep one";
        }
        return null;
    }

    // the store's half of the checks: what would break the file or the run, no game needed. a typed kind that isn't
    // the line's own kind is a mixup (raw takes anything)
    static String stepShape(RoutineStep.Kind kind, String line) {
        String normal = RoutineLines.normalize(line, null);
        String shape = RoutineLines.shapeProblem(normal);
        if (shape != null) {
            return shape;
        }
        // a typed goto that crosses dimensions is still a goto line, the editor's checks say "use a Raw step" for it.
        // turning the whole file down over it would be a lot
        boolean dimensionGoto = kind == RoutineStep.Kind.GOTO && RoutineLines.isDimensionGoto(normal);
        if (kind != RoutineStep.Kind.RAW && !dimensionGoto && RoutineLines.kindOf(normal) != kind) {
            return "a " + kind.name().toLowerCase(Locale.ROOT) + " step can't run \"" + RoutineLines.label(normal) + "\"";
        }
        return null;
    }
}
