package adris.altoclef.commands.graph;

import adris.altoclef.commands.CommandsStore;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.util.helpers.ConfigHelper;
import baritone.api.BaritoneAPI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// configs/routines/<id>.json, one graph per file. the same rules CommandsStore has for CustomTasks.json: reading never
// writes, a bad file is left alone until a person fixes it, every save is temp + atomic, and a file changed behind our
// back gets read again instead of written over
public final class GraphStore {

    static final String DIR = "configs/routines";

    // the list routines, the other half of "ids are unique". CommandsStore outside of tests
    public interface Linear {
        boolean has(String id);

        boolean remove(String id);
    }

    // one file. graph is null when the file is bad, error says why
    public record Entry(String id, RoutineGraph graph, String error) {
    }

    // saved with a note = the graph is in but something else didn't go through, ok stays true
    public record Result(boolean ok, String error, String id) {
        static Result fail(String why) {
            return new Result(false, why, null);
        }
    }

    private static final Pattern WINDOWS_NAMES = Pattern.compile("(con|prn|aux|nul|com\\d|lpt\\d)", Pattern.CASE_INSENSITIVE);

    private static GraphStore shared;

    // the folder is asked for every time, same reason as CommandsStore: no game dir yet when this class loads
    private final Supplier<Path> dir;
    private final Linear linear;
    private final Map<String, Loaded> loaded = new HashMap<>();
    // what each file was when we last wrote it or first saw it. a refresh doesn't move this, see CommandsStore
    private final Map<String, byte[]> baselines = new HashMap<>();

    private record Loaded(String id, FileTime time, long size, byte[] bytes, Entry entry) {
    }

    GraphStore(Supplier<Path> dir, Linear linear) {
        this.dir = dir;
        this.linear = linear;
    }

    public static synchronized GraphStore get() {
        if (shared == null) {
            shared = new GraphStore(() -> ConfigHelper.getConfigFolder().resolve(DIR), new Linear() {
                @Override
                public boolean has(String id) {
                    return CommandsStore.get().customLines(id) != null;
                }

                @Override
                public boolean remove(String id) {
                    return CommandsStore.get().delete(id);
                }
            });
        }
        return shared;
    }

