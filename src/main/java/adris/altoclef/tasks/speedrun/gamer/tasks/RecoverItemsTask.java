package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

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
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_budget.elapsed()) {
            setDebugState("Out of time.", "Giving up on our stuff");
            _finished = true;
            return null;
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

    private ItemEntity closestDrop(AltoClef mod) {
        List<ItemEntity> drops = mod.getEntityTracker().getDroppedItems();
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : drops) {
            double d = e.position().distanceToSqr(_deathPos.getX() + 0.5, _deathPos.getY() + 0.5, _deathPos.getZ() + 0.5);
            if (d <= DROP_RADIUS * DROP_RADIUS && d < bestDist
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
