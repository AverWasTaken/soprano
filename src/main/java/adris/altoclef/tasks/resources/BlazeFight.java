package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.BlazeFightRules.Mode;
import adris.altoclef.util.helpers.LookHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// the per tick brain of the rods task while there are blazes about. it only decides, BlazeFightRules has the numbers.
// null from tick() means "nobody to fight, carry on camping" (or sit tight, when we are retreating with nothing in sight)
final class BlazeFight {

    // spots are only re-picked this often. a pick is a few hundred raycasts, which is fine now and then and silly every tick
    private static final int SPOT_EVERY = 10;
    private static final int COVER_RADIUS = 8;
    private static final int RETREAT_RADIUS = 6;
    private static final int RETREAT_RADIUS_WIDE = 10;
    // closest first, the far ones are not going to be the winner anyway
    private static final int MAX_CANDIDATES = 80;
    // blazes come in packs, past the nearest few the answer does not change
    private static final int MAX_SHOOTERS = 6;
    private static final double SPOT_NEAR_SPAWNER_SQ = 10 * 10;
    // staying put is worth about this many blocks of walking. without it two similar spots make us pace
    private static final double STAY_BONUS = 30;
    private static final long BAD_SPOT_TICKS = 600;
    private static final double RUN_DISTANCE = 30;

    // a raycast per blaze per tick is silly, a blaze moves maybe half a block in this long
    private static final int LINE_EVERY = 5;

    private Mode mode = Mode.CAMP;
    // when the current mode started, game time. the dwell in BlazeFightRules.decide counts from here
    private long modeSince;
    private final Map<Integer, LineCheck> lines = new HashMap<>();
    private Mode loggedMode = Mode.CAMP;
    private boolean retreating;
    private final List<Blaze> threats = new ArrayList<>();
    private int reachable;

    private BlockPos spot;
    private long spotTick = Long.MIN_VALUE;
    private boolean spotForRetreat;
    // the last place we hid. when nothing is shooting we go stand there instead of in the open next to the spawner
    private BlockPos campSpot;
    private final Map<BlockPos, Long> badSpots = new HashMap<>();

    Mode mode() {
        return mode;
    }

    private record LineCheck(long tick, boolean clear) {
    }

    BlockPos campSpot() {
        return campSpot;
    }

    void forgetCampSpot() {
        campSpot = null;
    }

