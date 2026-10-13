package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskClock;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

// pearls from endermen, in the dimension we are told to hunt in. in the nether it walks to the warped forest first when
// one is known (the densest enderman farm there is that needs no gold), in the overworld it just hunts what it finds.
// angry endermen (the ones that already saw us) die first, like KillEndermanTask did, and the pearls are picked up by
// the resource task underneath. the stronghold phases can use the overworld mode as their pearl top up
public class PearlHuntTask extends Task {
    // close enough to the forest that the endermen are in view distance
    private static final double AT_HOTSPOT_BLOCKS = 40;
    // do not stand around in the dark for an angry enderman forever
    private static final double ANGRY_RANGE = 256;
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

    private GetToXZTask toHotspot;
    private RunState.Pos hotspotWalkingTo;
    // these two are phase clocks, not time on task: each only ticks in its own branch and restarts with its target.
    // the caps survive interrupts, and a long fight with something else (or a death) in between does not eat them
    private final TaskClock hotspotSpent = new TaskClock();
    private boolean hotspotDone;
    private Entity angryTarget;
    private KillEntityTask angryTask;
    private final TaskClock angrySpent = new TaskClock();
    private final Set<Entity> angryGivenUp = Collections.newSetFromMap(new IdentityHashMap<>());
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
        Task angry = fightAngry(mod);
        if (angry != null) {
            return say("Fighting an angry enderman", angry);
        }
        Task walk = walkToHotspot(mod);
        if (walk != null) {
            return say("Heading for the warped forest", walk);
        }
        return say(mod.getEntityTracker().entityFound(Enderman.class) ? "Collecting Ender Pearls" : "Looking for endermen", loot);
    }

    private Task say(String hud, Task child) {
        step = hud;
        setDebugState(hud, hud);
        return child;
    }

    // an enderman that is already after us dies first: it is coming anyway and it hits like a truck when ignored.
    // but only for a while per target, one we cannot reach (lava, a ledge) does not get to stall the whole hunt
    private Task fightAngry(AltoClef mod) {
        for (Enderman man : mod.getEntityTracker().getTrackedEntities(Enderman.class)) {
            if (!man.isAlive() || !man.isCreepy() || angryGivenUp.contains(man)
                    || !man.position().closerThan(mod.getPlayer().position(), ANGRY_RANGE)) {
                continue;
            }
            if (angryTarget != man) {
                angryTarget = man;
                angryTask = new KillEntityTask(man);
                angrySpent.reset();
            }
            angrySpent.tick();
            if (angrySpent.over(ANGRY_SECONDS)) {
                angryGivenUp.add(man);
                continue;
            }
            return angryTask;
        }
        return null;
    }

    // null = not walking (no hotspot, there already, no endermen needed from there, or the walk took too long)
    private Task walkToHotspot(AltoClef mod) {
        RunState.Pos spot = hotspot.get();
        boolean endermanHere = endermanLatch.update(mod.getEntityTracker().entityFound(Enderman.class), System.currentTimeMillis() / 1000.0);
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
            toHotspot = new GetToXZTask(spot.x, spot.z);
        }
        double dx = mod.getPlayer().getX() - spot.x;
        double dz = mod.getPlayer().getZ() - spot.z;
        boolean there = dx * dx + dz * dz <= AT_HOTSPOT_BLOCKS * AT_HOTSPOT_BLOCKS;
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
