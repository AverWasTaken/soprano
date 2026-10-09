package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// what the container tasks (alto) can ask about the stations the gamer put down, without knowing there is a gamer. same pattern
// as AsyncSmelting.isOurFurnace: the run wires a Source in when it starts and pulls it when it ends, a plain alto run sees no
// source and behaves like it always did
public final class StationHook {
    // the three workbenches the registry keeps. blast furnaces and the like are the village's, never ours to take back
    public enum Kind {
        TABLE, FURNACE, SMOKER;

        public String word() {
            return switch (this) {
                case TABLE -> "table";
                case FURNACE -> "furnace";
                case SMOKER -> "smoker";
            };
        }
    }

    public interface Source {
        // the closest station of ours of this kind that is standing in this world within WalkCost.STATION_NEAR (straight line,
        // height counts) of the point, null when there is none. a station being taken back is not standing any more
        BlockPos standingNear(Kind kind, double x, double y, double z);

        // some station of this kind is being picked up right now, so nothing may place or craft another one
        boolean pickingUp(Kind kind);

        // this exact block is the one coming down, no container task may walk to it or open it
        boolean pickingUp(BlockPos pos);
    }

    private static volatile Source source;

    private StationHook() {
    }

    public static void install(Source s) {
        source = s;
    }

    public static void clear() {
        source = null;
    }

    // which workbench a container task is for, null for the ones we do not keep track of (chests, anvils, a blast furnace)
    public static Kind kindOf(Block[] blocks) {
        if (blocks == null || blocks.length != 1) {
            return null;
        }
        Block block = blocks[0];
        if (block == Blocks.CRAFTING_TABLE) {
            return Kind.TABLE;
        }
        if (block == Blocks.FURNACE) {
            return Kind.FURNACE;
        }
        return block == Blocks.SMOKER ? Kind.SMOKER : null;
    }

    public static BlockPos standingNear(Kind kind, double x, double y, double z) {
        Source s = source;
        return s == null || kind == null ? null : s.standingNear(kind, x, y, z);
    }

    public static boolean pickingUp(Kind kind) {
        Source s = source;
        return s != null && kind != null && s.pickingUp(kind);
    }

    public static boolean pickingUp(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.pickingUp(pos);
    }
}
