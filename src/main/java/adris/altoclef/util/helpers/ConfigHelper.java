package adris.altoclef.util.helpers;

import adris.altoclef.Debug;
import adris.altoclef.util.serialization.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Helps load settings/configuration files
 */
public class ConfigHelper {

    // everything lives in <gameDir>/baritone/altoclef, next to baritone's own folder
    private static final String BARITONE_FOLDER = "baritone";
    private static final String ALTO_FOLDER = "altoclef";

    // One gson for everything. Item/BlockPos/ChunkPos/Vec3 adapters live here, so config classes need no annotations.
    // The adapters only touch the registry when they actually run, so this is fine to build early.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .registerTypeHierarchyAdapter(Item.class, new ItemSerializer())
            .registerTypeHierarchyAdapter(Item.class, new ItemDeserializer())
            // list version so a typo'd item gets skipped instead of leaving a null in the list
            .registerTypeAdapter(new TypeToken<List<Item>>() {}.getType(), new ItemDeserializer.ListOf())
            .registerTypeHierarchyAdapter(BlockPos.class, new BlockPosSerializer())
            .registerTypeHierarchyAdapter(BlockPos.class, new BlockPosDeserializer())
            .registerTypeAdapter(ChunkPos.class, new ChunkPosSerializer())
            .registerTypeAdapter(ChunkPos.class, new ChunkPosDeserializer())
            .registerTypeAdapter(Vec3.class, new Vec3dSerializer())
            .registerTypeAdapter(Vec3.class, new Vec3dDeserializer())
            .create();

    // For reloading
    private static final HashMap<String, Runnable> _loadedConfigs = new HashMap<>();

    // Lets a test point us somewhere that is not a minecraft game dir.
    static Path _folderOverride = null;

    /**
     * The folder all altoclef config files live in. Worked out on every call because the
     * game dir does not exist until Minecraft does.
     */
    public static Path getConfigFolder() {
        if (_folderOverride != null) {
            return _folderOverride;
        }
        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc != null ? mc.gameDirectory.toPath() : Path.of("");
        return gameDir.resolve(BARITONE_FOLDER).resolve(ALTO_FOLDER);
    }

    /**
     * Returns a File object representing the configuration file located at the given path.
     *
     * @param path The path of the configuration file, relative to the altoclef config folder.
     * @return The File object representing the configuration file.
     */
    private static File getConfigFile(String path) {
        return getConfigFolder().resolve(path).toFile();
    }

    /**
     * Reloads all configurations.
     */
    public static void reloadAllConfigs() {
        for (Runnable config : _loadedConfigs.values()) {
            config.run();
        }
    }

    /**
     * Retrieves the configuration from the specified path.
     * If the configuration file does not exist, it creates a new one using the default value.
     * If there is an error reading or parsing the configuration file, it returns the default value.
     *
     * @param path        The path to the configuration file.
     * @param getDefault  A supplier that provides the default value for the configuration.
     * @param classToLoad The class of the configuration object.
     * @param <T>         The type of the configuration object.
     * @return The retrieved configuration object or the default value.
     */
    private static <T> T getConfig(String path, Supplier<T> getDefault, Class<T> classToLoad) {
        T result = getDefault.get();
        File loadFrom = getConfigFile(path);
        if (!loadFrom.exists()) {
            saveConfig(path, result);
            return result;
        }

        // the supplier builds the top level object so missing keys keep their defaults
        Gson gson = GSON.newBuilder()
                .registerTypeAdapter(classToLoad, (InstanceCreator<T>) type -> getDefault.get())
                .create();

        try (Reader reader = Files.newBufferedReader(loadFrom.toPath(), StandardCharsets.UTF_8)) {
            T loaded = gson.fromJson(reader, classToLoad);
            if (loaded == null) {
                // gson hands back null for an empty file
                throw new IllegalStateException("Config file is empty.");
            }
            result = loaded;
        } catch (IOException e) {
            Debug.logError("Failed to read Config at " + path + ".");
            e.printStackTrace();
            if (result instanceof IFailableConfigFile failable)
                failable.onFailLoad();
            return result;
        } catch (RuntimeException ex) {
            // JsonParseException and friends, plus whatever a bad value manages to throw
            Debug.logError("Failed to parse Config file of type " + classToLoad.getSimpleName() + " at " + path + ". JSON Error Message: " + ex.getMessage() + ".\n JSON Error STACK TRACE:\n\n");
            ex.printStackTrace();
            if (result instanceof IFailableConfigFile failable)
                failable.onFailLoad();
            return result;
        }

        saveConfig(path, result);

        return result;
    }

