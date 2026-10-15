package baritone.utils.schematic.library;

import baritone.utils.schematic.SchematicSystem;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// copies schematic files into the library folder for the file picker and for drag and drop. never throws: whatever goes
// wrong with one file lands in the report and the rest still get their turn
public final class SchematicImporter {

    // imported are the library paths the files ended up at ("castle (2).litematic"), skipped are files we looked at and
    // left alone, errors are files we wanted and couldn't copy. each line says which file, so a toast can print them
    public record Report(List<String> imported, List<String> skipped, List<String> errors) {

        public boolean nothingHappened() {
            return imported.isEmpty() && skipped.isEmpty() && errors.isEmpty();
        }
    }

    private final Path directory;

    public SchematicImporter(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Report importAll(List<Path> sources) {
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (sources != null) {
            for (Path source : sources) {
                try {
                    one(source, imported, skipped, errors);
                } catch (Throwable t) {
                    errors.add(label(source) + ": " + reason(t));
                }
            }
        }
        return new Report(List.copyOf(imported), List.copyOf(skipped), List.copyOf(errors));
    }

    private void one(Path source, List<String> imported, List<String> skipped, List<String> errors) throws IOException {
        if (source == null) {
            return;
        }
        if (Files.isDirectory(source)) {
            // a dropped folder keeps its shape, so a pack of 40 builds doesn't get flattened into one pile
            folder(source, source.getFileName() == null ? "" : source.getFileName().toString(), imported, skipped, errors);
            return;
        }
        file(source, "", imported, skipped, errors);
    }

    private void folder(Path root, String prefix, List<String> imported, List<String> skipped, List<String> errors) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            try {
                Path parent = root.relativize(file).getParent();
                String sub = parent == null ? prefix : join(prefix, parent.toString().replace('\\', '/'));
                // a folder that has other stuff in it isn't an error, just not ours
                if (!isSchematic(file)) {
                    continue;
                }
                file(file, sub, imported, skipped, errors);
            } catch (Throwable t) {
                errors.add(label(file) + ": " + reason(t));
            }
        }
        if (files.stream().noneMatch(SchematicImporter::isSchematic)) {
            skipped.add(label(root) + ": no schematics in this folder");
        }
    }

    private void file(Path source, String subFolder, List<String> imported, List<String> skipped, List<String> errors) throws IOException {
        String name = source.getFileName().toString();
        if (!isSchematic(source)) {
            skipped.add(name + ": not a schematic (" + extensionHint() + ")");
            return;
        }
        if (!Files.isRegularFile(source)) {
            errors.add(name + ": file not found");
            return;
        }
        if (source.toAbsolutePath().normalize().startsWith(directory)) {
            skipped.add(name + ": already in the library");
            return;
        }
        Path targetDir = subFolder.isEmpty() ? directory : directory.resolve(subFolder).normalize();
        // a folder called ".." from a weird drop must not walk out of the library
        if (!targetDir.startsWith(directory)) {
            errors.add(name + ": folder name not allowed");
            return;
        }
        Files.createDirectories(targetDir);
        Path target = copyUnique(source, targetDir);
        imported.add(directory.relativize(target).toString().replace('\\', '/'));
    }

    // "castle.litematic" -> "castle (2).litematic" -> "castle (3).litematic". the copy itself is the existence check so
    // two imports racing for the same name can't overwrite each other
    private static Path copyUnique(Path source, Path targetDir) throws IOException {
        String name = source.getFileName().toString();
        String ext = FilenameUtils.getExtension(name);
        String base = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
        for (int n = 1; n < 10_000; n++) {
            String candidate = n == 1 ? name : base + " (" + n + ")" + (ext.isEmpty() ? "" : "." + ext);
            Path target = targetDir.resolve(candidate);
            try {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                return target;
            } catch (FileAlreadyExistsException e) {
                // next number
            }
        }
        throw new IOException("too many files with that name");
    }

    // same test the library scan uses, so anything that gets imported also shows up in the list
    public static boolean isSchematic(Path path) {
        Path name = path.getFileName();
        return name != null && SchematicSystem.INSTANCE.getByFile(path.toFile()).isPresent();
    }

    // "*.litematic, *.schem, *.schematic" for error text and the file picker's filter
    public static List<String> extensions() {
        List<String> out = new ArrayList<>();
        for (String ext : SchematicSystem.INSTANCE.getFileExtensions()) {
            String lower = ext.toLowerCase(Locale.ROOT);
            if (!out.contains(lower)) {
                out.add(lower);
            }
        }
        return out;
    }

    private static String extensionHint() {
        return String.join(", ", extensions().stream().map(e -> "." + e).toList());
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + "/" + b;
    }

    private static String label(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static String reason(Throwable t) {
        String message = t.getMessage();
        if (t instanceof java.nio.file.NoSuchFileException) {
            return "file not found";
        }
        if (t instanceof java.nio.file.AccessDeniedException) {
            return "access denied";
        }
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
