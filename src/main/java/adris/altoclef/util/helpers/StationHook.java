package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// what the container tasks (alto) can ask about the stations the gamer put down, without knowing there is a gamer. same pattern
// as AsyncSmelting.isOurFurnace: the run wires a Source in when it starts and pulls it when it ends. the container tasks feed what
// it says (and what the block tracker sees) to StationChoice, which is the one place that picks a station. a plain alto run has no
// source: nothing is ours, nothing is coming down, and the nearest reachable station within NEAR is used, else one is placed or crafted
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
        // the closest station of ours of this kind that is standing in this world within `radius` (straight line, height counts) of
        // the point, null when there is none. a station being taken back is not standing any more
        BlockPos standingWithin(Kind kind, double x, double y, double z, double radius);

        // the usual question: within WalkCost.STATION_NEAR
        default BlockPos standingNear(Kind kind, double x, double y, double z) {
            return standingWithin(kind, x, y, z, WalkCost.STATION_NEAR);
        }

        // some station of this kind is being picked up right now, so nothing may place or craft another one
        boolean pickingUp(Kind kind);

        // this exact block is the one coming down, no container task may walk to it or open it
        boolean pickingUp(BlockPos pos);

        // the registry has this block, whatever state it is in and however far away. a table the run did not place (a village's)
        // is not ours, so a container task may use it when it is near and nothing ever picks it up
        boolean ours(BlockPos pos);

        // this block is ours and its job went stale and was given up on (Bench.givenUp): what the container tracker still remembers
        // in it is not a load to go back and finish
        boolean givenUp(BlockPos pos);

        // cobble in the bag that is already owed to something else (the gamer's stone tools). "the bag can make a furnace" only
        // counts what is left over, or 8 cobble for a pick, an axe and a sword reads as a furnace and the far one gets abandoned
        default int cobbleOwed() {
            return 0;
        }
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

    public static BlockPos standingWithin(Kind kind, double x, double y, double z, double radius) {
        Source s = source;
        return s == null || kind == null ? null : s.standingWithin(kind, x, y, z, radius);
    }

    public static boolean pickingUp(Kind kind) {
        Source s = source;
        return s != null && kind != null && s.pickingUp(kind);
    }

    public static boolean pickingUp(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.pickingUp(pos);
    }

    // no source (a plain alto run) means nothing is ours: every table the tracker sees within NEAR is a world one, and the choice
    // (StationChoice) uses the nearest reachable one, else places or crafts
    public static boolean ours(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.ours(pos);
    }

    public static boolean givenUp(BlockPos pos) {
        Source s = source;
        return s != null && pos != null && s.givenUp(pos);
    }

    // plain alto owes nothing, all of it can go into a furnace
    public static int cobbleOwed() {
        Source s = source;
        return s == null ? 0 : s.cobbleOwed();
    }
}
