package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import java.util.List;
import java.util.stream.Collectors;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

// in this package because ConfigHelper's folder override is package private
public class GamerConfigsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file;

    @Before
    public void pointConfigsAtTheTempFolder() {
        ConfigHelper._folderOverride = tmp.getRoot().toPath();
        file = tmp.getRoot().toPath().resolve("configs").resolve("beat_minecraft.json");
    }

    @After
    public void putItBack() {
        ConfigHelper._folderOverride = null;
    }

    private void write(String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private JsonObject onDisk() throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void aMissingFileGetsCreatedWithTheDefaults() throws IOException {
        GamerConfig c = GamerConfigs.load();
        assertEquals(GamerConfig.VERSION, c.version);
        assertEquals(14, c.targetEyes);
        assertTrue(Files.exists(file));
        assertEquals(GamerConfig.VERSION, onDisk().get("version").getAsInt());
    }

    @Test
    public void theOldShapeHasNoVersionSoItIsReplacedByTheDefaults() throws IOException {
        // what BeatMinecraftConfig wrote: its numbers must not leak into the new defaults
        write("{\"targetEyes\": 20, \"minimumEyes\": 12, \"requiredBeds\": 10, \"minFoodUnits\": 180, \"foodUnits\": 220, \"sleepThroughNight\": true}");
        GamerConfig c = GamerConfigs.load();
        assertEquals(new GamerConfig().targetEyes, c.targetEyes);
        assertEquals(GamerConfig.VERSION, c.version);
        JsonObject disk = onDisk();
        assertEquals(GamerConfig.VERSION, disk.get("version").getAsInt());
        assertFalse(disk.has("requiredBeds"));
        assertFalse(disk.has("minimumEyes"));
        assertEquals(new GamerConfig().targetEyes, disk.get("targetEyes").getAsInt());
    }

    @Test
    public void anotherVersionNumberIsReplacedToo() throws IOException {
        write("{\"version\": " + (GamerConfig.VERSION + 1) + ", \"targetEyes\": 99}");
        assertEquals(new GamerConfig().targetEyes, GamerConfigs.load().targetEyes);
        write("{\"version\": \"" + GamerConfig.VERSION + "\", \"targetEyes\": 99}");
        assertEquals(new GamerConfig().targetEyes, GamerConfigs.load().targetEyes);
    }

    @Test
    public void garbageIsReplacedByTheDefaults() throws IOException {
        write("this is { not json");
        assertEquals(GamerConfig.VERSION, GamerConfigs.load().version);
        assertEquals(GamerConfig.VERSION, onDisk().get("version").getAsInt());
        write("");
        assertEquals(GamerConfig.VERSION, GamerConfigs.load().version);
        write("[1, 2, 3]");
        assertEquals(GamerConfig.VERSION, GamerConfigs.load().version);
    }

    @Test
    public void aCurrentFileKeepsItsChangedValues() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"targetEyes\": 16, \"floorEyes\": 13,"
                + " \"nether\": {\"sweepSpacingChunks\": 5}, \"budgets\": {\"gather\": 3.5}, \"death\": {\"recoverBlocks\": 50}}");
        GamerConfig c = GamerConfigs.load();
        assertEquals(16, c.targetEyes);
        assertEquals(13, c.floorEyes);
        assertEquals(5, c.nether.sweepSpacingChunks);
        assertEquals(3.5, c.budgets.gather, 0);
        assertEquals(50, c.death.recoverBlocks);
        // what the file did not mention keeps the default, nested objects too
        assertEquals(new GamerConfig().nether.rodsBudgetMinutes, c.nether.rodsBudgetMinutes, 0);
        assertEquals(new GamerConfig().budgets.iron, c.budgets.iron, 0);
        assertEquals(new GamerConfig().maxAttempts, c.maxAttempts);
        assertEquals(new GamerConfig().end.beds, c.end.beds);
        // and the file keeps the change after the rewrite that every load does
        assertEquals(16, onDisk().get("targetEyes").getAsInt());
    }

    @Test
    public void unknownKeysAreIgnored() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"targetEyes\": 15, \"somethingNew\": {\"a\": 1}, \"nether\": {\"alsoNew\": true}}");
        GamerConfig c = GamerConfigs.load();
        assertEquals(15, c.targetEyes);
        assertNotNull(c.nether);
    }

    @Test
    public void theFileThatGetsReplacedIsKeptAsABackup() throws IOException {
        String old = "{\"targetEyes\": 20, \"requiredBeds\": 10}";
        write(old);
        GamerConfigs.load();
        Path bak = file.resolveSibling("beat_minecraft.json.bak");
        assertTrue(Files.exists(bak));
        assertEquals(old, Files.readString(bak, StandardCharsets.UTF_8));
        // a typo'd current file (stray comma) is replaced too, and the edits are not lost
        String typo = "{\"version\": " + GamerConfig.VERSION + ", \"targetEyes\": 16,}x";
        write(typo);
        GamerConfigs.load();
        assertEquals(typo, Files.readString(bak, StandardCharsets.UTF_8));
        assertEquals(new GamerConfig().targetEyes, GamerConfigs.get().targetEyes);
        // a good file makes no backup
        Files.delete(bak);
        write("{\"version\": " + GamerConfig.VERSION + ", \"targetEyes\": 16}");
        GamerConfigs.load();
        assertFalse(Files.exists(bak));
    }

    @Test
    public void numbersThatWouldBreakARunAreClamped() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"maxAttempts\": 0, \"targetEyes\": 0, \"floorEyes\": 99,"
                + " \"budgets\": {\"gather\": 0, \"nether\": -5, \"dragon\": 0,\"iron\": 30},"
                + " \"death\": {\"maxPerPhase\": 0, \"maxTotal\": -1, \"recoverBlocks\": -3},"
                + " \"end\": {\"attempts\": 0, \"beds\": -2},"
                + " \"stronghold\": {\"maxThrows\": 0, \"maxEmptyThrows\": 0, \"sigmaDeg\": 0, \"spiralRadiusChunks\": 0},"
                + " \"nether\": {\"maxCells\": 0, \"sweepSpacingChunks\": 0, \"waypointSeconds\": -1}}");
        GamerConfig c = GamerConfigs.load();
        GamerConfig d = new GamerConfig();
        assertEquals(1, c.maxAttempts);
        assertEquals(1, c.targetEyes);
        // the floor can not be above the target
        assertEquals(1, c.floorEyes);
        assertEquals(d.budgets.gather, c.budgets.gather, 0);
        assertEquals(d.budgets.nether, c.budgets.nether, 0);
        assertEquals(d.budgets.dragon, c.budgets.dragon, 0);
        assertEquals(30, c.budgets.iron, 0);
        assertEquals(1, c.death.maxPerPhase);
        assertEquals(1, c.death.maxTotal);
        assertEquals(0, c.death.recoverBlocks);
        assertEquals(1, c.end.attempts);
        assertEquals(0, c.end.beds);
        assertEquals(1, c.stronghold.maxThrows);
        assertEquals(1, c.stronghold.maxEmptyThrows);
        assertEquals(d.stronghold.sigmaDeg, c.stronghold.sigmaDeg, 0);
        assertEquals(1, c.stronghold.spiralRadiusChunks);
        assertEquals(1, c.nether.maxCells);
        assertEquals(1, c.nether.sweepSpacingChunks);
        assertEquals(d.nether.waypointSeconds, c.nether.waypointSeconds, 0);
    }

    @Test
    public void sensibleNumbersAreLeftAlone() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"maxAttempts\": 3, \"targetEyes\": 16, \"floorEyes\": 12,"
                + " \"budgets\": {\"gather\": 2.5}, \"end\": {\"attempts\": 5}}");
        GamerConfig c = GamerConfigs.load();
        assertEquals(3, c.maxAttempts);
        assertEquals(16, c.targetEyes);
        assertEquals(12, c.floorEyes);
        assertEquals(2.5, c.budgets.gather, 0);
        assertEquals(5, c.end.attempts);
    }

    @Test
    public void aNullSectionBecomesTheDefaultSection() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"nether\": null, \"budgets\": null}");
        GamerConfig c = GamerConfigs.load();
        assertNotNull(c.nether);
        assertEquals(new GamerConfig().budgets.gather, c.budgets.gather, 0);
    }

    // what a v2 file looked like on disk when the bot went into the nether with no ladders: kit lists saved before the
    // ladder and the axe existed, plus a couple of numbers somebody tuned
    private static final String V2_FILE = "{\"version\": 2, \"targetEyes\": 16, \"floorEyes\": 13,"
            + " \"nether\": {\"sweepSpacingChunks\": 5}, \"budgets\": {\"gather\": 3.5},"
            + " \"overworld\": {\"minFoodUnits\": 55, \"armorPlan\": \"CHEST_HELMET\","
            + " \"starterKit\": [{\"item\": \"stone_pickaxe\", \"count\": 1}, {\"item\": \"furnace\", \"count\": 1}],"
            + " \"ironKit\": [{\"item\": \"iron_pickaxe\", \"count\": 1}, {\"item\": \"bucket\", \"count\": 2}],"
            + " \"smeltExtras\": [{\"item\": \"food\", \"count\": 5}]}}";

    private static List<String> names(List<OverworldConfig.KitItem> kit) {
        return kit.stream().map(k -> k.item + " x" + k.count).collect(Collectors.toList());
    }

    @Test
    public void aV2FileKeepsItsNumbersButGetsTheDefaultKitLists() throws IOException {
        write(V2_FILE);
        GamerConfig c = GamerConfigs.load();
        GamerConfig d = new GamerConfig();
        // the kit lists are the code's, so the ladder and the wooden axe are back
        assertEquals(names(d.overworld.starterKit), names(c.overworld.starterKit));
        assertEquals(names(d.overworld.ironKit), names(c.overworld.ironKit));
        assertEquals(names(d.overworld.smeltExtras), names(c.overworld.smeltExtras));
        assertTrue(names(c.overworld.starterKit).contains("wooden_axe x1"));
        assertTrue(names(c.overworld.ironKit).contains("ladder x3"));
        // and what somebody tuned stays tuned
        assertEquals(16, c.targetEyes);
        assertEquals(13, c.floorEyes);
        assertEquals(5, c.nether.sweepSpacingChunks);
        assertEquals(3.5, c.budgets.gather, 0);
        assertEquals(55, c.overworld.minFoodUnits);
        assertEquals(OverworldConfig.ArmorPlan.CHEST_HELMET, c.overworld.armorPlan);
        assertEquals(GamerConfig.VERSION, c.version);
    }

    @Test
    public void aV2FileIsSavedAsTheCurrentVersionWithABackup() throws IOException {
        write(V2_FILE);
        GamerConfigs.load();
        JsonObject disk = onDisk();
        assertEquals(GamerConfig.VERSION, disk.get("version").getAsInt());
        assertEquals(16, disk.get("targetEyes").getAsInt());
        assertTrue(disk.getAsJsonObject("overworld").toString().contains("ladder"));
        assertEquals(V2_FILE, Files.readString(file.resolveSibling("beat_minecraft.json.bak"), StandardCharsets.UTF_8));
        // the second load has nothing left to migrate
        GamerConfig again = GamerConfigs.load();
        assertEquals(16, again.targetEyes);
        assertTrue(names(again.overworld.ironKit).contains("ladder x3"));
    }

    @Test
    public void aCurrentFileKeepsItsKitLists() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"overworld\": {\"ironKit\": [{\"item\": \"bucket\", \"count\": 2}]}}");
        assertEquals(List.of("bucket x2"), names(GamerConfigs.load().overworld.ironKit));
    }

    // GamerConfig.VERSION has to move whenever a default kit list does (a saved list replaces the default, so old files keep
    // the old kit until the migration resets them). this pins the pair: change a default and it fails, and the fix is to
    // bump VERSION and then put the new VERSION and hash here
    private static final int PINNED_VERSION = 3;
    private static final String PINNED_KIT_HASH = "ac609dc3";

    @Test
    public void changingADefaultKitListMeansBumpingTheVersion() {
        StringBuilder dump = new StringBuilder();
        GamerConfigs.kitLists(new GamerConfig()).forEach((path, kit) -> dump.append(path).append(": ").append(names(kit)).append('\n'));
        String hash = Integer.toHexString(dump.toString().hashCode());
        String hint = "\nthe default kit lists are now:\n" + dump + "hash " + hash + ". bump GamerConfig.VERSION, then set"
                + " PINNED_VERSION and PINNED_KIT_HASH in this test (and keep the migration in GamerConfigs.migrate working)";
        assertEquals("kit lists changed without a version bump." + hint, PINNED_KIT_HASH, hash);
        assertEquals("version moved, repin it." + hint, PINNED_VERSION, GamerConfig.VERSION);
    }

    @Test
    public void everyKitListInTheTreeIsFound() {
        // containsAll, somebody else adding a list should not break this
        assertTrue(GamerConfigs.kitLists(new GamerConfig()).keySet()
                .containsAll(List.of("overworld.ironKit", "overworld.smeltExtras", "overworld.starterKit")));
    }

    @Test
    public void aVersionBelowTheOldestKeptIsStillReplaced() throws IOException {
        write("{\"version\": " + (GamerConfig.OLDEST_KEPT - 1) + ", \"targetEyes\": 99}");
        assertEquals(new GamerConfig().targetEyes, GamerConfigs.load().targetEyes);
    }

    @Test
    public void altoreloadReReadsTheFileAndTheVersionRuleAppliesThereToo() throws IOException {
        write("{\"version\": " + GamerConfig.VERSION + ", \"targetEyes\": 16}");
        assertEquals(16, GamerConfigs.load().targetEyes);
        write("{\"version\": " + GamerConfig.VERSION + ", \"targetEyes\": 18}");
        ConfigHelper.reloadAllConfigs();
        assertEquals(18, GamerConfigs.get().targetEyes);
        // somebody (the old BM2 task, still around until the integrator deletes it) writes the old shape over it
        write("{\"targetEyes\": 25, \"requiredBeds\": 10}");
        ConfigHelper.reloadAllConfigs();
        assertEquals(new GamerConfig().targetEyes, GamerConfigs.get().targetEyes);
        assertFalse(onDisk().has("requiredBeds"));
    }
}
