package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;

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

    private final int pearlsTotal;
    private final Dimension dimension;
    private final Supplier<RunState.Pos> hotspot;
    private final KillAndLootTask loot;
    private final Task goToDimension;

    private GetToXZTask toHotspot;
    private RunState.Pos hotspotWalkingTo;
    private long hotspotSinceMs;
    private boolean hotspotDone;
    private Entity angryTarget;
    private KillEntityTask angryTask;
    private String step = "Looking for endermen";

    public PearlHuntTask(int pearlsTotal, Dimension dimension, Supplier<RunState.Pos> hotspot) {
        this.pearlsTotal = pearlsTotal;
        this.dimension = dimension;
        this.hotspot = hotspot;
        loot = new KillAndLootTask(EnderMan.class, new ItemTarget(Items.ENDER_PEARL, pearlsTotal));
        loot.forceDimension(dimension);
        goToDimension = new DefaultGoToDimensionTask(dimension);
    }

    public String step() {
        return step;
    }

    @Override
    protected void onStart(AltoClef mod) {
        hotspotDone = false;
        hotspotWalkingTo = null;
        angryTarget = null;
        angryTask = null;
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
        return say(mod.getEntityTracker().entityFound(EnderMan.class) ? "Collecting Ender Pearls" : "Looking for endermen", loot);
    }

    private Task say(String hud, Task child) {
        step = hud;
        setDebugState(hud, hud);
        return child;
    }

    // an enderman that is already after us dies first: it is coming anyway and it hits like a truck when ignored
    private Task fightAngry(AltoClef mod) {
        for (EnderMan man : mod.getEntityTracker().getTrackedEntities(EnderMan.class)) {
            if (man.isAlive() && man.isCreepy() && man.position().closerThan(mod.getPlayer().position(), ANGRY_RANGE)) {
                if (angryTarget != man) {
                    angryTarget = man;
                    angryTask = new KillEntityTask(man);
                }
                return angryTask;
            }
        }
        return null;
    }

    // null = not walking (no hotspot, there already, no endermen needed from there, or the walk took too long)
    private Task walkToHotspot(AltoClef mod) {
        RunState.Pos spot = hotspot.get();
        if (spot == null || hotspotDone || mod.getEntityTracker().entityFound(EnderMan.class)) {
            return null;
        }
        if (!spot.equals(hotspotWalkingTo)) {
            hotspotWalkingTo = spot;
            hotspotSinceMs = System.currentTimeMillis();
            toHotspot = new GetToXZTask(spot.x, spot.z);
        }
        double dx = mod.getPlayer().getX() - spot.x;
        double dz = mod.getPlayer().getZ() - spot.z;
        boolean there = dx * dx + dz * dz <= AT_HOTSPOT_BLOCKS * AT_HOTSPOT_BLOCKS;
        boolean tooLong = System.currentTimeMillis() - hotspotSinceMs > HOTSPOT_WALK_SECONDS * 1000;
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
