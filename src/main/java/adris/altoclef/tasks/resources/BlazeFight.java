package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.BlazeFightRules.Mode;
import adris.altoclef.util.helpers.LookHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
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
    // well inside SPOT_NEAR_SPAWNER_SQ, so once we get here the cover search has spots to look at
    private static final int BACK_TO_SPAWNER_RANGE = 6;

    // a raycast per blaze per tick is silly, a blaze moves maybe half a block in this long
    private static final int LINE_EVERY = 5;
    // how close to the floor under a blaze the hunt walk wants to get. close enough that it sees us, then COVER takes over
    private static final int HUNT_RANGE = 3;

    private Mode mode = Mode.CAMP;
    // when the current mode started, game time. the dwell in BlazeFightRules.decide counts from here
    private long modeSince;
    // per blaze by entity id: is it in reach and what its swing line has been doing. this is what makes reach sticky,
    // see BlazeFightRules.reachableNow. dropped the scan a blaze is dead or out of range, the ids get reused
    private final Map<Integer, Reach> reach = new HashMap<>();
    private Mode loggedMode = Mode.CAMP;
    private boolean retreating;
    // the retreat stall watch: when the idle retreat started (or last gained hp), what hp was last tick, and whether we
    // gave up waiting. MIN_VALUE is not watching
    private long retreatIdleSince = Long.MIN_VALUE;
    private float lastHealth = Float.NaN;
    private boolean retreatGaveUp;
    private final List<Blaze> threats = new ArrayList<>();
    private int reachable;
    // blazes worth walking to, nearest first, and every live blaze within MAX_CHASE_DISTANCE (that one is just for the hud)
    private final List<Hunt> huntable = new ArrayList<>();
    private int around;
    // game time a blaze last had eyes on us. MIN_VALUE is never
    private long lastThreatTick = Long.MIN_VALUE;
    // the blaze we are walking toward and where we are walking. sticky so two similar blazes do not have us pacing
    private Blaze huntTarget;
    private BlockPos huntGoal;
    private long huntSince;
    private long lastHuntTick = Long.MIN_VALUE;
    // blazes we walked at for too long, by entity id and when. skipped for BAD_SPOT_TICKS, same idea as badSpots
    private final Map<Integer, Long> skippedHunts = new HashMap<>();

    // cover and retreat each hold their own spot, a cover pick used to overwrite the retreat's and send it the other way
    private static final class Held {
        BlockPos spot;
        long tick = Long.MIN_VALUE;
        // picks in a row where the spot no longer hid us well enough
        int misses;
    }

    private final Held coverHeld = new Held();
    private final Held retreatHeld = new Held();
    // the last place we hid. when nothing is shooting we go stand there instead of in the open next to the spawner
    private BlockPos campSpot;
    private final Map<BlockPos, Long> badSpots = new HashMap<>();

    Mode mode() {
        return mode;
    }

    private static final class Reach {
        long rayTick = Long.MIN_VALUE;
        boolean clear;
        // rays in a row that hit something
        int blocked;
        boolean reachable;
        // from the floor under it to the spawner, NaN for no floor or no spawner
        double fromSpawner = Double.NaN;
    }

    // what is under a blaze: the block we would stand on (null for none within the scan), how high it hovers over that,
    // and whether it is lava instead
    private record Floor(BlockPos pos, double height, boolean lava) {
    }

    // stand is the spot on top of its floor
    private record Hunt(Blaze blaze, BlockPos stand) {
    }

    int huntable() {
        return huntable.size();
    }

    int around() {
        return around;
    }

    BlockPos campSpot() {
        return campSpot;
    }

    void forgetCampSpot() {
        campSpot = null;
    }

    // the walk back to campSpot is going nowhere. same as a cover spot we could not get to: mark it so the next cover pick
    // finds a different one, and camp by the spawner until then
    void campSpotUnreachable() {
        if (campSpot == null) return;
        markBad(campSpot);
        campSpot = null;
    }

    boolean retreatGaveUp() {
        return retreatGaveUp;
    }

    Task tick(AltoClef mod, BlockPos spawner) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        scan(mod, player, now, spawner);
        float health = player.getHealth();
        // game time can go backwards on a world change, then the old mode is as good as ancient
        long dwelled = now >= modeSince ? now - modeSince : Long.MAX_VALUE;
        // same deal for the hide hold, a clock that went backwards means the last look was long ago
        long sinceThreat = lastThreatTick != Long.MIN_VALUE && now >= lastThreatTick ? now - lastThreatTick : Long.MAX_VALUE;
        // hp going up is what the retreat waits for, so any rise means it is working again
        if (health > lastHealth) retreatGaveUp = false;
        Mode next = BlazeFightRules.decide(health, retreating, retreatGaveUp, burning(player), threats.size(), reachable,
                huntable.size(), sinceThreat, mode, dwelled);
        retreating = next == Mode.RETREAT;
        if (next != mode) modeSince = now;
        mode = next;
        watchRetreat(now, health);
        lastHealth = health;
        // a real fight starts the next hunt fresh. CAMP does not: a blaze blinking over the lava edge for a tick would
        // restart the give up clock for ever. COVER does not either, the blaze we walked at seeing us and making us hide is
        // the hunt loop, clearing here was what kept the give up from ever firing
        if (mode == Mode.KILL || mode == Mode.RETREAT) huntTarget = null;
        logChange(health);
        return switch (mode) {
            case CAMP -> null;
            case KILL -> new KillEntitiesTask(e -> e instanceof Blaze blaze && fightable(mod, blaze), Blaze.class);
            case COVER -> cover(mod, now, spawner);
            case RETREAT -> retreat(mod, now, spawner);
            case HUNT -> hunt(now);
        };
    }

    // an idle retreat (nobody looking, just healing) that has not gained hp in RETREAT_GIVE_UP_TICKS gives up. runs after
    // decide, the flag is read by the next one
    private void watchRetreat(long now, float health) {
        boolean rose = health > lastHealth;
        boolean idle = mode == Mode.RETREAT && threats.isEmpty();
        boolean stalled = false;
        if (!idle || rose || now < retreatIdleSince) {
            retreatIdleSince = idle ? now : Long.MIN_VALUE;
        } else {
            if (retreatIdleSince == Long.MIN_VALUE) retreatIdleSince = now;
            stalled = BlazeFightRules.retreatStalled(now - retreatIdleSince);
        }
        retreatGaveUp = BlazeFightRules.retreatGaveUpNext(retreatGaveUp, mode == Mode.RETREAT, rose, stalled);
    }

    private void logChange(float health) {
        if (mode == loggedMode) return;
        loggedMode = mode;
        switch (mode) {
            case KILL -> Debug.logInternal("rods: going after a blaze (" + reachable + " in reach)");
            case COVER -> Debug.logInternal("rods: taking cover from " + plural(threats.size()));
            case RETREAT -> Debug.logInternal("rods: retreating at " + (int) health + " hp");
            case CAMP -> Debug.logInternal("rods: all quiet, back to camping");
            case HUNT -> Debug.logInternal("rods: " + plural(huntable.size()) + " about and none can see us, going to say hi");
        }
    }

    private static String plural(int blazes) {
        return blazes + (blazes == 1 ? " blaze" : " blazes");
    }

    // ---- what is out there

    private void scan(AltoClef mod, LocalPlayer player, long now, BlockPos spawner) {
        threats.clear();
        reachable = 0;
        huntable.clear();
        around = 0;
        skippedHunts.values().removeIf(when -> now < when || now - when > BAD_SPOT_TICKS);
        List<Blaze> blazes;
        try {
            blazes = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, one tick of stale blazes is fine
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (Blaze blaze : blazes) {
            if (!blaze.isAlive()) continue;
            double distance = player.distanceTo(blaze);
            if (distance > BlazeFightRules.MAX_CHASE_DISTANCE) continue;
            around++;
            seen.add(blaze.getId());
            Floor floor = floorUnder(mod.getWorld(), blaze);
            BlockPos stand = floor.pos() == null ? null : floor.pos().above();
            double fromSpawner = spawner == null || stand == null ? Double.NaN : Math.sqrt(stand.distSqr(spawner));
            updateReach(mod, blaze, floor, distance, fromSpawner, now);
            if (fightable(mod, blaze)) reachable++;
            // raycasts only for the ones that could actually hit us from where they are
            if (distance <= BlazeFightRules.SIGHT_RANGE && LookHelper.seesPlayer(blaze, player, BlazeFightRules.SIGHT_RANGE)) {
                threats.add(blaze);
            }
            boolean banned = !mod.getEntityTracker().isEntityReachable(blaze) || skippedHunts.containsKey(blaze.getId());
            if (BlazeFightRules.isHuntable(stand != null, floor.lava(), distance, banned, fromSpawner)) {
                huntable.add(new Hunt(blaze, stand));
            }
        }
        // forget blazes that died or left, the ids get reused
        reach.keySet().retainAll(seen);
        threats.sort(Comparator.comparingDouble(player::distanceToSqr));
        huntable.sort(Comparator.comparingDouble(h -> player.distanceToSqr(h.blaze())));
        if (!threats.isEmpty()) lastThreatTick = now;
    }

    // the one "can we go hit it" for both the reachable count and the kill task's picker. the picker reads what the scan
    // worked out this tick instead of redoing it, the sticky memory only moves once per scan
    private boolean fightable(AltoClef mod, Blaze blaze) {
        Reach r = reach.get(blaze.getId());
        if (r == null) return false;
        return BlazeFightRules.isFightable(r.reachable, !mod.getEntityTracker().isEntityReachable(blaze), r.fromSpawner);
    }

    private void updateReach(AltoClef mod, Blaze blaze, Floor floor, double distance, double fromSpawner, long now) {
        Reach r = reach.computeIfAbsent(blaze.getId(), id -> new Reach());
        r.fromSpawner = fromSpawner;
        double ceiling = r.reachable ? BlazeFightRules.STAY_REACHABLE_HEIGHT : BlazeFightRules.MAX_REACHABLE_HEIGHT;
        // cheap rejects first, the ray is the only part that costs anything
        if (floor.lava() || distance > BlazeFightRules.MAX_CHASE_DISTANCE || floor.height() > ceiling) {
            r.reachable = false;
            r.blocked = 0;
            return;
        }
        meleeLine(mod, blaze, r, now);
        r.reachable = BlazeFightRules.reachableNow(r.reachable, floor.height(), floor.lava(), distance, r.clear, r.blocked);
    }

    // walk straight down from the blaze to whatever it is hovering over. lava is a no, a floor is how high it is
    private static Floor floorUnder(Level level, Blaze blaze) {
        BlockPos at = blaze.blockPosition();
        for (int i = 0; i <= 8; i++) {
            BlockPos check = at.below(i);
            BlockState state = level.getBlockState(check);
            if (state.is(Blocks.LAVA)) return new Floor(null, Double.POSITIVE_INFINITY, true);
            if (!state.getCollisionShape(level, check).isEmpty()) {
                // fences and slabs and stairs are not a full block, use the actual top of the thing
                double height = blaze.getY() - (check.getY() + state.getCollisionShape(level, check).max(Direction.Axis.Y));
                return new Floor(check.immutable(), height, false);
            }
        }
        return new Floor(null, Double.POSITIVE_INFINITY, false);
    }

    // collision shapes, not visual ones: a nether brick fence is see through and swing proof. cached a few ticks per blaze,
    // and only a fresh ray moves the blocked streak, a cached answer is not a second opinion
    private static void meleeLine(AltoClef mod, Blaze blaze, Reach r, long now) {
        if (r.rayTick != Long.MIN_VALUE && now >= r.rayTick && now - r.rayTick < LINE_EVERY) return;
        LocalPlayer player = mod.getPlayer();
        r.rayTick = now;
        r.clear = !clipped(mod.getWorld(), player, player.getEyePosition(), blaze.getBoundingBox().getCenter());
        r.blocked = r.clear ? 0 : Math.min(r.blocked + 1, BlazeFightRules.BLOCKED_CHECKS_TO_DROP);
    }

    // the nearest one that is winding up a volley, that is the one the shield goes toward
    Blaze shieldTarget() {
        for (Blaze blaze : threats) {
            if (blaze.isOnFire()) return blaze;
        }
        return null;
    }

    // fire resistance keeps the flag but not the damage, no reason to run early for it
    private static boolean burning(LocalPlayer player) {
        return player.isOnFire() && !player.hasEffect(MobEffects.FIRE_RESISTANCE);
    }

    private boolean anyThreat() {
        return !threats.isEmpty();
    }

    private List<Entity> threatEntities() {
        return new ArrayList<>(threats);
    }

    // ---- what to do about it

    private Task cover(AltoClef mod, long now, BlockPos spawner) {
        BlockPos pick = chooseSpot(mod, now, spawner, false);
        // no spot at all is mostly us being out past the spawner (a run, a chase), and the candidates are only ever by it
        if (pick == null) return noCover(mod, now, spawner);
        campSpot = pick;
        return new BlazeCoverTask(pick, true, this::shieldTarget, this::markBad, this::anyThreat);
    }

    // campSpot is only written for the shield in place, our own block out past the spawner is not somewhere to come back to
    private Task noCover(AltoClef mod, long now, BlockPos spawner) {
        BlockPos feet = mod.getPlayer().blockPosition();
        // the same measure GoalNear finishes on, so "near" here and "arrived" there agree
        boolean near = spawner != null && feet.distSqr(spawner) <= BACK_TO_SPAWNER_RANGE * BACK_TO_SPAWNER_RANGE;
        return switch (BlazeFightRules.noCoverSpot(spawner != null, near, mod.getPlayer().getHealth(),
                burning(mod.getPlayer()))) {
            case BACK_TO_SPAWNER -> new GetWithinRangeOfBlockTask(spawner, BACK_TO_SPAWNER_RANGE);
            case SHIELD_HERE -> {
                campSpot = feet;
                yield new BlazeCoverTask(feet, true, this::shieldTarget, this::markBad, this::anyThreat);
            }
            case RETREAT -> retreat(mod, now, spawner);
        };
    }

    private Task retreat(AltoClef mod, long now, BlockPos spawner) {
        // nothing is looking at us, so this is just sitting tight until the food chain and regen are done
        if (threats.isEmpty()) return null;
        BlockPos pick = chooseSpot(mod, now, spawner, true);
        if (pick != null) {
            return new BlazeCoverTask(pick, false, this::shieldTarget, this::markBad, this::anyThreat);
        }
        // nowhere nearby hides us from all of them. run from them, the threat list is who we mean (not just the charged ones)
        return new RunAwayFromHostilesTask(RUN_DISTANCE, true, this::threatEntities);
    }

    // walk to the floor under a blaze, not the blaze: one hovering five up has no standing spot in a follow goal, so the
    // path pillars or fails and the tracker bans it. the mode is re-decided every tick, so the walk ends the moment one
    // sees us (COVER) or gets in reach (KILL)
    private Task hunt(long now) {
        // a long break with nothing worth the walk is a fresh start too, the old clock is not this hunt's fault. a cover
        // break is not long, see huntBreakIsFreshStart
        boolean longBreak = lastHuntTick == Long.MIN_VALUE || now < lastHuntTick
                || BlazeFightRules.huntBreakIsFreshStart(now - lastHuntTick);
        if (longBreak) huntTarget = null;
        lastHuntTick = now;
        if (huntTarget != null && BlazeFightRules.huntTakingTooLong(now >= huntSince ? now - huntSince : 0)) {
            skippedHunts.put(huntTarget.getId(), now);
            Blaze gone = huntTarget;
            huntable.removeIf(h -> h.blaze() == gone);
            huntTarget = null;
        }
        // that was the only one, sit tight this tick and the next scan says camp
        if (huntable.isEmpty()) return null;
        Hunt pick = null;
        for (Hunt h : huntable) {
            if (h.blaze() == huntTarget) {
                pick = h;
                break;
            }
        }
        boolean changed = pick == null;
        if (changed) {
            pick = huntable.get(0);
            huntSince = now;
        }
        double drift = huntGoal == null ? Double.POSITIVE_INFINITY : Math.sqrt(pick.stand().distSqr(huntGoal));
        if (BlazeFightRules.needsNewHuntGoal(changed, drift)) {
            huntTarget = pick.blaze();
            huntGoal = pick.stand();
        }
        return new GetWithinRangeOfBlockTask(huntGoal, HUNT_RANGE);
    }

    private void markBad(BlockPos bad) {
        badSpots.put(bad.immutable(), mod().getWorld().getGameTime());
        for (Held h : new Held[]{coverHeld, retreatHeld}) {
            h.tick = Long.MIN_VALUE;
            if (bad.equals(h.spot)) {
                h.spot = null;
                h.misses = 0;
            }
        }
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
        Held h = forRetreat ? retreatHeld : coverHeld;
        if (h.tick != Long.MIN_VALUE && now >= h.tick && now - h.tick < SPOT_EVERY) return h.spot;
        h.tick = now;
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        List<Blaze> shooters = threats.size() > MAX_SHOOTERS ? threats.subList(0, MAX_SHOOTERS) : threats;
        if (h.spot != null && (isBad(h.spot, now) || !standable(level, h.spot)
                || outsideSearch(player.blockPosition(), h.spot, spawner, forRetreat ? RETREAT_RADIUS_WIDE : COVER_RADIUS))) {
            h.spot = null;
            h.misses = 0;
        }
        // the spot we hold gets the benefit of the doubt: blazes bob and the shooter list changes, and the strict search
        // used to throw away a spot we were halfway to over one flickering ray
        if (h.spot != null) {
            int exposed = shooters.size() - countBlocked(player, level, h.spot, shooters);
            if (BlazeFightRules.heldSpotHolds(exposed, shooters.size())) {
                h.misses = 0;
                return h.spot;
            }
            h.misses++;
            if (!BlazeFightRules.heldSpotGivenUp(h.misses)) return h.spot;
        }
        BlockPos best = search(mod, now, spawner, forRetreat ? RETREAT_RADIUS : COVER_RADIUS, shooters, forRetreat, h.spot);
        if (best == null && forRetreat) {
            best = search(mod, now, spawner, RETREAT_RADIUS_WIDE, shooters, true, h.spot);
        }
        if (h.spot != null) {
            // an empty search is a bad moment, not an answer. and a few steps from a spot beats a better one far off
            if (best == null && BlazeFightRules.emptySearchKeeps(h.misses)) return h.spot;
            if (best != null && !best.equals(h.spot)
                    && !BlazeFightRules.swapAllowed(distance(player, h.spot), distance(player, best),
                    countBlocked(player, level, h.spot, shooters) > 0)) {
                return h.spot;
            }
        }
        if (!java.util.Objects.equals(best, h.spot)) h.misses = 0;
        h.spot = best;
        return best;
    }

    // the fight lives as long as the rods task, so a spot from an earlier fight can be anywhere. the search only ever looks in
    // this box, a held spot outside it would be a long walk under fire that nothing else could have picked
    private static boolean outsideSearch(BlockPos origin, BlockPos pos, BlockPos spawner, int radius) {
        int dy = pos.getY() - origin.getY();
        return Math.abs(pos.getX() - origin.getX()) > radius || Math.abs(pos.getZ() - origin.getZ()) > radius
                || dy < -2 || dy > 1 || (spawner != null && pos.distSqr(spawner) > SPOT_NEAR_SPAWNER_SQ);
    }

    private static double distance(LocalPlayer player, BlockPos pos) {
        return player.position().distanceTo(Vec3.atCenterOf(pos));
    }

    private BlockPos search(AltoClef mod, long now, BlockPos spawner, int radius, List<Blaze> shooters, boolean needAll,
                             BlockPos held) {
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
            if (pos.equals(held)) score += STAY_BONUS;
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
