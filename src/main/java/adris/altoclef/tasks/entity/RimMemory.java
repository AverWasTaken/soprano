package adris.altoclef.tasks.entity;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// what the rim code learned fighting one mob: cells a step already went nowhere to, and how long we've walked off the shore
// for it. per mob id and kept outside the task, same as the lava waits: the pearl hunt drops the angry fight's kill task and
// builds a fresh one for the same enderman a pickup later, and a fresh task with an empty memory walks the same doomed steps
// again (and then climbs to it instead)
final class RimMemory {
    // this long without a look is a different fight
    static final int GAP_TICKS = 400;
    // nobody fights 64 mobs at once, past that it's old fights piling up
    static final int MAX_MOBS = 64;

    final Set<BlockPos> failed = new HashSet<>();
    int groundTicks;
    private int last;

    private RimMemory(int now) {
        last = now;
    }

    static final class Store {
        private final Map<Integer, RimMemory> byId = new HashMap<>();

        // a clock that went backwards is a respawn or a new world, the ids mean nothing there
        RimMemory get(int id, int now) {
            if (byId.size() > MAX_MOBS) {
                byId.clear();
            }
            RimMemory m = byId.get(id);
            if (m == null || now < m.last || now - m.last > GAP_TICKS) {
                m = new RimMemory(now);
                byId.put(id, m);
            }
            m.last = now;
            return m;
        }

        int size() {
            return byId.size();
        }
    }
}
