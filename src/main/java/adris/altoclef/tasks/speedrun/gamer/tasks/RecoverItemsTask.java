package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

// after a death: walk back to where we died and pick up whatever is lying around there. bounded on purpose, the pile might
// be in lava or the void and a bot that stares at it for ten minutes helps nobody
public class RecoverItemsTask extends Task {
    // close enough that the drops are loaded and the pickup task can see them
    private static final int ARRIVE_RANGE = 6;
    // drops spread out a bit when a player dies, and fall down slopes
    private static final double DROP_RADIUS = 24;
    // nothing to see for this long after we got there: it burned, it was eaten by a cactus, or somebody else got it
    private static final double EMPTY_SECONDS = 4;

    private final BlockPos _deathPos;
    private final TimerGame _budget;
    private final TimerGame _empty = new TimerGame(EMPTY_SECONDS);
    private final Task _walk;
    private final RecoverRules.Gate _gate = new RecoverRules.Gate();
    private Task _standOff;
    private Item _pickingUp;
    private Task _pickup;
    private boolean _finished;

    public RecoverItemsTask(BlockPos deathPos, double budgetSeconds) {
        _deathPos = deathPos;
        _budget = new TimerGame(budgetSeconds);
        _walk = new GetWithinRangeOfBlockTask(deathPos, ARRIVE_RANGE);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _budget.reset();
        _empty.reset();
        _gate.reset();
        _standOff = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_budget.elapsed()) {
            setDebugState("Out of time.", "Giving up on our stuff");
            _finished = true;
            return null;
        }
        if (spotIsHopeless(mod)) {
            Debug.logMessage("Our stuff is in lava or the void, leaving it.");
            _finished = true;
            return null;
        }
        // whatever killed us is probably still standing on it. walking in again is how the same two zombies got us twice
        List<Entity> threats = threatsAtPile(mod);
        switch (_gate.update(mod.getWorld().getGameTime() / 20.0, threats.size())) {
            case GIVE_UP -> {
                Debug.logMessage("Still " + threats.size() + " hostile(s) on our stuff after " + (int) RecoverRules.WAIT_SECONDS
                        + " s, giving it up.");
                setDebugState("Crowded.", "Giving up on our stuff");
                _finished = true;
                return null;
            }
            case WAIT -> {
                return standOff(mod, threats);
            }
            default -> {
                _standOff = null;
            }
        }
        if (mod.getPlayer().blockPosition().distSqr(_deathPos) > ARRIVE_RANGE * ARRIVE_RANGE) {
            _empty.reset();
            setDebugState("Walking back to where we died.", "Going back for our stuff");
            return _walk;
        }
        ItemEntity drop = closestDrop(mod);
        if (drop == null) {
            setDebugState("Nothing left here.", "Looking for our stuff");
            if (_empty.elapsed()) {
                _finished = true;
            }
            return null;
        }
        _empty.reset();
        setDebugState("Picking up a drop.", "Picking up our stuff");
        Item item = drop.getItem().getItem();
        if (item != _pickingUp) {
            _pickingUp = item;
            // "any amount": the pickup task goes for the nearest of this item, and we choose the next kind when it is gone
            _pickup = new PickupDroppedItemTask(new ItemTarget(item, Integer.MAX_VALUE), true);
        }
        return _pickup;
    }

    // the angry ones near the pile, if the world has loaded them (from far away it hasn't, they show up as we get closer
    // and the gate takes it from there)
    private List<Entity> threatsAtPile(AltoClef mod) {
        List<Entity> out = new ArrayList<>();
        try {
            for (Entity entity : mod.getEntityTracker().getHostiles()) {
                if (!(entity instanceof Mob mob) || !EntityHelper.isAngryAtPlayer(mod, mob)) continue;
                if (RecoverRules.threatens(mob.getX() - (_deathPos.getX() + 0.5), mob.getY() - _deathPos.getY(),
                        mob.getZ() - (_deathPos.getZ() + 0.5))) {
                    out.add(mob);
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread, an empty look this tick just means one more tick of walking
        }
        return out;
    }

    // wait it out somewhere the crowd can't see us. already far enough means standing still (the wheel is mob defense's if
    // anything does come)
    private Task standOff(AltoClef mod, List<Entity> threats) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity threat : threats) {
            nearest = Math.min(nearest, threat.distanceTo(mod.getPlayer()));
        }
        setDebugState("Something is standing on our stuff.", "Waiting for our stuff to clear");
        if (nearest >= RecoverRules.SAFE_DISTANCE) {
            _standOff = null;
            return null;
        }
        if (_standOff == null) {
            _standOff = new RunAwayFromHostilesTask(RecoverRules.SAFE_DISTANCE, false, () -> threatsAtPile(mod));
        }
        return _standOff;
    }

    private boolean spotIsHopeless(AltoClef mod) {
        Level level = mod.getWorld();
        // an unloaded chunk reads as air, so only judge it once we can see it
        boolean lava = level.isLoaded(_deathPos) && level.getFluidState(_deathPos).is(FluidTags.LAVA);
        return RecoverRules.hopeless(lava, _deathPos.getY(), level.getMinY());
    }

    private ItemEntity closestDrop(AltoClef mod) {
        List<ItemEntity> drops = mod.getEntityTracker().getDroppedItems();
        Level level = mod.getWorld();
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : drops) {
            double d = e.position().distanceToSqr(_deathPos.getX() + 0.5, _deathPos.getY() + 0.5, _deathPos.getZ() + 0.5);
            // a drop that slid into lava is a drop we are not going swimming for
            boolean lost = RecoverRules.hopeless(level.getFluidState(e.blockPosition()).is(FluidTags.LAVA), e.blockPosition().getY(), level.getMinY());
            if (d <= DROP_RADIUS * DROP_RADIUS && d < bestDist && !lost
                    && mod.getEntityTracker().isEntityReachable(e)) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RecoverItemsTask task && task._deathPos.equals(_deathPos);
    }

    @Override
    protected String toDebugString() {
        return "Recover items near " + _deathPos.toShortString();
    }

    @Override
    protected String toHudString() {
        return "Getting our stuff back near " + HudText.pos(_deathPos);
    }
}
