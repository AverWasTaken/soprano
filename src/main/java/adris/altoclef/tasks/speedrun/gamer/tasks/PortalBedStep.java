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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

// the spawn goes on a bed by the overworld nether portal before we step in, so a nether death comes back here and not to the world
// spawn. PortalPhase runs it on the first way in and HomePortalWalk on every way back in: a night bed somewhere else takes the
// spawn and the morning pickup leaves us with none, so the way back in clicks the old portal bed again
public class PortalBedStep {
    // one try: the walk to a spot, the dig, the place and the click. the task's own give ups are quicker than this
    private static final long TRY_TICKS = 20 * 45;
    // a bed out of the bag's wool gets a minute, same as the gate's (PortalPhase.bedCraftStep)
    private static final long CRAFT_TICKS = 20 * 60;

    private PlaceBedAndSetSpawnTask task;
    // the portal the running try is for
    private BlockPos portal;
    private PortalBedRules.Source source;
    private long startedTick;
    private int tries;
    // game time of the last give up, -1 = none
    private long gaveUpTick = -1;
    // the bed went missing after the gate (or this is a way back in) and none in the bag: one gets made from the wool right here.
    // game time the craft started, -1 = not crafting
    private BedCraft bedCraft = new BedCraft();
    private long craftSince = -1;

    public void reset() {
        task = null;
        portal = null;
        tries = 0;
        gaveUpTick = -1;
        bedCraft = new BedCraft();
        craftSince = -1;
    }

    public boolean running() {
        return task != null || craftSince >= 0;
    }

    // making the bed out of wool: PortalPhase keeps the table down for it
    public boolean crafting() {
        return craftSince >= 0;
    }

    // the portal the running try (or craft) is for, null when none runs
    public BlockPos portal() {
        return running() ? portal : null;
    }

    // the bed task while it runs, null once there is nothing to do here: the spawn is by the portal, there is no bed for it, or
    // the tries ran out. the caller walks in on null
    public Task tick(AltoClef mod, RunState state, BlockPos at, Runnable save) {
        // a running try finishes against the portal it started for
        BlockPos portal = running() && this.portal != null ? this.portal : at;
        long now = mod.getWorld().getGameTime();
        if (craftSince >= 0) {
            Task craft = craftStep(mod, now);
            if (craft != null) {
                return craft;
            }
            if (gaveUpTick >= 0) {
                return null;
            }
            // crafted: the bag has one now, on to the place and click below
        }
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
        int bagBeds = mod.getItemStorage().getItemCount(ItemHelper.BED);
        boolean canCraft = bagBeds == 0 && standing == null && PortalBedRules.canCraft(BedCraft.bestWoolStack(mod), BedCraft.usableWood(mod));
        source = PortalBedRules.source(standing != null, bagBeds, canCraft);
        if (source == PortalBedRules.Source.NONE) {
            gaveUpTick = now;
            Debug.logInternal("portal bed: skipped, no bed in the bag, none standing by the portal at " + portal.toShortString()
                    + " and the bag can't make one (best wool " + BedCraft.bestWoolStack(mod) + ", wood " + BedCraft.wood(mod) + ")");
            return null;
        }
        if (source == PortalBedRules.Source.CRAFT) {
            // the gate crafts one on the first way in, this is every other way in (and a bed that went missing after the gate)
            craftSince = now;
            this.portal = portal;
            Debug.logInternal("portal bed: no bed in the bag and none standing by the portal at " + portal.toShortString()
                    + ", crafting one from the wool (best stack " + BedCraft.bestWoolStack(mod) + ", wood " + BedCraft.wood(mod) + ")");
            Task craft = craftStep(mod, now);
            if (craft != null) {
                return craft;
            }
            if (gaveUpTick >= 0) {
                return null;
            }
        }
        tries++;
        startedTick = now;
        this.portal = portal;
        task = new PlaceBedAndSetSpawnTask().spawnOnly().noFetch().awayFrom(portal, 4);
        Debug.logInternal("portal bed: try " + tries + ", bed from " + source + (standing == null ? "" : " at " + standing.toShortString())
                + ", portal at " + portal.toShortString() + ", spawn now " + (Respawn.bed(state) == null ? "world spawn" : Respawn.bed(state)));
        return task;
    }

    // one bed out of the bag's wool, a minute at most. a task while it crafts, null once it is over: crafted (craftSince back to -1,
    // the bag has a bed) or given up (gaveUpTick set, the usual cooldown before the next way in looks again). never hunts wool
    private Task craftStep(AltoClef mod, long now) {
        if (mod.getItemStorage().getItemCount(ItemHelper.BED) > 0) {
            craftSince = -1;
            Debug.logInternal("portal bed: crafted one from the bag's wool");
            return null;
        }
        Task t = bedCraft.planksFirst(mod);
        if (t == null) {
            t = bedCraft.next(mod);
        }
        if (t == null) {
            // only now: stray shuts any screen that is up, and that would be the table the craft just opened (same order as the gate)
            t = BedCraft.stray(mod);
        }
        if (t == null || now - craftSince > CRAFT_TICKS) {
            Debug.logInternal("portal bed: could not craft one (" + (t == null ? "the bag can't make it" : "out of time")
                    + "), going in without");
            craftSince = -1;
            bedCraft = new BedCraft();
            gaveUpTick = now;
            return null;
        }
        return t;
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

    // either half of the portal bed while it stands, for the never-break rule in GamerTask.applyBehaviour. the walk to the portal
    // goes right past it and a bed is a quarter second of digging, so a path through it was the cheap way and cost us the spawn. the pather
    // asks off the client thread for every block it might dig, so the "is it even next to the bed" ints go first and only those
    // few cells get a world read. the bed check keeps a night bed placed against it (or dirt) breakable
    public static boolean guarded(RunState state, BlockPos p, Function<BlockPos, BlockState> world) {
        RunState.Pos bed = state == null ? null : state.portalBed;
        if (bed == null || bed.y != p.getY() || Math.abs(bed.x - p.getX()) + Math.abs(bed.z - p.getZ()) > 1) {
            return false;
        }
        return halfOf(bed, p, world.apply(p));
    }

    // p is the bed at foot, or the other half of it
    static boolean halfOf(RunState.Pos foot, BlockPos p, BlockState st) {
        if (st == null || !(st.getBlock() instanceof BedBlock)) {
            return false;
        }
        if (p.getX() == foot.x && p.getY() == foot.y && p.getZ() == foot.z) {
            return true;
        }
        Direction facing = st.getValue(BedBlock.FACING);
        BlockPos other = st.getValue(BedBlock.PART) == BedPart.HEAD ? p.relative(facing.getOpposite()) : p.relative(facing);
        return other.getX() == foot.x && other.getY() == foot.y && other.getZ() == foot.z;
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