    // the api setting that gates all of this. off = nothing in here gets looked at
    public static boolean enabled() {
        try {
            return BaritoneAPI.getSettings().experimentalRoutineGraphs.value;
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized List<Entry> list() {
        refresh();
        List<Entry> out = new ArrayList<>();
        for (Loaded l : loaded.values()) {
            out.add(l.entry());
        }
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return out;
    }

    // a bad file comes back too (graph null), so #custom can say what's wrong with it instead of "no such routine"
    public synchronized Entry find(String id) {
        if (id == null) {
            return null;
        }
        refresh();
        Loaded l = loaded.get(key(id));
        return l == null ? null : l.entry();
    }

    public synchronized boolean exists(String id) {
        return find(id) != null;
    }

    // previousId = the id it had when the editor opened it, a list routine's id for an upgrade, null for a new one.
    // a half built graph saves fine, only a run needs it valid. the graph goes in first and the old one goes after, so
    // whatever goes wrong halfway there's a routine left (#custom checks graphs first, a leftover list entry is harmless)
    public synchronized Result save(RoutineGraph graph, String previousId) {
        if (graph == null) {
            return Result.fail("nothing to save");
        }
        refresh();
        String id = RoutineLines.idFor(graph.name());
        if (id == null) {
            return Result.fail("give it a name with a letter or a number in it");
        }
        if (WINDOWS_NAMES.matcher(id).matches()) {
            return Result.fail("windows can't have a file called " + id + ", pick another name");
        }
        boolean sameFile = previousId != null && previousId.equalsIgnoreCase(id);
        Loaded previous = previousId == null ? null : loaded.get(key(previousId));
        // a case only rename keeps the file it has, the rename would be a delete of what we just wrote on windows
        if (sameFile && previous != null) {
            id = previous.id();
        }
        Loaded here = loaded.get(key(id));
        if (here != null && !sameFile) {
            return Result.fail("there is already one called " + here.id());
        }
        if (!sameFile && linear.has(id)) {
            return Result.fail("there is already one called " + id);
        }
        for (Loaded check : new Loaded[]{here, previous}) {
            Result blocked = check == null ? null : writeBlocked(check);
            if (blocked != null) {
                return blocked;
            }
        }
        if (here == null && changedOnDisk(id)) {
            baselines.remove(key(id));
            return Result.fail(id + ".json changed on disk, check it and save again");
        }
        RoutineGraph clean = new RoutineGraph(id, graph.name().trim().replaceAll("\\s+", " "), graph.description().trim(), graph.nodes(), graph.edges());
        String text;
        try {
            text = GraphFile.write(clean);
            // never write what we couldn't read back, the next load would call our own file bad
            GraphFile.parse(id, text);
        } catch (GraphFile.BadFile | RuntimeException e) {
            return Result.fail("that graph can't be saved: " + e.getMessage());
        }
        String failure = writeFile(id, text);
        if (failure != null) {
            return Result.fail(failure);
        }
        refresh();
        return new Result(true, cleanUpAfterSave(id, previousId, previous), id);
    }

    // null when it all went through, else a note for the editor. the graph is saved either way
    private String cleanUpAfterSave(String id, String previousId, Loaded previous) {
        if (previousId == null) {
            return null;
        }
        if (previous != null && !previousId.equalsIgnoreCase(id) && !deleteFile(previous.id())) {
            return "saved, but couldn't remove " + previous.id() + ".json";
        }
        if (linear.has(previousId) && !linear.remove(previousId)) {
            return "saved, but the old list entry is still in CustomTasks.json";
        }
        return null;
    }

    public synchronized boolean delete(String id) {
        if (id == null) {
            return false;
        }
        refresh();
        Loaded l = loaded.get(key(id));
        return l != null && writeBlocked(l) == null && deleteFile(l.id());
    }

    private boolean deleteFile(String id) {
        try {
            Files.deleteIfExists(pathOf(id));
            refresh();
            baselines.remove(key(id));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // null when this file may be written over or deleted
    private Result writeBlocked(Loaded l) {
        if (changedOnDisk(l.id())) {
            refresh();
            Loaded now = loaded.get(key(l.id()));
            if (now == null) {
                baselines.remove(key(l.id()));
            } else {
                baselines.put(key(l.id()), now.bytes());
            }
            return Result.fail(l.id() + ".json changed on disk, it was read again. check it and save again");
        }
        if (l.entry().graph() == null) {
            return Result.fail(l.id() + ".json has a problem, fix it by hand first: " + l.entry().error());
        }
        return null;
    }

    // a byte compare and not the timestamp, same as CommandsStore
    private boolean changedOnDisk(String id) {
        byte[] baseline = baselines.get(key(id));
        if (baseline == null) {
            return false;
        }
        Path p = pathOf(id);
        try {
            return !Files.exists(p) || !Arrays.equals(Files.readAllBytes(p), baseline);
        } catch (IOException e) {
            return true;
        }
    }

    private String writeFile(String id, String text) {
        Path p = pathOf(id);
        Path tmp = null;
        try {
            Path folder = p.toAbsolutePath().getParent();
            Files.createDirectories(folder);
            tmp = Files.createTempFile(folder, "graph", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            baselines.put(key(id), text.getBytes(StandardCharsets.UTF_8));
            // same second, same length would look unchanged to refresh
            loaded.remove(key(id));
            return null;
        } catch (AtomicMoveNotSupportedException e) {
            return "couldn't save " + id + ".json: this folder can't replace a file in one go, nothing was changed";
        } catch (IOException | RuntimeException e) {
            return "couldn't save " + id + ".json: " + e.getMessage();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a stray temp file is not worth a second error
                }
            }
        }
    }

    private Path pathOf(String id) {
        return dir.get().resolve(id + ".json");
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    // the folder is walked every time, a file dropped in while the game runs shows up. only what changed gets parsed
    private void refresh() {
        Set<String> present = new HashSet<>();
        Path folder = dir.get();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
                for (Path p : files) {
                    String name = p.getFileName().toString();
                    String id = name.substring(0, name.length() - ".json".length());
                    present.add(key(id));
                    refreshOne(p, id);
                }
            } catch (IOException | DirectoryIteratorException e) {
                // a folder we can't list this time. what we knew stays, the next look tries again
                return;
            }
        }
        loaded.keySet().retainAll(present);
    }

    private void refreshOne(Path p, String id) {
        String k = key(id);
        Loaded old = loaded.get(k);
        try {
            FileTime time = Files.getLastModifiedTime(p);
            long size = Files.size(p);
            if (old != null && old.time().equals(time) && old.size() == size) {
                return;
            }
            byte[] bytes = Files.readAllBytes(p);
            loaded.put(k, new Loaded(id, time, size, bytes, parseEntry(id, bytes)));
            baselines.putIfAbsent(k, bytes);
        } catch (NoSuchFileException e) {
            loaded.remove(k);
        } catch (IOException e) {
            // unreadable is not empty: nothing gets written over a file we couldn't look at
            loaded.put(k, new Loaded(id, FileTime.fromMillis(0), -1, new byte[0], new Entry(id, null, "couldn't read it: " + e.getMessage())));
        }
    }

    private static Entry parseEntry(String id, byte[] bytes) {
        if (!RoutineLines.isId(id)) {
            return new Entry(id, null, "a file name here is one word of letters, numbers, _ and -");
        }
        try {
            return new Entry(id, GraphFile.parse(id, new String(bytes, StandardCharsets.UTF_8)), null);
        } catch (GraphFile.BadFile e) {
            return new Entry(id, null, e.getMessage());
        }
    }
}