    /**
     * Load the configuration from the given path, using the provided default value,
     * class to load, and callback function for when the configuration is reloaded.
     *
     * @param path        The path to the configuration file.
     * @param getDefault  A supplier function that provides the default value of the configuration.
     * @param classToLoad The class of the configuration object to load.
     * @param onReload    A consumer function that is called when the configuration is reloaded.
     * @param <T>         The type of the configuration object.
     */
    public static <T> void loadConfig(String path, Supplier<T> getDefault, Class<T> classToLoad, Consumer<T> onReload) {
        // Get the configuration object using the getConfig function.
        T config = getConfig(path, getDefault, classToLoad);

        // Store the reload callback in the loadedConfigs map. It reads the file again: handing the object from the
        // first load back to onReload made #altoreload a no-op for everything but the file's first contents.
        _loadedConfigs.put(path, () -> onReload.accept(getConfig(path, getDefault, classToLoad)));

        // Call the onReload callback function to notify that the configuration is loaded.
        onReload.accept(config);
    }

    /**
     * Reads a json value with the same gson everything else here uses (item, block pos... adapters included).
     * Throws whatever gson throws for a value that does not fit.
     */
    public static <T> T fromJson(JsonElement json, Type type) {
        return GSON.fromJson(json, type);
    }

    /**
     * Save the configuration object to a file at the specified path.
     *
     * @param path   The path of the file to save the configuration to.
     * @param config The configuration object to be saved.
     */
    public static <T> void saveConfig(String path, T config) {
        File configFile = getConfigFile(path);

        // Create parent directories if they don't exist
        createParentDirectories(configFile);

        try (Writer writer = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    /**
     * Creates the parent directories for a given file.
     *
     * @param file the file to create parent directories for
     */
    private static void createParentDirectories(File file) {
        try {
            Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        } catch (IOException e) {
            System.err.println("Failed to create parent directories: " + e.getMessage());
        }
    }

    /**
     * Handles an IOException by printing an error message to the standard error stream.
     *
     * @param exception The IOException to handle.
     */
    private static void handleIOException(IOException exception) {
        System.err.println("An IOException occurred: " + exception.getMessage());
    }

    /**
     * Retrieves a list configuration from the specified path.
     *
     * @param path       The path of the configuration file.
     * @param getDefault A supplier that provides a default configuration object.
     * @param <T>        The type of the configuration object.
     * @return The retrieved configuration object, or null if an error occurs.
     */
    private static <T extends IListConfigFile> T getListConfig(String path, Supplier<T> getDefault) {
        T result = getDefault.get();
        result.onLoadStart();

        File configFile = getConfigFile(path);
        if (!configFile.exists()) {
            return result;
        }

        try (Scanner scanner = new Scanner(configFile, StandardCharsets.UTF_8)) {
            while (scanner.hasNextLine()) {
                String line = trimComment(scanner.nextLine()).trim();
                if (line.isEmpty()) {
                    continue;
                }
                result.addLine(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return result;
    }

    /**
     * Loads a list configuration file from the given path.
     *
     * @param path       the path of the configuration file
     * @param getDefault a supplier function that provides a default configuration object
     * @param onReload   a consumer function that handles the reload of the configuration object
     * @param <T>        the type of the configuration object
     */
    public static <T extends IListConfigFile> void loadListConfig(String path, Supplier<T> getDefault, Consumer<T> onReload) {
        // Get the configuration object from the specified path
        T result = getListConfig(path, getDefault);

        // Store a lambda function in the map to handle the reload of the configuration object (re-reading the file, see loadConfig)
        _loadedConfigs.put(path, () -> {
            T reloaded = getListConfig(path, getDefault);
            if (reloaded != null) {
                onReload.accept(reloaded);
            }
        });

        // Trigger the reload of the configuration object
        onReload.accept(result);
    }

    /**
     * This method trims a comment from the given line.
     * If the line does not contain a comment, the original line is returned.
     *
     * @param line The line to trim the comment from
     * @return The line with the comment trimmed
     */
    private static String trimComment(String line) {
        int poundIndex = line.indexOf('#');
        if (poundIndex == -1) {
            return line;
        } else {
            return line.substring(0, poundIndex);
        }
    }

    /**
     * Ensures that the commented list file exists at the specified path.
     *
     * @param path            The path where the commented list file should be located.
     * @param startingComment The starting comment for the file.
     */
    public static void ensureCommentedListFileExists(String path, String startingComment) {
        File configFile = getConfigFile(path);
        if (configFile.exists()) {
            return;
        }
        StringBuilder commentBuilder = new StringBuilder();
        for (String line : startingComment.split("\\r?\\n")) {
            if (!line.isEmpty()) {
                commentBuilder.append("# ").append(line).append("\n");
            }
        }
        createParentDirectories(configFile);
        try {
            Files.write(configFile.toPath(), commentBuilder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            handleException(e);
        }
    }

    /**
     * Handles an IOException by printing an error message to the standard error stream.
     *
     * @param exception The IOException to handle.
     */
    private static void handleException(IOException exception) {
        // Print the error message to the standard error stream
        System.err.println("An error occurred: " + exception.getMessage());
    }
}
