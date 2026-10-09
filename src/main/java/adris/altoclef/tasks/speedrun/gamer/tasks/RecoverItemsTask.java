package adris.altoclef.tasks.speedrun.gamer.tasks;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;

// after a death: walk back to where we died and pick up whatever is lying around there. bounded on purpose, the pile might
// be in lava or the void and a bot that stares at it for ten minutes helps nobody
public class RecoverItemsTask extends Task {
    // nothing to see for this long after we got there: it burned, it was eaten by a cactus, or somebody else got it
    private static final double EMPTY_SECONDS = 4;

    private final BlockPos _deathPos;
    private final TimerGame _budget;
    private final TimerGame _empty = new TimerGame(EMPTY_SECONDS);
    private final Task _walk;
    private final RecoverRules.Gate _gate = new RecoverRules.Gate();
    private final RecoverRules.Chase _chase = new RecoverRules.Chase();
    private Task _standOff;
    private final RecoverRules.Pin<ItemEntity> _pin = new RecoverRules.Pin<>();
    private PickupThisDrop _pickup;
    private boolean _finished;

    public RecoverItemsTask(BlockPos deathPos, double budgetSeconds) {
        _deathPos = deathPos;
        _budget = new TimerGame(budgetSeconds);
        _walk = new GetWithinRangeOfBlockTask(deathPos, (int) RecoverRules.ARRIVE_RANGE);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _budget.reset();
        _empty.reset();
        _gate.reset();
        // the chase counts and write offs are NOT reset here: onStart runs again after every interrupt, and mob defense
        // flickering at the pile would hand every stuck drop a fresh 20 s each time
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
        ItemEntity drop = closestDrop(mod);
        double fromPile = Math.sqrt(mod.getPlayer().blockPosition().distSqr(_deathPos));
        RecoverRules.Step step = RecoverRules.step(fromPile, drop != null);
        if (step == RecoverRules.Step.WALK) {
            _empty.reset();
            _chase.idle();
            setDebugState("Walking back to where we died.", "Going back for our stuff");
            return _walk;
        }
        if (step == RecoverRules.Step.LOOK) {
            _chase.idle();
            setDebugState("Nothing left here.", "Looking for our stuff");
            if (_empty.elapsed()) {
                _finished = true;
            }
            return null;
        }
        // every tick a drop is the target counts, so 20 s of chasing is 20 s and not 20 s of the ticks the walk left over
        if (_chase.update(drop.getId(), mod.getWorld().getGameTime() / 20.0)) {
            // visible, "reachable" and still not ours after all that: the pickup task's own retries reset every time we get a
            // block closer, so this is the only count that ever ends
            Debug.logMessage("Can't get at " + drop.getItem().getItem().getDescriptionId() + " after "
                    + (int) RecoverRules.CHASE_SECONDS + " s, leaving it.");
            BanPolicy.recoverGaveUp(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
            _pickup = null;
            _pin.clear();
            return null;
        }
        _empty.reset();
        setDebugState("Picking up a drop.", "Picking up our stuff");
        if (PickupThisDrop.refuses(drop)) {
            // next to lava or out in water the pickup won't touch it. pinned to it, the pickup would wander off for the
            // whole chase instead, so it is banned right now (the shared book, the tracker stops offering it to anyone)
            BanPolicy.recoverRefused(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
            _pickup = null;
            _pin.clear();
            return null;
        }
        boolean moved = _pin.retarget(drop);
        if (_pickup == null || moved || _pickup.stalled(mod)) {
            // a stall only flags this pickup (see gaveUpOnDrop below), it bans nothing. a fresh one gets another go and the chase
            // clock above is the only judge (the tracker's strikes, the fourth bans, usually land around the same time)
            _pickup = new PickupThisDrop(drop);
        }
        return _pickup;
    }

    // the stock pickup goes for the nearest item of a kind anywhere, which is not always the one we picked and timed.
    // this one only ever sees our drop and keeps the rest (full bag, water watchdog, stuck shimmy) as is
    private static final class PickupThisDrop extends PickupDroppedItemTask {
        private final ItemEntity _drop;

        PickupThisDrop(ItemEntity drop) {
            // "any amount" is only for the debug string, getClosestTo below never looks at it
            super(new ItemTarget(drop.getItem().getItem(), Integer.MAX_VALUE), true);
            _drop = drop;
        }

        // the parent's isValid rules that no retry fixes, same checks in the same order. if isValid grows a new one and
        // this doesn't, the drop just gets rebuilt every tick until the chase writes it off
        static boolean refuses(ItemEntity drop) {
            return ItemPickupRules.lavaBlocksPickup(drop)
                    || (!Baritone.settings().altoPickupItemsInWater.value && !ItemPickupRules.isPickupSafe(drop));
        }

        private boolean _stalled;

        // gave up on its own: a progress fail, or anything else that made it stop wanting the drop
        boolean stalled(AltoClef mod) {
            return _drop.isAlive() && (_stalled || !isValid(mod, _drop));
        }

        // the stock pickup bans a drop it got nowhere with for a minute. here that would hide the pile's drop from the recover
        // for most of its budget, and the chase clock is the one that is supposed to decide
        @Override
        protected void gaveUpOnDrop(AltoClef mod, ItemEntity drop) {
            _stalled = true;
        }

        // the budget is 90 s and the bag is empty, a stone pickaxe trip is the whole budget and then some
        @Override
        protected boolean mayGetPickaxeFirst() {
            return false;
        }

        @Override
        protected Optional<ItemEntity> getClosestTo(AltoClef mod, Vec3 pos) {
            return isValid(mod, _drop) ? Optional.of(_drop) : Optional.empty();
        }

        // the parent's isEqual only compares item kinds, so two andesite drops would keep the old task (and its old target).
        // the object and not the id, see Pin
        @Override
        protected boolean isEqual(Task other) {
            return other instanceof PickupThisDrop task && task._drop == _drop;
        }
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
            if (d <= RecoverRules.DROP_RADIUS * RecoverRules.DROP_RADIUS && d < bestDist && !lost
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