    Task tick(AltoClef mod, BlockPos spawner) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        scan(mod, player, now);
        float health = player.getHealth();
        // game time can go backwards on a world change, then the old mode is as good as ancient
        long dwelled = now >= modeSince ? now - modeSince : Long.MAX_VALUE;
        Mode next = BlazeFightRules.decide(health, retreating, threats.size(), reachable, mode, dwelled);
        retreating = next == Mode.RETREAT;
        if (next != mode) modeSince = now;
        mode = next;
        logChange(health);
        return switch (mode) {
            case CAMP -> null;
            case KILL -> new KillEntitiesTask(e -> e instanceof Blaze blaze && isReachable(mod, blaze), Blaze.class);
            case COVER -> cover(mod, now, spawner);
            case RETREAT -> retreat(mod, now, spawner);
        };
    }

    private void logChange(float health) {
        if (mode == loggedMode) return;
        loggedMode = mode;
        switch (mode) {
            case KILL -> Debug.logInternal("rods: going after a blaze (" + reachable + " in reach)");
            case COVER -> Debug.logInternal("rods: taking cover from " + plural(threats.size()));
            case RETREAT -> Debug.logInternal("rods: retreating at " + (int) health + " hp");
            case CAMP -> Debug.logInternal("rods: all quiet, back to camping");
        }
    }

    private static String plural(int blazes) {
        return blazes + (blazes == 1 ? " blaze" : " blazes");
    }

    // ---- what is out there

    private void scan(AltoClef mod, LocalPlayer player, long now) {
        threats.clear();
        reachable = 0;
        // forget blazes that died or left, the ids get reused
        lines.values().removeIf(check -> now - check.tick() > LINE_EVERY * 4L);
        List<Blaze> blazes;
        try {
            blazes = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, one tick of stale blazes is fine
            return;
        }
        for (Blaze blaze : blazes) {
            if (!blaze.isAlive()) continue;
            double distance = player.distanceTo(blaze);
            if (distance > BlazeFightRules.MAX_CHASE_DISTANCE) continue;
            if (isReachable(mod, blaze, distance, now)) reachable++;
            // raycasts only for the ones that could actually hit us from where they are
            if (distance <= BlazeFightRules.SIGHT_RANGE && LookHelper.seesPlayer(blaze, player, BlazeFightRules.SIGHT_RANGE)) {
                threats.add(blaze);
            }
        }
        threats.sort(Comparator.comparingDouble(player::distanceToSqr));
    }

    private boolean isReachable(AltoClef mod, Blaze blaze) {
        return isReachable(mod, blaze, mod.getPlayer().distanceTo(blaze), mod.getWorld().getGameTime());
    }

    // walk straight down from the blaze to whatever it is hovering over. lava is a no, a floor is how high it is
    private boolean isReachable(AltoClef mod, Blaze blaze, double distance, long now) {
        Level level = mod.getWorld();
        BlockPos at = blaze.blockPosition();
        double height = Double.POSITIVE_INFINITY;
        boolean lava = false;
        for (int i = 0; i <= 8; i++) {
            BlockPos check = at.below(i);
            BlockState state = level.getBlockState(check);
            if (state.is(Blocks.LAVA)) {
                lava = true;
                break;
            }
            if (!state.getCollisionShape(level, check).isEmpty()) {
                // fences and slabs and stairs are not a full block, use the actual top of the thing
                height = blaze.getY() - (check.getY() + state.getCollisionShape(level, check).max(Direction.Axis.Y));
                break;
            }
        }
        // cheap rejects first, the ray is the only part that costs anything
        if (!BlazeFightRules.isReachable(height, lava, distance, true)) return false;
        return meleeLineClear(mod, blaze, now);
    }

    // collision shapes, not visual ones: a nether brick fence is see through and swing proof. cached a few ticks per blaze
    private boolean meleeLineClear(AltoClef mod, Blaze blaze, long now) {
        LineCheck cached = lines.get(blaze.getId());
        if (cached != null && now >= cached.tick() && now - cached.tick() < LINE_EVERY) return cached.clear();
        LocalPlayer player = mod.getPlayer();
        boolean clear = !clipped(mod.getWorld(), player, player.getEyePosition(), blaze.getBoundingBox().getCenter());
        lines.put(blaze.getId(), new LineCheck(now, clear));
        return clear;
    }

    // the nearest one that is winding up a volley, that is the one the shield goes toward
    Blaze shieldTarget() {
        for (Blaze blaze : threats) {
            if (blaze.isOnFire()) return blaze;
        }
        return null;
    }

    private List<Entity> threatEntities() {
        return new ArrayList<>(threats);
    }

    // ---- what to do about it

    private Task cover(AltoClef mod, long now, BlockPos spawner) {
        BlockPos pick = chooseSpot(mod, now, spawner, false);
        if (pick == null) pick = mod.getPlayer().blockPosition();
        campSpot = pick;
        return new BlazeCoverTask(pick, true, this::shieldTarget, this::markBad);
    }

    private Task retreat(AltoClef mod, long now, BlockPos spawner) {
        // nothing is looking at us, so this is just sitting tight until the food chain and regen are done
        if (threats.isEmpty()) return null;
        BlockPos pick = chooseSpot(mod, now, spawner, true);
        if (pick != null) {
            return new BlazeCoverTask(pick, false, this::shieldTarget, this::markBad);
        }
        // nowhere nearby hides us from all of them. run from them, the threat list is who we mean (not just the charged ones)
        return new RunAwayFromHostilesTask(RUN_DISTANCE, true, this::threatEntities);
    }

    private void markBad(BlockPos bad) {
        badSpots.put(bad.immutable(), mod().getWorld().getGameTime());
        spotTick = Long.MIN_VALUE;
    }

    private AltoClef mod() {
        return AltoClef.getInstance();
    }

    private boolean isBad(BlockPos pos, long now) {
        Long when = badSpots.get(pos);
        if (when == null) return false;
        if (now - when > BAD_SPOT_TICKS) {
            badSpots.remove(pos);
            return false;
        }
        return true;
    }

    // a cover spot: somewhere nearby to stand that hides us from the blazes. covering picks the spot that hides the most of
    // them (and stays close to the spawner, that is where the rods come from), retreating needs one that hides us from all
    // of them and takes the nearest. null means nothing nearby does that
    private BlockPos chooseSpot(AltoClef mod, long now, BlockPos spawner, boolean forRetreat) {
        if (spotTick != Long.MIN_VALUE && now >= spotTick && now - spotTick < SPOT_EVERY && spotForRetreat == forRetreat) {
            return spot;
        }
        spotTick = now;
        spotForRetreat = forRetreat;
        List<Blaze> shooters = threats.size() > MAX_SHOOTERS ? threats.subList(0, MAX_SHOOTERS) : threats;
        BlockPos best = search(mod, now, spawner, forRetreat ? RETREAT_RADIUS : COVER_RADIUS, shooters, forRetreat);
        if (best == null && forRetreat) {
            best = search(mod, now, spawner, RETREAT_RADIUS_WIDE, shooters, true);
        }
        spot = best;
        return best;
    }

    private BlockPos search(AltoClef mod, long now, BlockPos spawner, int radius, List<Blaze> shooters, boolean needAll) {
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        BlockPos origin = player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (spawner != null && pos.distSqr(spawner) > SPOT_NEAR_SPAWNER_SQ) continue;
                    if (isBad(pos, now) || !standable(level, pos)) continue;
                    candidates.add(pos);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        if (candidates.size() > MAX_CANDIDATES) candidates = candidates.subList(0, MAX_CANDIDATES);

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : candidates) {
            int blocked = countBlocked(player, level, pos, shooters);
            if (needAll && blocked < shooters.size()) continue;
            double score = blocked * 100 - Math.sqrt(origin.distSqr(pos)) * 2;
            // the rods come from the spawner, so while we are just covering do not wander off from it
            if (!needAll && spawner != null) score -= Math.sqrt(pos.distSqr(spawner)) * 0.5;
            if (pos.equals(spot)) score += STAY_BONUS;
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    // blazes that cannot see a bot standing here. same two rays the blaze's own sight check uses (eye and a bit lower)
    private static int countBlocked(LocalPlayer player, Level level, BlockPos pos, List<Blaze> shooters) {
        Vec3 eye = new Vec3(pos.getX() + 0.5, pos.getY() + 1.62, pos.getZ() + 0.5);
        Vec3 low = new Vec3(pos.getX() + 0.5, pos.getY() + 0.62, pos.getZ() + 0.5);
        int blocked = 0;
        for (Blaze blaze : shooters) {
            Vec3 from = blaze.getEyePosition();
            if (clipped(level, player, from, eye) && clipped(level, player, from, low)) blocked++;
        }
        return blocked;
    }

    private static boolean clipped(Level level, LocalPlayer player, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS;
    }

    // solid top to stand on that is not magma, and two blocks of nothing (no fire, no lava) for us to stand in
    private static boolean standable(Level level, BlockPos feet) {
        BlockPos floor = feet.below();
        BlockState ground = level.getBlockState(floor);
        if (!ground.isFaceSturdy(level, floor, Direction.UP) || ground.is(Blocks.MAGMA_BLOCK)) return false;
        BlockState body = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        return body.getCollisionShape(level, feet).isEmpty() && head.getCollisionShape(level, feet.above()).isEmpty()
                && body.getFluidState().isEmpty() && head.getFluidState().isEmpty() && !body.is(Blocks.FIRE) && !body.is(Blocks.SOUL_FIRE);
    }
}
