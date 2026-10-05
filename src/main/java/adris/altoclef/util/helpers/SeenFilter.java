package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;

// STUB, owner: W1 engine (gamer-design.md 5.1). "has the player actually seen this block": line of sight from the eyes
// within a distance, remembered. the gamer uses it for its own structure discovery so it does not x-ray through rock
public final class SeenFilter {
    // true once the block was in line of sight within range at some point. unknown = false, ask again next tick
    public static boolean isSeen(AltoClef mod, BlockPos pos) {
        return false;
    }

    // forget everything (world leave)
    public static void reset() {
    }

    private SeenFilter() {
    }
}