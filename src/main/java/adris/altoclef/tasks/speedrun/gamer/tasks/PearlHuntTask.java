package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.AbstractDoToEntityTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.PearlHuntHook;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

// pearls from endermen, in the dimension we are told to hunt in. in the nether it walks to the warped forest first when
// one is known (the densest enderman farm there is that needs no gold), in the overworld it just hunts what it finds.
// angry endermen (the ones that already saw us) die first, like KillEndermanTask did, and the pearls are picked up by
// the resource task underneath. the stronghold phases can use the overworld mode as their pearl top up
public class PearlHuntTask extends Task {
    // close enough to the forest that the endermen are in view distance
    private static final double AT_HOTSPOT_BLOCKS = 40;
    // a sighted forest block is a floor, the hills around it are not. further off than this up or down is not "there"
    private static final double AT_HOTSPOT_Y = 8;
    // a nook that came up empty is not tried again for this long (the loot walk below moves us somewhere else meanwhile)
    private static final long NOOK_RETRY_TICKS = 400;
    private static final int NOOK_TRIES = 4;
    // the walk to the forest is judged on progress, not a flat clock: a long honest walk is fine, one that stopped getting
    // closer is not. the hard cap is for a walk that keeps creeping forward forever
    private static final double HOTSPOT_STALL_SECONDS = 40;
    private static final double HOTSPOT_WALK_CAP_SECONDS = 480;
    // closer than the best so far by this much counts as progress, a half block creep is the path wobbling
    private static final double HOTSPOT_PROGRESS_BLOCKS = 2;
    // arrived once and now this far off (flat) means the hunt wandered away from the forest
    private static final double HOTSPOT_DRIFT_BLOCKS = 3 * AT_HOTSPOT_BLOCKS;
    // not being ticked this long is a food trip or the like, mob defense is seconds
    private static final long AWAY_REOPEN_TICKS = 600;
    // an angry one across a lava lake never arrives: after this long on one target it is somebody else's problem
    private static final double ANGRY_SECONDS = 20;
    private static final double ENDERMAN_HOLD_SECONDS = 6;
    private static final long NOOK_FRESH_TICKS = 200;
    private static final double AWAY_BLOCKS = 6;
    // 15 s of flailing about is enough, after that the hunt goes on from wherever we ended up
    private static final int AWAY_GIVE_UP_TICKS = 300;
    // the back off count only starts over after this long without fleeing: lava that is in reach one step and out of it the
    // next would otherwise reset it every other tick and the give up could never come
    private static final int AWAY_CALM_RESET_TICKS = 100;
    // a pearl this close (all three axes, one 20 blocks down a cliff is not "close") is walked to before the hunt goes on
    private static final double PICKUP_BLOCKS = 16;
    // out of the lava's reach and a good way out of the spot's own radius, 15 s is plenty to walk it
    private static final double LEAVE_BLOCKS = 32;
    private static final long LEAVE_TICKS = 300;

    // an enderman this close to a heavy hitter is not a loot target
    private static final double HEAVY_SKIP_BLOCKS = 16;
    // the tracker is keyed by exact class: the nether ones from FoeRules HEAVY that stand around bastions
    private static final List<Class<? extends Entity>> HEAVY_TYPES = List.of(PiglinBrute.class, Hoglin.class);

    // the planner's nearBastion in the nether, never in the overworld
    private final BiPredicate<Integer, Integer> nearBastion;
    private final Set<Integer> skipLogged = new HashSet<>();
    private final int pearlsTotal;
    private final Dimension dimension;
    private final Supplier<RunState.Pos> hotspot;
    // told when an enderman dies, and only then (a stare that goes nowhere is not progress, see NetherPhase.stallSeconds)
    private final Runnable onKill;
    // the angry ones seen last tick, so one that is dead now was one we killed
    private final List<Enderman> watched = new ArrayList<>();
    private final KillAndLootTask loot;
    private final ItemTarget pearlTarget;
    // one instance for the hunt: the bans live on the mod so a rebuild would be fine too, but this keeps its walk progress
    private PickupDroppedItemTask pickup;
    private final Task goToDimension;

