package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.PearlHuntHook;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // the walk to the forest is not allowed to take longer than this, after that we hunt wherever we are
    private static final double HOTSPOT_WALK_SECONDS = 150;
    // an angry one across a lava lake never arrives: after this long on one target it is somebody else's problem
    private static final double ANGRY_SECONDS = 20;
    private static final double ENDERMAN_HOLD_SECONDS = 6;
    private static final long NOOK_FRESH_TICKS = 200;
    private static final double AWAY_BLOCKS = 6;
    // 15 s of flailing about is enough, after that the hunt goes on from wherever we ended up
    private static final int AWAY_GIVE_UP_TICKS = 300;

    private final int pearlsTotal;
    private final Dimension dimension;
    private final Supplier<RunState.Pos> hotspot;
    // told when an enderman dies, and only then (a stare that goes nowhere is not progress, see NetherPhase.stallSeconds)
    private final Runnable onKill;
    // the angry ones seen last tick, so one that is dead now was one we killed
    private final List<Enderman> watched = new ArrayList<>();
    private final KillAndLootTask loot;
    private final Task goToDimension;

    private GetWithinRangeOfBlockTask toHotspot;
    // the walk is waiting on hp, not done
    private boolean walkHeld;
    private RunState.Pos hotspotWalkingTo;
    // these two are phase clocks, not time on task: each only ticks in its own branch and restarts with its target.
    // the caps survive interrupts, and a long fight with something else (or a death) in between does not eat them
    private final TaskClock hotspotSpent = new TaskClock();
    private boolean hotspotDone;
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
    private int awayTicks;
    // same latch the phase uses, an enderman behind a pillar must not switch the hotspot walk off and on
    private final HoldLatch endermanLatch = new HoldLatch(ENDERMAN_HOLD_SECONDS);

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot) {
        this(pearlsTotal, dimension, hotspot, () -> {
        });
    }

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot, Runnable onKill) {
        this.onKill = onKill;
        this.pearlsTotal = pearlsTotal;
        this.dimension = dimension;
        this.hotspot = hotspot;
        // an enderman on a lava shore is not worth the walk, the bot ends up standing at the lava with nowhere to go
        loot = new KillAndLootTask(Enderman.class, man -> lootable(angrySpent.givenUp(man.getId()), () -> onLavaShore(man)), true, new ItemTarget(Items.ENDER_PEARL, pearlsTotal));
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
    }

    // the stamp on nook is this fresh or it is a leftover from a hunt that stopped being ticked
    static boolean nookFresh(long lastTickAt, long now) {
        return lastTickAt != Long.MIN_VALUE && now - lastTickAt <= NOOK_FRESH_TICKS;
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
        return EndermanNook.latchSees(nearestEnderman(mod, null));
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
        // mob defense flickering would hand them a fresh 20 s / 150 s each time
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
        trackHp(mod, gameTime);
        lastTickAt = gameTime;
        noteKills(mod);
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
        Task walk = walkToHotspot(mod);
        if (walk != null) {
            return say("Heading for the warped forest", walk);
        }
        if (walkHeld) {
            // (the loot task wanders when nothing is around, which is no way to heal)
            return say("Recovering before the walk", null);
        }
        if (dimension == Dimension.NETHER && mod.getEntityTracker().entityFound(Enderman.class)) {
            // no nook to do it from: lava or fire means step away, low hp means wait while it heals
            List<BlockPos> lava = exposedLava(mod.getWorld(), mod.getPlayer().blockPosition(), EndermanNook.AGGRO_LAVA_GAP);
            EndermanNook.Stance stance = EndermanNook.stance(!lava.isEmpty(), mod.getPlayer().isOnFire(), mod.getPlayer().getHealth(),
                    EndermanNook.hpRising(gameTime, lastRiseAt));
            awayTicks = stance == EndermanNook.Stance.AWAY ? awayTicks + 1 : 0;
            if (stance == EndermanNook.Stance.AWAY && awayTicks <= AWAY_GIVE_UP_TICKS) {
                return say("Backing off", stepAway(mod, lava));
            }
            away = null;
            if (stance == EndermanNook.Stance.WAIT) {
                return say("Recovering before the next enderman", null);
            }
        } else {
            away = null;
            awayTicks = 0;
        }
        return say(mod.getEntityTracker().entityFound(Enderman.class) ? "Collecting Ender Pearls" : "Looking for endermen", loot);
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
            if (!EndermanNook.latchSees(nearestEnderman(mod, nookFight)) || nookFails >= NOOK_TRIES || gameTime < nookRetryAt) {
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
    static List<BlockPos> exposedLava(Level level, BlockPos at, int r) {
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

    // distance to the nearest tracked enderman we have line of sight to, infinity for none (see EndermanNook.nearestSeen)
    private static double nearestEnderman(AltoClef mod, AngryClock gaveUp) {
        List<Enderman> men = mod.getEntityTracker().getTrackedEntities(Enderman.class);
        double[] dist = new double[men.size()];
        for (int i = 0; i < dist.length; i++) {
            Enderman man = men.get(i);
            double d = man.distanceTo(mod.getPlayer());
            // (the cheap distance first, the ray is the dear part and the latch does not care past its range)
            // (one the nook gave up on would keep re-arming the latch and the nook with it)
            boolean skip = gaveUp != null && gaveUp.givenUp(man.getId());
            dist[i] = !skip && man.isAlive() && d <= EndermanNook.LATCH_RANGE && man.hasLineOfSight(mod.getPlayer()) ? d : Double.NaN;
        }
        return EndermanNook.nearestSeen(dist);
    }

    // an enderman that is already after us dies first: it is coming anyway and it hits like a truck when ignored. the
    // nearest one first, and only inside the range where it walks to us (past it, it teleports to us by itself).
    // but only for a while per target, one we cannot reach (lava, a ledge) does not get to stall the whole hunt
    private Task fightAngry(AltoClef mod) {
        List<Enderman> men = mod.getEntityTracker().getTrackedEntities(Enderman.class);
        double[] dist = new double[men.size()];
        for (int i = 0; i < dist.length; i++) {
            Enderman man = men.get(i);
            dist[i] = fightable(man.isAlive() && man.isCreepy(), angrySpent.givenUp(man.getId()), () -> onLavaShore(man)) ? man.distanceTo(mod.getPlayer()) : Double.NaN;
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

    // null = not walking (no hotspot, there already, no endermen needed from there, or the walk took too long)
    private Task walkToHotspot(AltoClef mod) {
        walkHeld = false;
        RunState.Pos spot = hotspot.get();
        boolean endermanHere = endermanLatch.update(EndermanNook.latchSees(nearestEnderman(mod, nookFight)), System.currentTimeMillis() / 1000.0);
        if (spot == null) {
            return null;
        }
        // a new hotspot is a new walk, the same one coming back after an interrupt is not
        if (!spot.equals(hotspotWalkingTo)) {
            hotspotDone = false;
        }
        if (hotspotDone || endermanHere) {
            return null;
        }
        if (!spot.equals(hotspotWalkingTo)) {
            hotspotWalkingTo = spot;
            hotspotSpent.reset();
            // near the spot in 3D, not its column: GoalXZ is happy on top of a hill. the block itself may sit under fungus
            toHotspot = new GetWithinRangeOfBlockTask(new BlockPos(spot.x, spot.y, spot.z), (int) AT_HOTSPOT_Y);
        }
        double dx = mod.getPlayer().getX() - spot.x;
        double dz = mod.getPlayer().getZ() - spot.z;
        boolean there = EndermanNook.atHotspot(dx, mod.getPlayer().getY() - spot.y, dz, AT_HOTSPOT_BLOCKS, AT_HOTSPOT_Y);
        // hurt: stand still and heal first, the walk waits (its clock too). hp that stopped coming back is not waited on
        if (!there && !EndermanNook.mayWalkIn(mod.getPlayer().getHealth()) && EndermanNook.hpRising(mod.getWorld().getGameTime(), lastRiseAt)) {
            walkHeld = true;
            return null;
        }
        hotspotSpent.tick();
        boolean tooLong = hotspotSpent.over(HOTSPOT_WALK_SECONDS);
        if (there || tooLong) {
            hotspotDone = true;
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
