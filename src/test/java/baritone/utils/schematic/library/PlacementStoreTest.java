package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlacementStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private Path file() throws Exception {
        return folder.getRoot().toPath().resolve("altoclef").resolve(PlacementStore.FILE_NAME);
    }

    @Test
    public void roundTripsEveryField() throws Exception {
        Map<String, SchematicPlacement> in = new LinkedHashMap<>();
        in.put("castle.litematic", new SchematicPlacement(new BlockPos(-120, 70, 4400), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, "minecraft:overworld"));
        in.put("sub/tower.schem", new SchematicPlacement(new BlockPos(1, -60, 2), Rotation.NONE, Mirror.NONE, "minecraft:the_nether"));
        Path file = file();
        // the altoclef folder doesn't exist yet, save makes it
        assertTrue(PlacementStore.save(file, in));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
        assertEquals(in, PlacementStore.load(file, path -> true));
    }

    @Test
    public void entriesForFilesThatAreGoneArePruned() throws Exception {
        Map<String, SchematicPlacement> in = new LinkedHashMap<>();
        in.put("kept.schem", SchematicPlacement.at(BlockPos.ZERO, "minecraft:overworld"));
        in.put("deleted.schem", SchematicPlacement.at(BlockPos.ZERO, "minecraft:overworld"));
        PlacementStore.save(file(), in);
        Map<String, SchematicPlacement> out = PlacementStore.load(file(), Set.of("kept.schem")::contains);
        assertEquals(Set.of("kept.schem"), out.keySet());
    }

    @Test
    public void aCheckThatThrowsCountsAsGone() throws Exception {
        PlacementStore.save(file(), Map.of("a.schem", SchematicPlacement.at(BlockPos.ZERO, "d")));
        assertTrue(PlacementStore.load(file(), path -> {
            throw new IllegalArgumentException("bad path");
        }).isEmpty());
    }

    @Test
    public void noFileOrNoWorldIsEmpty() throws Exception {
        assertTrue(PlacementStore.load(file(), path -> true).isEmpty());
        assertTrue(PlacementStore.load(null, path -> true).isEmpty());
    }

    @Test
    public void aCorruptFileIsMovedAsideAndReadsEmpty() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        assertTrue(PlacementStore.load(file, path -> true).isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bad")));
    }

    @Test
    public void aNewerSchemaIsMovedAsideNotOverwritten() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\": 9, \"placements\": {}}", StandardCharsets.UTF_8);
        assertTrue(PlacementStore.load(file, path -> true).isEmpty());
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bad")));
    }

    @Test
    public void oneBadEntryDoesNotSinkTheRest() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"schema": 1, "placements": {
              "good.schem": {"x": 1, "y": 2, "z": 3, "rotation": "CLOCKWISE_180", "mirror": "NONE", "dimension": "minecraft:overworld"},
              "weird.schem": {"x": 1, "y": 2, "z": 3, "rotation": "SIDEWAYS", "dimension": "minecraft:overworld"},
              "nowhere.schem": {"x": 1, "y": 2, "z": 3},
              "bare.schem": {"x": 4, "y": 5, "z": 6, "dimension": "minecraft:the_end"},
              "lr.schem": {"x": 0, "y": 0, "z": 0, "rotation": "NONE", "mirror": "LEFT_RIGHT", "dimension": "d"},
              "typo.schem": {"x": "abc", "y": 2, "z": 3, "dimension": "minecraft:overworld"},
              "half.schem": {"x": 1.5, "y": 2, "z": 3, "dimension": "minecraft:overworld"},
              "noz.schem": {"x": 1, "y": 2, "dimension": "minecraft:overworld"}
            }}
            """, StandardCharsets.UTF_8);
        Map<String, SchematicPlacement> out = PlacementStore.load(file, path -> true);
        assertEquals(Set.of("good.schem", "bare.schem", "lr.schem", "half.schem"), out.keySet());
        // a hand typed 1.5 is read as 1 rather than losing the entry, the typo and the missing z are dropped
        assertEquals(1, out.get("half.schem").origin().getX());
        assertEquals(Rotation.CLOCKWISE_180, out.get("good.schem").rotation());
        // a missing turn or flip is none of either
        assertEquals(Rotation.NONE, out.get("bare.schem").rotation());
        assertEquals(new BlockPos(4, 5, 6), out.get("bare.schem").origin());
        // and a hand written LEFT_RIGHT comes back as the one mirror plus half a turn
        assertEquals(Mirror.FRONT_BACK, out.get("lr.schem").mirror());
        assertEquals(Rotation.CLOCKWISE_180, out.get("lr.schem").rotation());
    }

    @Test
    public void aFileThatCannotBeReadIsNullNotEmpty() throws Exception {
        // a folder squatting on the name: something is there, but no text comes out. null, and nothing is touched
        Path file = file();
        Files.createDirectories(file.resolve("x"));
        assertEquals(null, PlacementStore.load(file, path -> true));
        assertTrue(Files.isDirectory(file.resolve("x")));
    }

    @Test
    public void bytesThatAreNotTextAreCorruptNotLocked() throws Exception {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertTrue(PlacementStore.load(file, path -> true).isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bad")));
    }

    @Test
    public void theJsonIsReadableByAHuman() {
        String json = PlacementStore.toJson(Map.of("a.schem",
            new SchematicPlacement(new BlockPos(1, 2, 3), Rotation.COUNTERCLOCKWISE_90, Mirror.FRONT_BACK, "minecraft:overworld")));
        assertTrue(json.contains("\"rotation\": \"COUNTERCLOCKWISE_90\""));
        assertTrue(json.contains("\"mirror\": \"FRONT_BACK\""));
        assertTrue(json.contains("\"dimension\": \"minecraft:overworld\""));
    }
}