    private GetWithinRangeOfBlockTask toHotspot;
    // the walk is waiting on hp, not done
    private boolean walkHeld;
    private RunState.Pos hotspotWalkingTo;
    // these two are phase clocks, not time on task: each only ticks in its own branch and restarts with its target.
    // the caps survive interrupts, and a long fight with something else (or a death) in between does not eat them
    private final TaskClock hotspotSpent = new TaskClock();
    private final TaskClock hotspotStall = new TaskClock();
    private double bestXZ = Double.MAX_VALUE;
    private boolean hotspotDone;
    // the walk ended by getting there / by a stall or the cap, drift only reopens the first kind
    private boolean hotspotArrived;
    private boolean hotspotGaveUp;
    private Entity angryTarget;
    private KillEntityTask angryTask;
    private final AngryClock angrySpent = new AngryClock();
    private NookHuntTask nook;
    private long lastTickAt = Long.MIN_VALUE;
    // fight time per enderman across every nook this hunt builds, so a new hole does not hand an unreachable one a fresh minute
    private final AngryClock nookFight = new AngryClock();
    private int nookFails;
    private long nookRetryAt = Long.MIN_VALUE;
    private String step = "Looking for endermen";
    // hp tracking for the low hp wait: the last tick hp went up (or was fine)
    private float lastHp = -1;
    private long lastRiseAt = Long.MIN_VALUE;
    // latched: its isEqual compares the danger blocks, a rebuild per tick would restart it every tick
    private RunAwayFromPositionTask away;
    // ticks spent stepping away in a row; a shore with nowhere to flee to must not hold us in "Backing off" forever
    private AwayCount awayCount = AwayCount.NONE;
    // where lava kept us stuck: endermen near it are skipped for a few minutes (see LavaSpot) and we walk off it once
    private final LavaSpot lavaSpot = new LavaSpot();
    private final LavaStrikes lavaStrikes = new LavaStrikes();
    // a back off is new when the last fleeing tick is more than one tick old, so an interrupt or an early return in between
    // cannot leave it looking like the same one
    private long lastFleeAt = Long.MIN_VALUE / 2;
    private RunAwayFromPositionTask leaveSpot;
    private long leaveUntil = Long.MIN_VALUE;
    // same latch the phase uses, an enderman behind a pillar must not switch the hotspot walk off and on
    private final HoldLatch endermanLatch = new HoldLatch(ENDERMAN_HOLD_SECONDS);

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot) {
        this(pearlsTotal, dimension, hotspot, () -> {
        });
    }

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot, Runnable onKill) {
        this(pearlsTotal, dimension, hotspot, onKill, (x, z) -> false);
    }

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot, Runnable onKill, BiPredicate<Integer, Integer> nearBastion) {
        this.nearBastion = nearBastion;
        this.onKill = onKill;
        this.pearlsTotal = pearlsTotal;
        this.dimension = dimension;
        this.hotspot = hotspot;
        // an enderman on a lava shore is not worth the walk, the bot ends up standing at the lava with nowhere to go
        pearlTarget = new ItemTarget(Items.ENDER_PEARL, pearlsTotal);
        loot = new KillAndLootTask(Enderman.class, this::lootTarget, true, pearlTarget);
        loot.forceDimension(dimension);
        goToDimension = new DefaultGoToDimensionTask(dimension);
    }

    // ticks of fighting per enderman id, it carries over when the nearest angry one changes back and forth. an id that ran
    // out stays given up (ids are not pruned: one that teleports off and comes back keeps its id and its spent clock, and a
    // run's worth of ints is nothing)
    static final class AngryClock {
        private final Map<Integer, Integer> ticks = new HashMap<>();
        private final Set<Integer> gaveUp = new HashSet<>();

        // one more tick on this one, true once it is over the limit
        boolean tick(int id, double limitSeconds) {
            int t = ticks.merge(id, 1, Integer::sum);
            boolean over = t / 20.0 > limitSeconds;
            if (over) {
                gaveUp.add(id);
            }
            return over;
        }

        boolean givenUp(int id) {
            return gaveUp.contains(id);
        }

        void giveUp(int id) {
            gaveUp.add(id);
        }
    }

    // lava back offs against the enderman we were walking at: the same one every cycle is a foe that is not worth it, even
    // when each back off on its own looks fine. a new target starts the count over
    static final class LavaStrikes {
        static final int LIMIT = 3;
        private int id = -1;
        private int count;

        void pursue(int pursued) {
            if (pursued != id) {
                id = pursued;
                count = 0;
            }
        }

        // true on the strike that hits the limit
        boolean strike() {
            return id >= 0 && ++count >= LIMIT;
        }

        int id() {
            return id;
        }
    }

    // the stamp on nook is this fresh or it is a leftover from a hunt that stopped being ticked
    static boolean nookFresh(long lastTickAt, long now) {
        return lastTickAt != Long.MIN_VALUE && now - lastTickAt <= NOOK_FRESH_TICKS;
    }

    // stalled (no real progress for a while) or out of total time
    static boolean walkOver(double stallSeconds, double totalSeconds) {
        return stallSeconds > HOTSPOT_STALL_SECONDS || totalSeconds > HOTSPOT_WALK_CAP_SECONDS;
    }

    static boolean walkProgressed(double xz, double bestXZ) {
        return xz < bestXZ - HOTSPOT_PROGRESS_BLOCKS;
    }

    // we got there once, nothing gave the walk up, and now we are well off the forest again
    static boolean driftedOut(boolean arrived, boolean gaveUp, double xz) {
        return arrived && !gaveUp && xz > HOTSPOT_DRIFT_BLOCKS;
    }

    static boolean awayLong(long lastTickAt, long now) {
        return lastTickAt != Long.MIN_VALUE && now - lastTickAt > AWAY_REOPEN_TICKS;
    }

    // the walk starts over: the hunt object outlives food trips, so a latched "done" would never walk again
    void reopenWalk() {
        hotspotDone = false;
        hotspotArrived = false;
        hotspotGaveUp = false;
        hotspotWalkingTo = null;
        bestXZ = Double.MAX_VALUE;
        hotspotSpent.reset();
        hotspotStall.reset();
    }

    public String step() {
        return step;
    }

    // the nook is up: a still, patient stage that makes no distance and picks nothing up until something walks into it
    // (a hunt that finished or got swapped out mid-nook leaves nook set, so it only counts while we are being ticked)
    public boolean inNook(long gameTime) {
        return nook != null && nookFresh(lastTickAt, gameTime);
    }

    // the cheap-then-dear enderman check the nook and the phases share: one in range and in sight
    public static boolean endermanNear(AltoClef mod) {
        return EndermanNook.latchSees(nearestEnderman(mod, null, null));
    }

    // the angry ones we watched last tick that are dead now: those are kills
    private void noteKills(AltoClef mod) {
        for (Enderman man : watched) {
            if (man.isDeadOrDying()) {
                onKill.run();
            }
        }
        watched.clear();
        for (Enderman man : mod.getEntityTracker().getTrackedEntities(Enderman.class)) {
            if (man.isAlive() && man.isCreepy()) {
                watched.add(man);
            }
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the angry cap, the hotspot walk and its clock are NOT reset here: onStart runs again after every interrupt, and
        // mob defense flickering would hand them a fresh 20 s / walk clock each time
        endermanLatch.reset();
        // the flee task was built around lava from before the interrupt
        away = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != dimension) {
            return say("Heading to " + HudText.dimension(dimension), goToDimension);
        }
        long gameTime = mod.getWorld().getGameTime();
        // a nook left over from before we stopped being ticked is dropped: if it was still being built its clock ran on without us and it would
        // fail on the first tick back, which is not a nook failure (a built one would be fine, but a fresh find() is cheap)
        if (nook != null && !nookFresh(lastTickAt, gameTime)) {
            nook = null;
        }
        if (awayLong(lastTickAt, gameTime)) {
            reopenWalk();
            Debug.logMessage("Pearl hunt back after time away, walking to the forest again");
        }
        trackHp(mod, gameTime);
        lastTickAt = gameTime;
        noteKills(mod);
        // (any other branch below that returns first still counts as calm)
        awayCount = awayCount.step(gameTime, false);
        // before the nook: a nook built by the lava would sit there and put the walk off
        armLavaSpot(mod, gameTime, false);
        if (leaveSpot != null) {
            if (gameTime < leaveUntil && !leaveSpot.isFinished(mod)) {
                return say("Leaving the lava", leaveSpot);
            }
            leaveSpot = null;
        }
        if (dimension == Dimension.NETHER) {
            Task hole = nookStep(mod, gameTime);
            if (hole != null) {
                // the combat brain keeps its hands off endermen while this is stamped (only with the nook up, the plain hunt
                // in the open still runs by the usual rules), see EndermanNook.brainStandsDown
                PearlHuntHook.mark(gameTime);
                return say("Hunting endermen from a nook", hole);
            }
            PearlHuntHook.clear();
        }
        Task angry = fightAngry(mod);
        if (angry != null) {
            return say("Fighting an angry enderman", angry);
        }
        Task grab = pickupStep(mod);
        if (grab != null) {
            return say("Picking up an ender pearl", grab);
        }
        Task walk = walkToHotspot(mod);
        if (walk != null) {
            return say("Heading for the warped forest", walk);
        }
        if (walkHeld) {
            // (the loot task wanders when nothing is around, which is no way to heal)
            return say("Recovering before the walk", null);
        }
        // read before the back off below: it may be the tick that picks the target, and the back off clears it again
        int lootId = loot.pursuingId();
        if (lootId >= 0) {
            lavaStrikes.pursue(lootId);
        }
        if (backsOffFrom(dimension == Dimension.NETHER && mod.getEntityTracker().entityFound(Enderman.class), anyLootable(mod))) {
            // no nook to do it from: lava or fire means step away, low hp means wait while it heals
            List<BlockPos> lava = exposedLava(mod.getWorld(), mod.getPlayer().blockPosition(), EndermanNook.AGGRO_LAVA_GAP);
            EndermanNook.Stance stance = EndermanNook.stance(!lava.isEmpty(), mod.getPlayer().isOnFire(), mod.getPlayer().getHealth(),
                    EndermanNook.hpRising(gameTime, lastRiseAt));
            // a flee that started runs to the end even when the lava is out of reach by the first step, or it is a one block shuffle
            boolean fleeing = stance == EndermanNook.Stance.AWAY || (away != null && !away.isFinished(mod));
            awayCount = awayCount.step(gameTime, fleeing);
            // one strike per back off, on the tick it starts. the id was saved while looting, the loot task may have let go of it by now
            if (fleeing && gameTime - lastFleeAt > 1 && !lava.isEmpty() && lavaStrikes.strike()) {
                angrySpent.giveUp(lavaStrikes.id());
            }
            if (fleeing) {
                lastFleeAt = gameTime;
            }
            if (fleeing && awayCount.ticks() <= AWAY_GIVE_UP_TICKS) {
                return say("Backing off", stepAway(mod, lava));
            }
            away = null;
            // the give up fired: this spot is the problem, not the endermen in it
            if (fleeing) {
                armLavaSpot(mod, gameTime, true);
                if (leaveSpot != null) {
                    return say("Leaving the lava", leaveSpot);
                }
            }
            if (stance == EndermanNook.Stance.WAIT) {
                return say("Recovering before the next enderman", null);
            }
        } else {
            away = null;
        }
        // time spent as the loot target counts toward the same give up as the angry fight, an enderman the walk cannot reach
        // would otherwise bounce between this and the hotspot walk (the latch sees it, drops, sees it) for minutes
        if (lootId >= 0) {
            angrySpent.tick(lootId, ANGRY_SECONDS);
        }
        return say(mod.getEntityTracker().entityFound(Enderman.class) ? "Collecting Ender Pearls" : "Looking for endermen", loot);
    }

    // the lava spot goes up when the back off gave up or the kill tasks have spent 20 s of the last minute standing by lava, and
    // we walk off it once. a second trigger while it is live changes nothing
    private void armLavaSpot(AltoClef mod, long gameTime, boolean awayGaveUp) {
        var me = mod.getPlayer();
        if (!LavaSpot.trigger(awayGaveUp, AbstractDoToEntityTask.lavaHoldTicks(me.tickCount))) {
            return;
        }
        AbstractDoToEntityTask.clearLavaHolds();
        if (lavaSpot.arm(me.getX(), me.getY(), me.getZ(), gameTime)) {
            leaveSpot = new RunAwayFromPositionTask(LEAVE_BLOCKS, me.blockPosition());
            leaveUntil = gameTime + LEAVE_TICKS;
        }
    }

    // not worth the walk: on a lava shore, or inside the lava spot while it lasts
    private boolean avoid(Entity man) {
        return onLavaShore(man) || lavaSpot.covers(man.getX(), man.getY(), man.getZ(), man.level().getGameTime());
    }

    // loot filter only, not avoid(): an angry one already coming at us near a brute still has to be fought
    private boolean lootTarget(Entity man) {
        if (!lootable(angrySpent.givenUp(man.getId()), () -> avoid(man))) {
            return false;
        }
        boolean skip = skipNearHeavy(man.getX(), man.getZ(), heavyPositions(AltoClef.getInstance()), nearBastion.test(man.getBlockX(), man.getBlockZ()));
        if (skip && skipLogged.add(man.getId())) {
            Debug.logInternal("pearl hunt: skipping enderman " + man.getId() + ", it stands by a brute or a bastion");
        }
        return !skip;
    }

    // the same HEAVY list the combat brain runs from (FoeRules), read off the tracker
    private static List<double[]> heavyPositions(AltoClef mod) {
        List<double[]> out = new ArrayList<>();
        for (Class<? extends Entity> type : HEAVY_TYPES) {
            for (Entity e : mod.getEntityTracker().getTrackedEntities(type)) {
                if (e.isAlive() && FoeRules.isHeavy(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath())) {
                    out.add(new double[]{e.getX(), e.getZ()});
                }
            }
        }
        return out;
    }

    // an enderman parked by a brute or inside a known bastion is bait: we run from them, then the chase walks us right back
    static boolean skipNearHeavy(double manX, double manZ, List<double[]> heavyXZ, boolean nearBastion) {
        if (nearBastion) {
            return true;
        }
        for (double[] h : heavyXZ) {
            double dx = h[0] - manX;
            double dz = h[1] - manZ;
            if (dx * dx + dz * dz <= HEAVY_SKIP_BLOCKS * HEAVY_SKIP_BLOCKS) {
                return true;
            }
        }
        return false;
    }

    // a pearl lying close by comes before the hotspot walk and the recovery wait, the loot task below is the only other thing that
    // picks anything up and kills mostly happen outside it. the tracker only lists drops that passed the lava and wet checks and
    // skips banned ones, and the pickup task bans what it can't reach, so an unreachable pearl drops out of this by itself
    private Task pickupStep(AltoClef mod) {
        if (closestPearlDrop(mod, pearlTarget).isEmpty()) {
            return null;
        }
        if (pickup == null) {
            pickup = new PickupDroppedItemTask(pearlTarget, true);
        }
        return pickup;
    }

    private static Optional<ItemEntity> closestPearlDrop(AltoClef mod, ItemTarget target) {
        var me = mod.getPlayer();
        return mod.getEntityTracker().getClosestItemDrop(me.position(), e -> pickupInReach(e.distanceToSqr(me)), target);
    }

    // the phase asks this before it wanders off to sweep for a forest: a pearl on the floor is the hunt's to fetch
    public static boolean pearlDropNear(AltoClef mod) {
        return closestPearlDrop(mod, new ItemTarget(Items.ENDER_PEARL, 1)).isPresent();
    }

    static boolean pickupInReach(double distSqr) {
        return distSqr <= PICKUP_BLOCKS * PICKUP_BLOCKS;
    }

    // back off only when there is an enderman the loot walk would actually go for, not from nothing
    static boolean backsOffFrom(boolean endermanFound, boolean anyLootable) {
        return endermanFound && anyLootable;
    }

    private boolean anyLootable(AltoClef mod) {
        for (Enderman man : mod.getEntityTracker().getTrackedEntities(Enderman.class)) {
            if (man.isAlive() && lootTarget(man)) {
                return true;
            }
        }
        return false;
    }

    // a back off streak: the game time of its first flee and of the last one. the streak has a short memory (AWAY_CALM_RESET_TICKS)
    // so a back off that flips with the lava distance is still one streak, and it is read off the clock so the ticks spent
    // fighting or walking instead count as calm too. the length is wall time since the first flee, not the flee ticks in it:
    // a flip flees a few ticks out of every twenty, and counting only those takes minutes to get anywhere
    record AwayCount(long first, long lastFlee) {
        static final AwayCount NONE = new AwayCount(Long.MAX_VALUE / 2, Long.MIN_VALUE / 2);

        AwayCount step(long now, boolean fleeing) {
            if (fleeing) {
                return now - lastFlee >= AWAY_CALM_RESET_TICKS ? new AwayCount(now, now) : new AwayCount(first, now);
            }
            return now - lastFlee >= AWAY_CALM_RESET_TICKS ? NONE : this;
        }

        int ticks() {
            return lastFlee < first ? 0 : (int) Math.min(Integer.MAX_VALUE, lastFlee - first + 1);
        }
    }

    private Task say(String hud, Task child) {
        step = hud;
        setDebugState(hud, hud);
        return child;
    }

    // the nook when there is an enderman near enough to use it with, null when the plain hunt below should run. a nook that
    // failed is not tried again for a bit, and after a few the plain hunt is all there is
    private Task nookStep(AltoClef mod, long gameTime) {
        // off by default (altoPearlNook): the plain hunt below is the whole thing
        if (!Baritone.settings().altoPearlNook.value) {
            nook = null;
            return null;
        }
        if (nook != null && nook.isFinished(mod)) {
            // an idle-out counts too: a nook that sat there with nothing coming is as spent as one that broke
            nookFails++;
            nook = null;
            nookRetryAt = gameTime + NOOK_RETRY_TICKS;
        }
        if (nook == null) {
            if (!EndermanNook.latchSees(nearestEnderman(mod, nookFight, angrySpent)) || nookFails >= NOOK_TRIES || gameTime < nookRetryAt) {
                return null;
            }
            nook = new NookHuntTask(nookFight);
        }
        return nook;
    }

    // the one flee task for the whole gate, kept until it arrives. fleeing every lava block (not just the nearest) because the goal is
    // flat in xz and a lake has plenty of blocks. burning with no lava about flees from where we stand
    private Task stepAway(AltoClef mod, List<BlockPos> lava) {
        if (away != null && away.isFinished(mod)) {
            away = null;
        }
        if (away == null) {
            BlockPos[] danger = lava.isEmpty() ? new BlockPos[]{mod.getPlayer().blockPosition()} : lava.toArray(new BlockPos[0]);
            away = new RunAwayFromPositionTask(AWAY_BLOCKS, danger);
        }
        return away;
    }

    // hp went up this tick (or is fine): the clock the low hp wait runs on. a gap since the last tick restarts it,
    // an interrupt is not 30 s of flat hp
    private void trackHp(AltoClef mod, long gameTime) {
        float hp = mod.getPlayer().getHealth();
        if (hp > lastHp || hp >= EndermanNook.AGGRO_MIN_HP || lastRiseAt == Long.MIN_VALUE || !nookFresh(lastTickAt, gameTime)) {
            lastRiseAt = gameTime;
        }
        lastHp = hp;
    }

    // exposed lava (nothing solid right on top of it) from one below the middle up, inside r. under a floor it is just scenery
    public static List<BlockPos> exposedLava(Level level, BlockPos at, int r) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = at.offset(x, y, z);
                    if (!level.getFluidState(p).is(FluidTags.LAVA)) {
                        continue;
                    }
                    BlockPos up = p.above();
                    if (EndermanNook.lavaExposed(true, level.getBlockState(up).isCollisionShapeFullBlock(level, up))) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    // the loot walk goes for an enderman unless it stands on a lava shore (the bot ends up at the lava with nowhere to go) or the
    // angry fight already spent its 20 s on it: that one is the same unreachable mob, and as a fresh kill task it would get fresh
    // stand caps and stand there again
    // the angry fight skips the lava shore ones too: the bot would walk to the lava edge for a mob that never reaches it
    static boolean fightable(boolean angryAndAlive, boolean gaveUpAngry, java.util.function.BooleanSupplier onLavaShore) {
        return angryAndAlive && lootable(gaveUpAngry, onLavaShore);
    }

    static boolean lootable(boolean gaveUpAngry, java.util.function.BooleanSupplier onLavaShore) {
        return !gaveUpAngry && !onLavaShore.getAsBoolean();
    }

    private static boolean onLavaShore(Entity man) {
        return !exposedLava(AltoClef.getInstance().getWorld(), man.blockPosition(), EndermanNook.AGGRO_LAVA_GAP).isEmpty();
    }

    // one give up turns off the loot target and the latch both: either clock saying so is enough (null = no such clock)
    static boolean givenUpOn(int id, AngryClock nookGaveUp, AngryClock angryGaveUp) {
        return nookGaveUp != null && nookGaveUp.givenUp(id) || angryGaveUp != null && angryGaveUp.givenUp(id);
    }

    // distance to the nearest tracked enderman we have line of sight to, infinity for none (see EndermanNook.nearestSeen)
    private static double nearestEnderman(AltoClef mod, AngryClock nookGaveUp, AngryClock angryGaveUp) {
        List<Enderman> men = mod.getEntityTracker().getTrackedEntities(Enderman.class);
        double[] dist = new double[men.size()];
        for (int i = 0; i < dist.length; i++) {
            Enderman man = men.get(i);
            double d = man.distanceTo(mod.getPlayer());
            // (the cheap distance first, the ray is the dear part and the latch does not care past its range)
            // (one the nook gave up on would keep re-arming the latch and the nook with it)
            boolean skip = givenUpOn(man.getId(), nookGaveUp, angryGaveUp);
            dist[i] = !skip && man.isAlive() && d <= EndermanNook.LATCH_RANGE && man.hasLineOfSight(mod.getPlayer()) ? d : Double.NaN;
        }
        return EndermanNook.nearestSeen(dist);
    }

    // an enderman that is already after us dies first: it is coming anyway and it hits like a truck when ignored. the
    // nearest one first, and only inside the range where it walks to us (past it, it teleports to us by itself).
    // but only for a while per target, one we cannot reach (lava, a ledge) does not get to stall the whole hunt
    private Task fightAngry(AltoClef mod) {
        // the one we are on stays the one until it is dead, gone, far or spent: isCreepy flickers when it looks away, and
        // every swap to the loot task restarts the kill task and its caps
        if (angryTarget instanceof Enderman latched && angryTask != null) {
            if (stillLatched(latched.isAlive() && !latched.isRemoved(), latched.distanceTo(mod.getPlayer()), angrySpent.givenUp(latched.getId()), avoid(latched))) {
                return angrySpent.tick(latched.getId(), ANGRY_SECONDS) ? null : angryTask;
            }
            // (let go for real: a stale latch would come back the moment the mob walked into range again, skipping isCreepy)
            angryTarget = null;
            angryTask = null;
        }
        List<Enderman> men = mod.getEntityTracker().getTrackedEntities(Enderman.class);
        double[] dist = new double[men.size()];
        for (int i = 0; i < dist.length; i++) {
            Enderman man = men.get(i);
            dist[i] = fightable(man.isAlive() && man.isCreepy(), angrySpent.givenUp(man.getId()), () -> avoid(man)) ? man.distanceTo(mod.getPlayer()) : Double.NaN;
        }
        int at = EndermanNook.nearestAngry(dist);
        if (at < 0) {
            return null;
        }
        Enderman man = men.get(at);
        if (angryTarget != man) {
            angryTarget = man;
            angryTask = new KillEntityTask(man);
        }
        // per enderman: two that trade places as the nearest must not each get a fresh clock every time
        if (angrySpent.tick(man.getId(), ANGRY_SECONDS)) {
            return null;
        }
        return angryTask;
    }

    static boolean stillLatched(boolean aliveAndHere, double dist, boolean gaveUp, boolean avoided) {
        return aliveAndHere && dist <= EndermanNook.ANGRY_RANGE && !gaveUp && !avoided;
    }

    // null = not walking (no hotspot, there already, no endermen needed from there, or the walk took too long)
    private Task walkToHotspot(AltoClef mod) {
        walkHeld = false;
        RunState.Pos spot = hotspot.get();
        boolean endermanHere = endermanLatch.update(EndermanNook.latchSees(nearestEnderman(mod, nookFight, angrySpent)), System.currentTimeMillis() / 1000.0);
        if (spot == null) {
            return null;
        }
        // a new hotspot is a new walk, the same one coming back after an interrupt is not
        if (!spot.equals(hotspotWalkingTo)) {
            hotspotDone = false;
        }
        double dx = mod.getPlayer().getX() - spot.x;
        double dz = mod.getPlayer().getZ() - spot.z;
        double xz = Math.hypot(dx, dz);
        if (hotspotDone && driftedOut(hotspotArrived, hotspotGaveUp, xz)) {
            reopenWalk();
        }
        if (hotspotDone || endermanHere) {
            return null;
        }
        if (!spot.equals(hotspotWalkingTo)) {
            hotspotWalkingTo = spot;
            hotspotSpent.reset();
            hotspotStall.reset();
            bestXZ = Double.MAX_VALUE;
            // near the spot in 3D, not its column: GoalXZ is happy on top of a hill. the block itself may sit under fungus
            toHotspot = new GetWithinRangeOfBlockTask(new BlockPos(spot.x, spot.y, spot.z), (int) AT_HOTSPOT_Y);
        }
        boolean there = EndermanNook.atHotspot(dx, mod.getPlayer().getY() - spot.y, dz, AT_HOTSPOT_BLOCKS, AT_HOTSPOT_Y);
        // hurt: stand still and heal first, the walk waits (its clock too). hp that stopped coming back is not waited on
        if (!there && !EndermanNook.mayWalkIn(mod.getPlayer().getHealth()) && EndermanNook.hpRising(mod.getWorld().getGameTime(), lastRiseAt)) {
            walkHeld = true;
            return null;
        }
        if (walkProgressed(xz, bestXZ)) {
            bestXZ = xz;
            hotspotStall.reset();
        }
        hotspotSpent.tick();
        hotspotStall.tick();
        if (there || walkOver(hotspotStall.spentSeconds(), hotspotSpent.spentSeconds())) {
            hotspotDone = true;
            hotspotArrived = there;
            hotspotGaveUp = !there;
            return null;
        }
        return toHotspot;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (an interrupt is a meal, not the end of the hunt: the stamp goes stale by itself soon enough)
        if (!isInterrupting()) {
            PearlHuntHook.clear();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Items.ENDER_PEARL) >= pearlsTotal;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PearlHuntTask task && task.pearlsTotal == pearlsTotal && task.dimension == dimension;
    }

    @Override
    protected String toDebugString() {
        return "Hunting endermen in " + dimension + " for " + pearlsTotal + " pearls";
    }

    @Override
    protected String toHudString() {
        return "Hunting Endermen for " + HudText.count(pearlsTotal, "Ender Pearl");
    }
}
