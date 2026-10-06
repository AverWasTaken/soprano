package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;

public class KillAndLootTask extends ResourceTask {

    private final Class<?> _toKill;

    private final Task _killTask;
    // -1 = any of the class. otherwise this one animal, and part of isEqual so a new target is a new task (the runner
    // keeps the old object when they compare equal, and the old one would still be chasing the wrong pig)
    private final int _onlyId;

    public KillAndLootTask(Class<?> toKill, Predicate<Entity> shouldKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _onlyId = -1;
        _killTask = new KillEntitiesTask(shouldKill, _toKill);
    }

    // this animal and no other
    public KillAndLootTask(Entity target, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = target.getClass();
        _onlyId = target.getId();
        int id = _onlyId;
        _killTask = new KillEntitiesTask(entity -> entity.getId() == id, _toKill);
    }

    public KillAndLootTask(Class<?> toKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _onlyId = -1;
        _killTask = new KillEntitiesTask(_toKill);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (!mod.getEntityTracker().entityFound(_toKill)) {
            if (isInWrongDimension(mod)) {
                setDebugState("Going to correct dimension.");
                return getToCorrectDimensionTask(mod);
            }
            setDebugState("Searching for mob...");
            return new TimeoutWanderTask();
        }
        // We found the mob!
        return _killTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof KillAndLootTask task) {
            return task._toKill.equals(_toKill) && task._onlyId == _onlyId;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Hunting " + HudText.plural(HudText.entityClass(_toKill)) + " for " + HudText.some(_itemTargets);
    }

    @Override
    protected String toDebugStringName() {
        return "Collect items from " + _toKill.toGenericString();
    }
}
