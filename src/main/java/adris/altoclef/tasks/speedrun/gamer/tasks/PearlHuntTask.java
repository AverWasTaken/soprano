package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasks.speedrun.gamer.EndermanNook;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.PearlHuntHook;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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

    private final int pearlsTotal;
    private final Dimension dimension;
    private final Supplier<RunState.Pos> hotspot;
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
    private final TaskClock angrySpent = new TaskClock();
    private final Set<Entity> angryGivenUp = Collections.newSetFromMap(new IdentityHashMap<>());
    private NookHuntTask nook;
    private int nookFails;
    private long nookRetryAt = Long.MIN_VALUE;
    private String step = "Looking for endermen";
    // same latch the phase uses, an enderman behind a pillar must not switch the hotspot walk off and on
    private final HoldLatch endermanLatch = new HoldLatch(ENDERMAN_HOLD_SECONDS);

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot) {
        this.pearlsTotal = pearlsTotal;
        this.dimension = dimension;
        this.hotspot = hotspot;
        loot = new KillAndLootTask(Enderman.class, new ItemTarget(Items.ENDER_PEARL, pearlsTotal));
        loot.forceDimension(dimension);
        goToDimension = new DefaultGoToDimensionTask(dimension);
    }

    public String step() {
        return step;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the angry cap, the hotspot walk and its clock are NOT reset here: onStart runs again after every interrupt, and
        // mob defense flickering would hand them a fresh 20 s / 150 s each time
        endermanLatch.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != dimension) {
            return say("Heading to " + HudText.dimension(dimension), goToDimension);
        }
        long gameTime = mod.getWorld().getGameTime();
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
        if (dimension == Dimension.NETHER && !mayStartOne(mod)) {
            // no nook to do it from and not in shape to start one in the open: stand and let it pass
            return say("Recovering before the next enderman", null);
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
        if (nook != null && nook.isFinished(mod)) {
            if (nook.failed()) {
                nookFails++;
            }
            nook = null;
            nookRetryAt = gameTime + NOOK_RETRY_TICKS;
        }
        if (nook == null) {
            if (!EndermanNook.latchSees(nearestEnderman(mod)) || nookFails >= NOOK_TRIES || gameTime < nookRetryAt) {
                return null;
            }
            nook = new NookHuntTask();
        }
        return nook;
    }

    // out in the open with no nook: nothing is started on purpose while burning, hurt or next to lava
    private boolean mayStartOne(AltoClef mod) {
        if (!mod.getEntityTracker().entityFound(Enderman.class)) {
            return true;
        }
        BlockPos at = mod.getPlayer().blockPosition();
        int r = EndermanNook.AGGRO_LAVA_GAP;
        boolean lava = false;
        for (int x = -r; x <= r && !lava; x++) {
            for (int y = -r; y <= r && !lava; y++) {
                for (int z = -r; z <= r && !lava; z++) {
                    lava = mod.getWorld().getFluidState(at.offset(x, y, z)).is(FluidTags.LAVA);
                }
            }
        }
        // (the "no second angry one" gate is the nook's: counting the ones we gave up on here would park us in the open for good)
        return EndermanNook.mayAggro(mod.getPlayer().isOnFire(), mod.getPlayer().getHealth(), lava ? 1 : 99, 0, Double.MAX_VALUE);
    }

    // distance to the nearest tracked enderman we have line of sight to, infinity for none (see EndermanNook.nearestSeen)
    private static double nearestEnderman(AltoClef mod) {
        List<Enderman> men = mod.getEntityTracker().getTrackedEntities(Enderman.class);
        double[] dist = new double[men.size()];
        for (int i = 0; i < dist.length; i++) {
            Enderman man = men.get(i);
            double d = man.distanceTo(mod.getPlayer());
            // (the cheap distance first, the ray is the dear part and the latch does not care past its range)
            dist[i] = man.isAlive() && d <= EndermanNook.LATCH_RANGE && man.hasLineOfSight(mod.getPlayer()) ? d : Double.NaN;
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
            dist[i] = man.isAlive() && man.isCreepy() && !angryGivenUp.contains(man) ? man.distanceTo(mod.getPlayer()) : Double.NaN;
        }
        int at = EndermanNook.nearestAngry(dist);
        if (at < 0) {
            return null;
        }
        Enderman man = men.get(at);
        if (angryTarget != man) {
            angryTarget = man;
            angryTask = new KillEntityTask(man);
            angrySpent.reset();
        }
        angrySpent.tick();
        if (angrySpent.over(ANGRY_SECONDS)) {
            angryGivenUp.add(man);
            return null;
        }
        return angryTask;
    }

    // null = not walking (no hotspot, there already, no endermen needed from there, or the walk took too long)
    private Task walkToHotspot(AltoClef mod) {
        walkHeld = false;
        RunState.Pos spot = hotspot.get();
        boolean endermanHere = endermanLatch.update(EndermanNook.latchSees(nearestEnderman(mod)), System.currentTimeMillis() / 1000.0);
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
        // hurt: stand still and heal first, the walk waits (its clock too)
        if (!there && !EndermanNook.mayWalkIn(mod.getPlayer().getHealth())) {
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
        if (interruptTask == null) {
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
