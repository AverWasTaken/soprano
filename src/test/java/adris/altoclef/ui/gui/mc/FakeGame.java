package adris.altoclef.ui.gui.mc;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// the world McSchematics talks to, by hand: a dimension, a pair of feet, a placements file, and a record of every build
// and chat line instead of a builder and a chat
final class FakeGame implements McSchematics.Game {

    record Built(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror) {
    }

    String dimension = "minecraft:overworld";
    BlockPos feet = new BlockPos(10, 64, -20);
    Path file;
    Object level = new Object();
    TerrainMap.Source terrain;
    final List<Built> built = new ArrayList<>();
    final List<String> said = new ArrayList<>();

    void leaveWorld() {
        dimension = null;
        feet = null;
        level = null;
    }

    @Override
    public String dimension() {
        return dimension;
    }

    @Override
    public BlockPos feet() {
        return feet;
    }

    @Override
    public Path placementsFile() {
        return file;
    }

    @Override
    public Object level() {
        return level;
    }

    @Override
    public TerrainMap.Source terrain() {
        return terrain;
    }

    @Override
    public void build(String name, IStaticSchematic schematic, BlockPos origin, Rotation rotation, Mirror mirror) {
        built.add(new Built(name, schematic, origin, rotation, mirror));
    }

    @Override
    public void say(String message) {
        said.add(message);
    }
}
