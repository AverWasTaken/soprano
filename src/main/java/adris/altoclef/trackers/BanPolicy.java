package adris.altoclef.trackers;

import adris.altoclef.trackers.Bans.Key;
import adris.altoclef.trackers.Bans.Until;
import baritone.api.utils.Dimension;

import java.util.function.Predicate;

// how long each kind of ban lasts, in one place so "why is it still skipping that" has one file to read. every number is
// game ticks. pure, the callers hand in what they know
public final class BanPolicy {
    // the n-failures rule for blocks (DestroyBlockTask, GetToBlockTask, beds, hay...). 5 minutes is a lot of other blocks,
    // and coming back with a better pick or from far enough away that the chunk reloaded is a new try anyway
    public static final long BLOCK_STRIKES = 6000;
    // the same for mobs and drops. they move, a minute later the river may be between us and somewhere else
    public static final long ENTITY_STRIKES = 1200;
    // the books tasks used to keep for themselves (mining got nowhere, a pickup got nowhere, a bucket's lid would not
    // break). those lived as long as the task object, which is whenever its parent rebuilt it. a minute is about that
    public static final long TASK_GAVE_UP = 1200;
    // a drop we waited on and never got (DropPatience, a death pile, one stuck in water). items despawn at 5 minutes,
    // so this is "for as long as it exists" without keeping dead ids forever
    public static final long DROP_GAVE_UP = 6000;
    // a drop we could not make room for. a minute later the bag may have changed
    public static final long NO_ROOM = 1200;
    // two path searches for a mob came back empty (CalcFailureWatch). it ends early when the mob hits us
    public static final long NO_PATH = 2400;
    // a coal detour that ran out of time or wandered off. 5 minutes, then the cluster gets one more 30 s try
    public static final long COAL = 6000;

    public static final String COAL_REASON = "coal detour gave up on it";
    // the coal bans are the detour's business only (Bans.banFor): the fuel task, or coal as the head need, still mines them
    public static final String COAL_SCOPE = "the coal detour";
    // same deal for the gravel detour, same 5 minutes. its own scope, a gravel patch we gave up on says nothing about coal.
    // and the flint task (kit head "flint") still digs them
    public static final long GRAVEL = 6000;
    public static final String GRAVEL_REASON = "gravel detour gave up on it";
    public static final String GRAVEL_SCOPE = "the gravel detour";
    public static final String OUTPOST_REASON = "near a pillager outpost";
    public static final String DEEP_DARK_REASON = "in the deep dark (ancient city / warden)";

    private BanPolicy() {
    }

    // requestBlockUnreachable's rule
    public static boolean blockStrike(Bans bans, Dimension dim, int x, int y, int z, int allowed, double distSq, String reason) {
        return bans.strike(Key.block(dim, x, y, z), reason, allowed, distSq, BLOCK_STRIKES, Until.BETTER_TOOL, Until.CHUNK_RELOAD);
    }

    // requestEntityUnreachable's rule. the better pick part is the old blacklist's, it never made much sense for a cow but
    // it costs nothing either
    public static boolean entityStrike(Bans bans, int id, int allowed, double distSq, String reason) {
        return bans.strike(Key.entity(id), reason, allowed, distSq, ENTITY_STRIKES, Until.BETTER_TOOL, Until.HIT_US);
    }

    // ---- entities. `what` is EntityTracker.describe, it goes in the line

