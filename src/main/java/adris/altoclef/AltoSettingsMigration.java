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

package adris.altoclef;

import adris.altoclef.util.helpers.ConfigHelper;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.BlockRange;
import baritone.api.utils.Dimension;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// altoclef used to keep its settings in baritone/altoclef/altoclef_settings.json. they are soprano settings now
// (altoShowTaskChains and friends in settings.txt), this carries what somebody had changed over once and gets the old
// file out of the way. the names map by rule, old "foo" is "altoFoo", so the ones that were dropped
// (commandPrefix, chatLogPrefix, useCraftingBookToCraft) just have nothing to land on
public final class AltoSettingsMigration {

    public static final String OLD_FILE = "altoclef_settings.json";
    public static final String MIGRATED_FILE = OLD_FILE + ".migrated";

    private AltoSettingsMigration() {
    }

    // called on startup, does nothing at all when the old file is not there
    public static void migrateIfNeeded() {
        Path file = ConfigHelper.getConfigFolder().resolve(OLD_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            int moved = migrate(file, Baritone.settings());
            if (moved > 0) {
                SettingsUtil.save(Baritone.settings());
            }
            Files.move(file, file.resolveSibling(MIGRATED_FILE), StandardCopyOption.REPLACE_EXISTING);
            Debug.logMessage(moved > 0
                    ? "Moved " + moved + " AltoClef settings from " + OLD_FILE + " into the normal settings (#set alto<tab>), the old file is now " + MIGRATED_FILE
                    : "AltoClef's " + OLD_FILE + " had nothing changed in it, its settings are normal settings now (#set alto<tab>), the file is now " + MIGRATED_FILE);
        } catch (Exception e) {
            // the file stays where it is so the next start tries again, and nothing was half saved
            Debug.logWarning("Could not move " + OLD_FILE + " into the normal settings: " + e);
            e.printStackTrace();
        }
    }

    // sets every value in the file that differs from the new default, returns how many that was. no saving, no
    // renaming, so it can be tested on its own
    public static int migrate(Path file, Settings settings) throws IOException {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        int moved = 0;
        for (var entry : json.entrySet()) {
            String oldName = entry.getKey();
            // replantCrops is the one that already existed in soprano and was reused instead of getting a twin
            String newName = oldName.equals("replantCrops")
                    ? "replantCrops"
                    : "alto" + Character.toUpperCase(oldName.charAt(0)) + oldName.substring(1);
            Settings.Setting<?> setting = settings.byLowerName.get(newName.toLowerCase(Locale.ROOT));
            if (setting == null) {
                continue;
            }
            try {
                Object value = convert(setting, entry.getValue());
                if (value != null && !Objects.equals(value, setting.defaultValue)) {
                    set(setting, value);
                    moved++;
                }
            } catch (RuntimeException e) {
                // one bad value should not cost the user the rest of the file
                Debug.logWarning("Skipped " + oldName + " from " + OLD_FILE + ": " + e.getMessage());
            }
        }
        return moved;
    }

    private static Object convert(Settings.Setting<?> setting, JsonElement json) {
        if (setting.getName().equals("altoAreasToProtect")) {
            List<BlockRange> ranges = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray()) {
                JsonObject o = element.getAsJsonObject();
                BlockPos start = ConfigHelper.fromJson(o.get("start"), BlockPos.class);
                BlockPos end = ConfigHelper.fromJson(o.get("end"), BlockPos.class);
                Dimension dimension = o.has("dimension") && !o.get("dimension").isJsonNull()
                        ? Dimension.valueOf(o.get("dimension").getAsString())
                        : Dimension.OVERWORLD;
                ranges.add(new BlockRange(start, end, dimension));
            }
            return ranges;
        }
        return ConfigHelper.fromJson(json, setting.getType());
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(Settings.Setting<T> setting, Object value) {
        setting.value = (T) value;
    }
}
