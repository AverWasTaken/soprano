package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

// blocks we picked while their chunk was unloaded. the tracker and canBreak both say yes to a chunk they can't read, so a
// block that canBreak turns down once it loads gets picked again the moment we step back out of range, and the walk flips
// in and out at the chunk edge forever. same class of bug as BuriedLogs, this time for the whole of canBreak. a block that
// was picked (or held) while loaded is a normal pursuit and never gets in here, a block that is gone is the tracker's job
public final class UnloadedTrust {
    // which part of canBreak said no, first one wins in canBreak's own order
    public enum Part {
        DESTROY_SPEED("unbreakable"),
        AVOID_RULE("the avoid breaking rule"),
        UNREACHABLE("a ban"),
        TOO_SLOW("too slow to break"),
        PLAUSIBLE("plausibleToBreak"),
        OCEAN("the ocean rule"),
        OTHER("canBreak, no single part");

        public final String label;

        Part(String label) {
            this.label = label;
        }
    }

    // a task picks the same block every tick while it walks, this only ever holds a handful. the cap is for a task that lives
    // through a lot of map
    private static final int CAP = 256;

    private final Set<BlockPos> blind = new HashSet<>();

    // the block was picked or is still held this tick, and whether its chunk was there to look at
    public void seen(BlockPos pos, boolean loaded) {
        if (loaded) {
            blind.remove(pos);
            return;
        }
        if (blind.size() >= CAP && !blind.contains(pos)) {
            blind.clear();
        }
        blind.add(pos.immutable());
    }

    // the pursuit of pos was dropped. true once: it was picked blind, its chunk is loaded now, the block is still there and
    // canBreak says no. any loaded answer forgets it, only a drop that happened while still unloaded keeps it waiting
    public boolean caught(BlockPos pos, boolean loaded, boolean stillThere, boolean canBreak) {
        if (!loaded || !blind.remove(pos)) {
            return false;
        }
        return stillThere && !canBreak;
    }

    public boolean picked(BlockPos pos) {
        return blind.contains(pos);
    }

    // canBreak's checks in canBreak's order. all of them passing means the world changed between the two asks
    public static Part failing(boolean unbreakable, boolean avoid, boolean unreachable, boolean tooSlow, boolean plausible, boolean ocean) {
        if (unbreakable) return Part.DESTROY_SPEED;
        if (avoid) return Part.AVOID_RULE;
        if (unreachable) return Part.UNREACHABLE;
        if (tooSlow) return Part.TOO_SLOW;
        if (!plausible) return Part.PLAUSIBLE;
        if (ocean) return Part.OCEAN;
        return Part.OTHER;
    }
}
