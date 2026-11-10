package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.speedrun.gamer.BedRules;
import adris.altoclef.tasks.speedrun.gamer.PortalBedRules;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

// the spawn goes on a bed by the overworld nether portal before we step in, so a nether death comes back here and not to the world
// spawn. PortalPhase runs it on the first way in and HomePortalWalk on every way back in: a night bed somewhere else takes the
// spawn and the morning pickup leaves us with none, so the way back in clicks the old portal bed again
public class PortalBedStep {
    // one try: the walk to a spot, the dig, the place and the click. the task's own give ups are quicker than this
    private static final long TRY_TICKS = 20 * 45;

    private PlaceBedAndSetSpawnTask task;
    // the portal the running try is for
    private BlockPos portal;
    private PortalBedRules.Source source;
    private long startedTick;
    private int tries;
    // game time of the last give up, -1 = none
    private long gaveUpTick = -1;

    public void reset() {
        task = null;
        portal = null;
        tries = 0;
        gaveUpTick = -1;
    }

    public boolean running() {
        return task != null;
    }

    // the portal the running try is for, null when none runs
    public BlockPos portal() {
        return task == null ? null : portal;
    }

    // the bed task while it runs, null once there is nothing to do here: the spawn is by the portal, there is no bed for it, or
    // the tries ran out. the caller walks in on null
    public Task tick(AltoClef mod, RunState state, BlockPos at, Runnable save) {
        // a running try finishes against the portal it started for
        BlockPos portal = task != null && this.portal != null ? this.portal : at;
        long now = mod.getWorld().getGameTime();
        if (task != null) {
            boolean finished = task.isFinished(mod);
            if (!finished && now - startedTick < TRY_TICKS) {
                return task;
            }
            String why = task.gaveUp() ? task.gaveUpReason() : finished ? "the game never said the spawn moved" : "out of time";
            RunState.Pos bed = Respawn.portalBedNear(state, portal.getX(), portal.getY(), portal.getZ());
            if (bed == null && finished && !task.gaveUp()) {
                bed = silentClick(mod, state, task, portal);
            }
            PlaceBedAndSetSpawnTask last = task;
            task = null;
            switch (PortalBedRules.after(bed != null, tries)) {
                case DONE -> {
                    state.portalBed = bed;
                    save.run();
                    Debug.logInternal("portal bed: spawn set at " + bed + " (y " + bed.y + "), portal at " + portal.toShortString()
                            + ", bed from " + source + ", try " + tries);
                    tries = 0;
                    return null;
                }
                case GIVE_UP -> {
                    gaveUpTick = now;
                    Debug.logInternal("portal bed: NO SPAWN BY THE PORTAL after " + tries + " tries (" + why + "), going in anyway. a nether death"
                            + " goes back to " + (Respawn.bed(state) == null ? "the world spawn" : Respawn.bed(state)));
                    return null;
                }
                case RETRY -> {
                    // the same instance again: a new one is equal to it (isEqual says any bed task is), so the parent would keep
                    // ticking the old child and we'd be watching one that never starts
                    Debug.logInternal("portal bed: try " + tries + " did not take (" + why + "), once more");
                    last.resetSleep();
                    task = last;
                    tries++;
                    startedTick = now;
                    return task;
                }
            }
        }
        if (mod.getPlayer() == null || Respawn.portalBedNear(state, portal.getX(), portal.getY(), portal.getZ()) != null) {
            return null;
        }
        if (gaveUpTick >= 0) {
            if (now - gaveUpTick < PortalBedRules.RETRY_AFTER_TICKS) {
                return null;
            }
            gaveUpTick = -1;
            tries = 0;
        }
        BlockPos standing = standingNear(mod, state, portal);
        source = PortalBedRules.source(standing != null, mod.getItemStorage().getItemCount(ItemHelper.BED), false);
        if (source == PortalBedRules.Source.NONE) {
            // (the gate already had its go at crafting one, see PortalPhase.bedCraftStep)
            gaveUpTick = now;
            Debug.logInternal("portal bed: skipped, no bed in the bag and none standing by the portal at " + portal.toShortString());
            return null;
        }
        tries++;
        startedTick = now;
        this.portal = portal;
        task = new PlaceBedAndSetSpawnTask().spawnOnly().noFetch().awayFrom(portal, 4);
        Debug.logInternal("portal bed: try " + tries + ", bed from " + source + (standing == null ? "" : " at " + standing.toShortString())
                + ", portal at " + portal.toShortString() + ", spawn now " + (Respawn.bed(state) == null ? "world spawn" : Respawn.bed(state)));
        return task;
    }

    // the game only says "Respawn point set" when the spot moves, so a click on the bed that already holds the server's spawn
    // (our state lost it: a relog, a line with no bed in reach) is silent. the task only finishes without giving up once the click
    // landed (asleep, or the server's "only at night" / "monsters" overlay, which comes after it sets the spawn), so that bed is it
    private static RunState.Pos silentClick(AltoClef mod, RunState state, PlaceBedAndSetSpawnTask t, BlockPos portal) {
        BlockPos head;
        try {
            head = t.getBedSleptPos();
        } catch (IllegalStateException e) {
            return null;
        }
        BlockPos foot = NightSleepTask.footOf(head, mod.getWorld().getBlockState(head));
        if (foot == null) {
            return null;
        }
        RunState.Pos at = new RunState.Pos(foot.getX(), foot.getY(), foot.getZ());
        if (!foot.closerToCenterThan(Vec3.atCenterOf(portal), Respawn.NEAR_NETHER_PORTAL)) {
            return null;
        }
        Respawn.set(state, at, "OVERWORLD");
        Debug.logInternal("portal bed: the click got no respawn line, the game already had the spawn there, taking it");
        return at;
    }

    // a bed standing by the portal: the one we remember, else any the world shows within STANDING_NEAR. null = none (or not loaded,
    // an unloaded chunk reads as air and that is no bed to walk to)
    public static BlockPos standingNear(AltoClef mod, RunState state, BlockPos center) {
        var world = mod.getWorld();
        if (state.portalBed != null) {
            BlockPos at = new BlockPos(state.portalBed.x, state.portalBed.y, state.portalBed.z);
            if (world.hasChunkAt(at) && world.getBlockState(at).getBlock() instanceof BedBlock) {
                return at;
            }
        }
        int r = PortalBedRules.STANDING_NEAR;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -4, -r), center.offset(r, 4, r))) {
            // tonight's bed gets picked up in the morning, and the spawn on it goes with it
            if (state.nightBed != null && BedRules.touches(state.nightBed, new RunState.Pos(p.getX(), p.getY(), p.getZ()))) {
                continue;
            }
            if (world.hasChunkAt(p) && world.getBlockState(p).getBlock() instanceof BedBlock) {
                return p.immutable();
            }
        }
        return null;
    }
}
