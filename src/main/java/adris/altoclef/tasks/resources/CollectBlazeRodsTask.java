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
import adris.altoclef.tasks.speedrun.gamer.DeathZones;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.RunZones;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CollectBlazeRodsTask extends ResourceTask {

    // blazes spawn within 4 sideways of the spawner, standing this close keeps every new one in reach
    private static final double CAMP_RADIUS = 2;
    private final int _count;
    private final Task _searcher = new SearchChunkForBlockTask(Blocks.NETHER_BRICKS);
    // the fight logic lives in there. this task used to just camp, silently, while blazes it had decided not to see shot at it
    private final BlazeFight _fight = new BlazeFight();

    private BlockPos _foundBlazeSpawner = null;

    // true on ticks where we stand by the spawner waiting for blazes, so the gamer can give up on a dud spawner
    private boolean _camping;
    // true on ticks where the walk to the spawner is what we are doing (not the fight, not the camp, not the fire)
    private boolean _toSpawner;

    // nether death spots, asked on the main thread (the gamer hands in its death book, a bare task has none)
    private final Supplier<List<double[]>> _deathSpots;
    private final RunZones _deathZones = new RunZones();
    // what the path predicate sees, from baritone's thread: where we stand, the game clock and the spawner we walk to
    // (NaN = none). written every tick, same idea as FindNetherStructureTask
    private volatile int _zoneX;
    private volatile int _zoneZ;
    private volatile long _zoneNow;
    private volatile double _goalX = Double.NaN;
    private volatile double _goalZ = Double.NaN;
    // the zones are nether coordinates, the overworld walk to the portal must not see them as walls
    private volatile boolean _zoneNether;

    public CollectBlazeRodsTask(int count) {
        this(count, List::of);
    }

    public CollectBlazeRodsTask(int count, Supplier<List<double[]>> deathSpots) {
        super(Items.BLAZE_ROD, count);
        _count = count;
        _deathSpots = deathSpots;
    }

    // the middle of the wither pack around a spot, null when there is no pack there
    public static double[] witherPackCenter(AltoClef mod, Vec3 at, double radius) {
        return BlazeFightRules.packCenter(BlazeFight.witherSkeletonSpots(mod, at, radius), BlazeFightRules.ZONE_PACK_SIZE);
    }

    private void noteZoneInputs(AltoClef mod) {
        _zoneX = mod.getPlayer().blockPosition().getX();
        _zoneZ = mod.getPlayer().blockPosition().getZ();
        _zoneNow = mod.getWorld().getGameTime();
        _zoneNether = WorldHelper.getCurrentDimension() == Dimension.NETHER;
        _goalX = _foundBlazeSpawner == null ? Double.NaN : _foundBlazeSpawner.getX();
        _goalZ = _foundBlazeSpawner == null ? Double.NaN : _foundBlazeSpawner.getZ();
        _deathZones.replaceAll(_deathSpots.get(), _zoneNow, DeathZones.RADIUS);
    }

    public boolean isCampingSpawner() {
        return _camping;
    }

    // read once per phase tick and cleared, so a tick this task did not get to run (a rod pickup took the wheel) is not a walk
    public boolean takeWalkingToSpawner() {
        boolean was = _toSpawner;
        _toSpawner = false;
        return was;
    }

    // every live blaze within range, in reach or not. only for saying "they are here, just not in reach" on the hud
    public int blazesAround() {
        return _fight.around();
    }

    // hitting or running: not waiting on the spawner
    public boolean isBusyWithBlazes() {
        return _fight.mode() != BlazeFightRules.Mode.CAMP;
    }

    // the spawner this task is working on right now, null while it is still looking. the gamer blacklists this one when
    // it gives up, not whatever the tracker would call nearest
    public BlockPos currentSpawner() {
        return _foundBlazeSpawner;
    }

    // the walk to this spawner goes nowhere. only this one block is banned (the ban book honours it, so the pick below
    // skips it), true when another spawner took its place. none = the caller decides what a fortress with no way in is worth
    public boolean dropSpawner(AltoClef mod) {
        if (_foundBlazeSpawner == null) {
            return false;
        }
        mod.getBlockTracker().requestBlockUnreachable(_foundBlazeSpawner, 0, "no way to the blaze spawner");
        _foundBlazeSpawner = null;
        pickSpawner(mod);
        return _foundBlazeSpawner != null;
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
        // no bridging or pillaring while fighting or running, see BlazeFight.noPlacing
        mod.getBehaviour().avoidBlockPlacing(pos -> _fight.noPlacing());
        // the base class pushed our behaviour level and pops it on stop. the pack closets and where we died lately are walls for
        // the walk in (the player and the spawner being inside one still keeps it open, see RunZones)
        noteZoneInputs(mod);
        mod.getBehaviour().avoidWalkingThrough(p -> _zoneNether
                && (RunZones.INSTANCE.blocks(p.getX(), p.getZ(), _zoneNow, _zoneX, _zoneZ, _goalX, _goalZ)
                || _deathZones.blocks(p.getX(), p.getZ(), _zoneNow, _zoneX, _zoneZ, _goalX, _goalZ)));
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        _camping = false;
        _toSpawner = false;
        noteZoneInputs(mod);
        // the fight only sets this on ticks it runs, the walk to the nether or a rod pickup should not inherit it
        _fight.idle();
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        // the spawner is what starts the dry timer, finding one only between fights meant a long fight never found it
        pickSpawner(mod);
        // (again: the goal the walk is judged against is the spawner we just picked, not last tick's)
        noteZoneInputs(mod);
        // blazes first: kill the one we picked, run when it goes badly. null is "nobody to fight"
        Task fight = _fight.tick(mod);
        if (fight != null) {
            setDebugState("Blaze fight: " + _fight.mode().name().toLowerCase());
            return fight;
        }
        if (_fight.mode() == BlazeFightRules.Mode.RUN) {
            // out of their sight and healing, the spawner can wait. standing here is the plan so it counts as camping, unless
            // there are blazes we could go get, then it is just a breather and not a dud spawner
            setDebugState("Recovering away from the blazes");
            // a run that stopped healing is dead time just like a dud spawner, the rods watch clocks have to see it
            _camping = _fight.reachable() == 0 || _fight.runGaveUp();
            return null;
        }

        // If we have a blaze spawner, stand next to it in the open and let them come.
        if (_foundBlazeSpawner != null) {
            // a wither skeleton pack at the spawner: hold where we are, the rods watch counts it as dud time and moves us on
            // if they never leave. walking in is how the last two runs ended
            if (BlazeFightRules.packNear(BlazeFight.witherSkeletonsNear(mod, Vec3.atCenterOf(_foundBlazeSpawner), BlazeFightRules.PACK_SPAWNER_RADIUS))) {
                setDebugState("wither skeletons at the spawner, waiting");
                _camping = true;
                return null;
            }
            if (!_foundBlazeSpawner.closerToCenterThan(mod.getPlayer().position(), CAMP_RADIUS)) {
                setDebugState("Going to blaze spawner (" + (RunZones.INSTANCE.active(_zoneNow) + _deathZones.active(_zoneNow)) + " zones)");
                _toSpawner = true;
                return new GetToBlockTask(_foundBlazeSpawner.above(), false);
            }
            // Put out fire that might mess with us.
            Optional<BlockPos> nearestFire = mod.getBlockTracker().getNearestWithinRange(_foundBlazeSpawner, 5, Blocks.FIRE);
            if (nearestFire.isPresent()) {
                setDebugState("Clearing fire around spawner to prevent loss of blaze rods.");
                return new PutOutFireTask(nearestFire.get());
            }
            // nothing is in reach or we would be fighting it, so this is a dud spawner as far as the relocate timer goes
            setDebugState(_fight.around() > 0 ? "Waiting for the blazes to come in reach" : "Waiting near blaze spawner for blazes to spawn");
            _camping = true;
            return null;
        }
        // We need to find our fortress.
        setDebugState("Searching for fortress/Traveling around fortress");
        return _searcher;
    }

    private void pickSpawner(AltoClef mod) {
        // If the blaze spawner somehow isn't valid
        if (_foundBlazeSpawner != null && mod.getChunkTracker().isChunkLoaded(_foundBlazeSpawner) && !isValidBlazeSpawner(mod, _foundBlazeSpawner)) {
            Debug.logMessage("Blaze spawner at " + _foundBlazeSpawner + " too far away or invalid. Re-searching.");
            _foundBlazeSpawner = null;
        }
        if (_foundBlazeSpawner != null || !mod.getBlockTracker().isTracking(Blocks.SPAWNER)) return;
        // nearest one we can actually look at: asking for the single nearest and then throwing it out for being in an
        // unloaded chunk meant a perfectly good loaded spawner one tile further never got a look
        Optional<BlockPos> spawner = mod.getBlockTracker().getNearestTracking(p -> isValidBlazeSpawner(mod, p), Blocks.SPAWNER);
        spawner.ifPresent(found -> _foundBlazeSpawner = found);
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