    // DropPatience ran out: walked at it for 5 s without getting closer, or 30 s in all
    public static boolean dropGaveUp(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "drop never came: " + what, DROP_GAVE_UP);
    }

    // in water and not getting closer (WaterPickupWatchdog)
    public static boolean wetDrop(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "drop in water, not getting closer: " + what, DROP_GAVE_UP);
    }

    // a death pile item RecoverRules.Chase gave up on
    public static boolean recoverGaveUp(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "can't get at death pile item: " + what, DROP_GAVE_UP);
    }

    // the recover's pinned pickup won't touch it (next to lava, out in water), so chasing it is the whole chase for nothing
    public static boolean recoverRefused(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "death pile item the pickup refuses: " + what, DROP_GAVE_UP);
    }

    // touching it and the bag is full of things we keep
    public static boolean noRoom(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "no room for: " + what, NO_ROOM);
    }

    // the pickup task's progress checker ran out on it
    public static boolean pickupStalled(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "pickup got nowhere: " + what, TASK_GAVE_UP);
    }

    // CalcFailureWatch: the second empty search. a hit from it proves there is a way
    public static boolean noPath(Bans bans, int id, String what) {
        return bans.ban(Key.entity(id), "no path to " + what, NO_PATH, Until.HIT_US);
    }

    // ---- blocks

    // MineAndCollectTask's progress checker ran out mid-break or on the way
    public static boolean miningStalled(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.ban(Key.block(dim, x, y, z), "mining got nowhere", TASK_GAVE_UP);
    }

    // CollectBucketLiquidTask could not break the block over a source
    public static boolean bucketLidStuck(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.ban(Key.block(dim, x, y, z), "couldn't break the block over this source", TASK_GAVE_UP);
    }

    public static boolean coal(Bans bans, int x, int y, int z) {
        return bans.banFor(COAL_SCOPE, Key.block(Dimension.OVERWORLD, x, y, z), COAL_REASON, COAL);
    }

    // what the detour asks: the everybody bans plus its own
    public static boolean coalBanned(Bans bans, int x, int y, int z) {
        return bans.banned(Key.block(Dimension.OVERWORLD, x, y, z), COAL_SCOPE);
    }

    // gravel comes in the nether too (soul sand valleys), so this one takes the dimension
    public static boolean gravel(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.banFor(GRAVEL_SCOPE, Key.block(dim, x, y, z), GRAVEL_REASON, GRAVEL);
    }

    public static boolean gravelBanned(Bans bans, Dimension dim, int x, int y, int z) {
        return bans.banned(Key.block(dim, x, y, z), GRAVEL_SCOPE);
    }

    // DangerFilter: trial chamber furniture, witch hut and outpost tables. they do not stop being traps, so the run
    public static boolean trap(Bans bans, Dimension dim, int x, int y, int z, String what) {
        return bans.ban(Key.block(dim, x, y, z), what, Bans.RUN);
    }

    // DangerFilter: logs and wool by the outpost at (x, z), one pass. for the run, but lifted the moment the outpost goes quiet
    // (liftOutpost). one line for the pass, the already banned ones don't count
    public static int outpost(Bans bans, Iterable<Key> blocks, double x, double z) {
        return bans.banAll(blocks, OUTPOST_REASON, Bans.RUN, "logs/wool near the pillager outpost at " + (int) x + " " + (int) z);
    }

    // DeepDarkRules: wool in the deep dark, one pass, one line. never lifted, the warden is not going anywhere
    public static int deepDarkWool(Bans bans, Iterable<Key> blocks, long x, long z) {
        return bans.banAll(blocks, DEEP_DARK_REASON, Bans.RUN, "wool in the deep dark near " + x + " " + z);
    }

    // an outpost at (x, z) went quiet. its bans go unless `stillCovered` says another live outpost reaches the block.
    // only the outpost bans: a log that was unreachable for its own reasons stays that way
    public static int liftOutpost(Bans bans, double x, double z, double radius, Predicate<Key> stillCovered) {
        return bans.liftAll(OUTPOST_REASON, key -> key.kind() == Bans.Kind.BLOCK && key.dim() == Dimension.OVERWORLD
                && Math.hypot(key.x() - x, key.z() - z) <= radius && !stillCovered.test(key),
                "logs/wool near the pillager outpost at " + (int) x + " " + (int) z + ", it went quiet");
    }
}
