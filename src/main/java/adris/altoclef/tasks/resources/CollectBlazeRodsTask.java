package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CollectBlazeRodsTask extends ResourceTask {

    // a hiding spot further than this from the spawner is not a spot near the spawner
    private static final double COVER_HOLD_RADIUS = 10;
    private final int _count;
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
    // the fight logic lives in there. this task used to just camp, silently, while blazes it had decided not to see shot at it
    private final BlazeFight _fight = new BlazeFight();

    private BlockPos _foundBlazeSpawner = null;

    // true on ticks where we stand by the spawner waiting for blazes, so the gamer can give up on a dud spawner
    private boolean _camping;

    public CollectBlazeRodsTask(int count) {
        super(Items.BLAZE_ROD, count);
        _count = count;
    }

    public boolean isCampingSpawner() {
        return _camping;
    }

    // the spawner this task is working on right now, null while it is still looking. the gamer blacklists this one when
    // it gives up, not whatever the tracker would call nearest
    public BlockPos currentSpawner() {
        return _foundBlazeSpawner;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.SPAWNER);
        // the fight is ours: mob defense chasing a hovering blaze with a sword is how this used to go wrong. its projectile
        // shield and dodge do not look at this list, and the combat stance (no eating mid volley) still counts them
        mod.getBehaviour().addMobDefenseExclusion(entity -> entity instanceof Blaze);
        // the aura swinging and shielding at a blaze 6 blocks off pauses the path we need to get out of its sight, only
        // the ones next to us are for it
        mod.getBehaviour().addForceFieldExclusion(entity -> entity instanceof Blaze && mod.getPlayer().distanceTo(entity) > 3.5);
        _fight.forgetCampSpot();
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        _camping = false;
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        // blazes first: kill what we can reach, hide from what we cannot, leave when it goes badly. null is "nobody to fight"
        Task fight = _fight.tick(mod, _foundBlazeSpawner);
        if (fight != null) {
            setDebugState("Blaze fight: " + _fight.mode().name().toLowerCase());
            return fight;
        }
        if (_fight.mode() == BlazeFightRules.Mode.RETREAT) {
            // out of their sight and healing, the spawner can wait. standing here is the plan so it counts as camping
            setDebugState("Recovering away from the blazes");
            _camping = true;
            return null;
        }

        // If the blaze spawner somehow isn't valid
        if (_foundBlazeSpawner != null && mod.getChunkTracker().isChunkLoaded(_foundBlazeSpawner) && !isValidBlazeSpawner(mod, _foundBlazeSpawner)) {
            Debug.logMessage("Blaze spawner at " + _foundBlazeSpawner + " too far away or invalid. Re-searching.");
            _foundBlazeSpawner = null;
            _fight.forgetCampSpot();
        }

        // If we have a blaze spawner, go near it.
        if (_foundBlazeSpawner != null) {
            // wherever we last hid from them near here beats standing in the open next to the spawner
            BlockPos hide = _fight.campSpot();
            if (hide != null && hide.closerToCenterThan(_foundBlazeSpawner.getCenter(), COVER_HOLD_RADIUS)) {
                if (!hide.closerToCenterThan(mod.getPlayer().position(), 1.5)) {
                    setDebugState("Going back to cover");
                    return new GetToBlockTask(hide, false);
                }
            } else if (!_foundBlazeSpawner.closerToCenterThan(mod.getPlayer().position(), 4)) {
                setDebugState("Going to blaze spawner");
                return new GetToBlockTask(_foundBlazeSpawner.above(), false);
            }
            // Put out fire that might mess with us.
            Optional<BlockPos> nearestFire = mod.getBlockTracker().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
            if (nearestFire.isPresent()) {
                setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                return new PutOutFireTask(nearestFire.get());
            }
            setDebugState("Waiting near blaze spawner for blazes to spawn");
            _camping = true;
            return null;
        }
        // Search for blaze
        if (mod.getBlockTracker().isTracking(Blocks.SPAWNER)) {
            // nearest one we can actually look at: asking for the single nearest and then throwing it out for being in an
            // unloaded chunk meant a perfectly good loaded spawner one tile further never got a look
            Optional<BlockPos> spawner = mod.getBlockTracker().getNearestTracking(p -> isValidBlazeSpawner(mod, p), Blocks.SPAWNER);
            if (spawner.isPresent()) {
                _foundBlazeSpawner = spawner.get();
            }
        }
        // We need to find our fortress.
        setDebugState("Searching for fortress/Traveling around fortress");
        return _searcher;
    }

    private boolean isValidBlazeSpawner(AltoClef mod, BlockPos pos) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            // If unloaded, go to it. Unless it's super far away.
            return false;
            //return pos.isWithinDistance(mod.getPlayer().getPos(),3000);
        }
        return WorldHelper.getSpawnerEntity(mod, pos) instanceof Blaze;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.SPAWNER);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectBlazeRodsTask;
    }

    @Override
    protected String toHudString() {
        return "Hunting Blazes for " + HudText.count(_count, "Blaze Rod");
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " blaze rods";
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
