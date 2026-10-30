package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.BlazeFightRules.Mode;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.RecoverHold;
import adris.altoclef.util.helpers.RunZones;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// the per tick brain of the rods task while there are blazes about. it only decides, BlazeFightRules has the numbers.
// one blaze at a time: pick the nearest one we can reach, hit it until it is dead, then pick the next. null from tick() means
// "nothing to do right now" (camping, or running with nobody in sight)
final class BlazeFight {

    private static final double RUN_DISTANCE = 24;
    // same reach the plain run's crowd uses for "who is after us"
    private static final double RUN_CROWD_RADIUS = 20;

    private Mode mode = Mode.CAMP;
    private Mode loggedMode = Mode.CAMP;
    private boolean running;
    // the combat run end we already took a heal wait from, so one run arms it once
    private long armedFromRun = Long.MIN_VALUE;
    // the run stall watch: when the idle run started (or last gained hp), what hp was last tick, and whether we gave up
    // waiting. MIN_VALUE is not watching
    private long runIdleSince = Long.MIN_VALUE;
    private float lastHealth = Float.NaN;
    private boolean runGaveUp;
    // the blaze or skeleton whose projectile hit us last, and when. a run with that thrower out of the crowd lists used to stop and
    // stand still in its line of fire
    private DamageSource shotSeen;
    private Mob shooter;
    private long shotAt = Long.MIN_VALUE / 2;
    // blazes with a line on us, and blazes we could walk under and hit, as of the last scan
    private final List<Blaze> threats = new ArrayList<>();
    private final List<Blaze> candidates = new ArrayList<>();
    private int around;
    // the one blaze we are after. it stays ours until it dies, goes away or sits out of reach for a while, so two similar
    // blazes can never have us changing our mind
    private Blaze target;
    private boolean targetTracked;
    private boolean targetInReach;
    // the target sits by a wither skeleton pack as of the last scan, so it is dropped
    private boolean packDrop;
    // a pack within PACK_PLAYER_RADIUS of us (packSize of them, around packCenter) and whether it is also inside the back off radius
    private boolean packHere;
    private boolean packClose;
    private int packSize;
    private double[] packCenter;
    private boolean packLogged;
    private long outOfReachSince = Long.MIN_VALUE;
    private double range = BlazeFightRules.PICK_RANGE;
    // read off the pathfinder thread. fighting and running never place blocks at our feet or above: pillaring up at a hovering
    // blaze is how we ended up building next to one
    private volatile boolean noPlacing;
    // our feet y as of the last tick, for the height rule in noPlacing(pos)
    private volatile int floorY = Integer.MIN_VALUE;

    // the rods are in: only blazes close by still matter
    void disengageOnly() {
        range = BlazeFightRules.DISENGAGE_RANGE;
    }

    // fighting and running are the only places that place nothing, anywhere else (the walk to the spawner) may bridge
    void idle() {
        noPlacing = false;
    }

    Mode mode() {
        return mode;
    }

    boolean noPlacing() {
        return noPlacing;
    }

    // the ban by height: below our feet is bridge and traverse territory and stays open, so a bridge we were halfway across
    // still has a way off. feet level and up (pillar, ascend) is what builds us next to a blaze
    boolean noPlacing(BlockPos pos) {
        return placingBanned(noPlacing, pos.getY(), floorY);
    }

    static boolean placingBanned(boolean noPlacing, int placeY, int floorY) {
        return noPlacing && placeY >= floorY;
    }

    // blazes we could hit right now, and every live blaze in range (that one is just for the hud)
    int reachable() {
        return candidates.size();
    }

    int around() {
        return around;
    }

    boolean runGaveUp() {
        return runGaveUp;
    }

    // a wither skeleton pack is within PACK_PLAYER_RADIUS of us as of the last scan: no blaze target, no walking in
    boolean packHere() {
        return packHere;
    }

    Task tick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        scan(mod, player);
        noteShot(player, now);
        updateTarget(player, now);
        float health = player.getHealth();
        // hp going up is what the run waits for, so any rise means it is working again
        if (health > lastHealth) runGaveUp = false;
        // a run mob defense made also leaves us hurt: wait it out here like our own run, it is the same latch
        if (!running && mod.getMobDefenseChain() != null) {
            long runEnd = mod.getMobDefenseChain().lastCombatRunEnd();
            double blazeDistance = target == null ? Double.POSITIVE_INFINITY : player.distanceTo(target);
            if (runEnd != armedFromRun && BlazeFightRules.healAfterRun(now, runEnd, health, blazeDistance)) {
                running = true;
                armedFromRun = runEnd;
            }
        }
        boolean burning = player.isOnFire() && !player.hasEffect(MobEffects.FIRE_RESISTANCE);
        Mode next = BlazeFightRules.decide(health, running, runGaveUp, !threats.isEmpty(), target != null, burning);
        running = next == Mode.RUN;
        mode = next;
        noPlacing = mode != Mode.CAMP;
        floorY = player.getBlockY();
        watchRun(now, health);
        lastHealth = health;
        logChange(mod, health);
        notePack(mod, now);
        return switch (mode) {
            // (a pack on top of us gets a short run, a pack a bit further off is the caller's hold)
            case CAMP -> packClose ? new RunAwayFromHostilesTask(BlazeFightRules.PACK_BACKOFF_DISTANCE, true, () -> runCrowd(mod)) : null;
            case KILL -> new KillEntityTask(target);
            // never stand still in a run: with no blaze on us the wither skeletons that kept chasing still count, only an empty
            // crowd (nobody at all) is sitting tight until the food chain and regen are done
            // (a shot that landed lately keeps the thrower in that crowd, see runCrowd)
            case RUN -> {
                List<Entity> crowd = runCrowd(mod);
                yield crowd.isEmpty() ? null : new RunAwayFromHostilesTask(RUN_DISTANCE, true, () -> runCrowd(mod));
            }
        };
    }

    // one line and one zone per time the pack check trips, not per tick. the zone is what the next spawner walk routes round
    private void notePack(AltoClef mod, long now) {
        if (!packHere) {
            packLogged = false;
            return;
        }
        if (packLogged) return;
        packLogged = true;
        if (packCenter != null) RunZones.INSTANCE.add(packCenter[0], packCenter[1], now, RunZones.PACK_RADIUS);
        Vec3 at = mod.getPlayer().position();
        Debug.logInternal("pack: " + packSize + " wither skeletons within " + (int) BlazeFightRules.PACK_PLAYER_RADIUS + " at "
                + Math.round(at.x) + "," + Math.round(at.y) + "," + Math.round(at.z) + ", " + (packClose ? "backing off" : "holding"));
    }

    // projectile hits, every tick: the game hands a fresh source object per hit
    private void noteShot(LocalPlayer player, long now) {
        DamageSource source = player.getLastDamageSource();
        if (source == null || source == shotSeen) return;
        shotSeen = source;
        if (source.getDirectEntity() instanceof Projectile && source.getEntity() instanceof Mob mob
                && RecoverHold.rerunSource(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath())) {
            shooter = mob;
            shotAt = now;
        }
    }

    // blazes close enough to matter plus whatever else is after us
    private List<Entity> runCrowd(AltoClef mod) {
        List<Entity> blazes = new ArrayList<>(threats);
        List<Entity> others = new ArrayList<>();
        // the thing that shot us lately is still after us even when it is out of the lists below (past 20, or not angry on paper)
        if (shooter != null && shooter.isAlive() && BlazeFightRules.shotLately(mod.getWorld().getGameTime(), shotAt)
                && !blazes.contains(shooter)) {
            others.add(shooter);
        }
        try {
            LocalPlayer player = mod.getPlayer();
            for (Blaze blaze : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (blaze.isAlive() && player.distanceTo(blaze) <= BlazeFightRules.SIGHT_RANGE && !blazes.contains(blaze)) blazes.add(blaze);
            }
            for (Entity hostile : mod.getEntityTracker().getHostiles()) {
                if (hostile instanceof Blaze || !hostile.isAlive() || player.distanceTo(hostile) > RUN_CROWD_RADIUS) continue;
                if (EntityHelper.isAngryAtPlayer(mod, hostile)) others.add(hostile);
            }
            // the hostile list only holds the ones that noticed us, a pack that has not is still a place not to run to
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (skeleton.isAlive() && player.distanceTo(skeleton) <= RUN_CROWD_RADIUS && !others.contains(skeleton)) others.add(skeleton);
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes, we get the crowd again next tick
        }
        return merge(blazes, others);
    }

    // live wither skeletons within radius of a spot. 0 when the tracker is mid rebuild, we ask again next tick
    static int witherSkeletonsNear(AltoClef mod, Vec3 at, double radius) {
        int count = 0;
        try {
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (skeleton.isAlive() && skeleton.position().distanceTo(at) <= radius) count++;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return count;
    }

    // x/z of every live wither skeleton within radius of a spot, same tracker rules as witherSkeletonsNear
    static List<double[]> witherSkeletonSpots(AltoClef mod, Vec3 at, double radius) {
        List<double[]> spots = new ArrayList<>();
        try {
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (skeleton.isAlive() && skeleton.position().distanceTo(at) <= radius) spots.add(new double[]{skeleton.getX(), skeleton.getZ()});
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return spots;
    }

    static <T> List<T> merge(List<? extends T> blazes, List<? extends T> others) {
        List<T> all = new ArrayList<>(blazes);
        all.addAll(others);
        return all;
    }

    // an idle run (nobody looking, just healing) that has not gained hp in RUN_GIVE_UP_TICKS gives up. runs after decide, the
    // flag is read by the next one
    private void watchRun(long now, float health) {
        boolean rose = health > lastHealth;
        boolean idle = mode == Mode.RUN && threats.isEmpty();
        boolean stalled = false;
        if (!idle || rose || now < runIdleSince) {
            runIdleSince = idle ? now : Long.MIN_VALUE;
        } else {
            if (runIdleSince == Long.MIN_VALUE) runIdleSince = now;
            stalled = BlazeFightRules.runStalled(now - runIdleSince);
        }
        runGaveUp = BlazeFightRules.runGaveUpNext(runGaveUp, mode == Mode.RUN, rose, stalled);
    }

    // where the run starts and who is in it, so a run into unseen skeletons can be told from a run into nothing
    private String runDetail(AltoClef mod, float health) {
        LocalPlayer player = mod.getPlayer();
        StringBuilder crowd = new StringBuilder();
        for (Entity e : runCrowd(mod)) {
            if (crowd.length() > 0) crowd.append(", ");
            crowd.append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()).append(' ').append(Math.round(player.distanceTo(e)));
        }
        return "rods: running at " + String.format(java.util.Locale.ROOT, "%.1f", health) + " hp from " + player.blockPosition().toShortString()
                + ", " + RUN_DISTANCE + " block run, crowd [" + crowd + "], burning " + player.isOnFire()
                + ", wither " + player.hasEffect(MobEffects.WITHER);
    }

    private void logChange(AltoClef mod, float health) {
        if (mode == loggedMode) return;
        loggedMode = mode;
        switch (mode) {
            case KILL -> Debug.logInternal("rods: going after a blaze (" + candidates.size() + " in reach)");
            case RUN -> Debug.logInternal(runDetail(mod, health));
            case CAMP -> Debug.logInternal("rods: all quiet, back to camping");
        }
    }

    // ---- what is out there

    private void scan(AltoClef mod, LocalPlayer player) {
        List<Blaze> blazes;
        try {
            blazes = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, one tick of stale blazes is fine
            return;
        }
        threats.clear();
        candidates.clear();
        packDrop = false;
        // (the same pack check as for a blaze below, asked where we stand: the closet is the thing, not the blaze over it)
        List<double[]> nearUs = witherSkeletonSpots(mod, player.position(), BlazeFightRules.PACK_PLAYER_RADIUS);
        packHere = BlazeFightRules.packNear(nearUs.size());
        packSize = nearUs.size();
        packCenter = packHere ? BlazeFightRules.packCenter(nearUs) : null;
        // (once the back off starts it runs until the pack is out of the hold radius too, else it stops at 8.1 blocks with them still on top of us)
        packClose = packHere && (packClose || BlazeFightRules.packNear(witherSkeletonsNear(mod, player.position(), BlazeFightRules.PACK_BACKOFF_RADIUS)));
        around = 0;
        targetTracked = false;
        targetInReach = false;
        for (Blaze blaze : blazes) {
            if (!blaze.isAlive()) continue;
            double distance = player.distanceTo(blaze);
            boolean inReach = false;
            // a blaze hovering over a wither skeleton pack is theirs, not ours
            // the one we are already after is checked at any distance, so it can't drift out of pick range and keep us walking at a pack
            boolean packed = packHere || ((distance <= range || blaze == target)
                    && BlazeFightRules.packNear(witherSkeletonsNear(mod, blaze.position(), BlazeFightRules.PACK_BLAZE_RADIUS)));
            if (packed && blaze == target) packDrop = true;
            if (distance <= range && !packed) {
                around++;
                Floor floor = floorUnder(mod.getWorld(), blaze);
                inReach = BlazeFightRules.isReachable(floor.height(), floor.lava(), distance, range, blaze == target)
                        && mod.getEntityTracker().isEntityReachable(blaze);
                if (inReach) candidates.add(blaze);
            }
            if (blaze == target) {
                targetTracked = true;
                targetInReach = inReach;
            }
            // raycasts only for the ones that could actually hit us from where they are
            if (distance <= BlazeFightRules.SIGHT_RANGE && LookHelper.seesPlayer(blaze, player, BlazeFightRules.SIGHT_RANGE)) {
                threats.add(blaze);
            }
        }
    }

    // keep the blaze we have until it is dead, gone or has been out of reach a while, then take the nearest one in reach
    private void updateTarget(LocalPlayer player, long now) {
        if (target != null) {
            // a clock that went backwards (new world) starts the out of reach count over
            if (targetInReach || now < outOfReachSince) {
                outOfReachSince = targetInReach ? Long.MIN_VALUE : now;
            } else if (outOfReachSince == Long.MIN_VALUE) {
                outOfReachSince = now;
            }
            long outFor = outOfReachSince == Long.MIN_VALUE ? 0 : now - outOfReachSince;
            if (packDrop || !BlazeFightRules.keepTarget(target.isAlive(), targetTracked, outFor)) target = null;
        }
        if (target != null) return;
        outOfReachSince = Long.MIN_VALUE;
        Blaze nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Blaze blaze : candidates) {
            double d = player.distanceToSqr(blaze);
            if (d < best) {
                best = d;
                nearest = blaze;
            }
        }
        target = nearest;
        targetTracked = nearest != null;
        targetInReach = nearest != null;
    }

    private List<Entity> threatEntities() {
        return new ArrayList<>(threats);
    }

    // what is under a blaze: how high it hovers over the block we would stand on, and whether it is lava instead
    private record Floor(double height, boolean lava) {
    }

    // walk straight down from the blaze to whatever it is hovering over. lava is a no, a floor is how high it is
    private static Floor floorUnder(Level level, Blaze blaze) {
        BlockPos at = blaze.blockPosition();
        for (int i = 0; i <= 8; i++) {
            BlockPos check = at.below(i);
            BlockState state = level.getBlockState(check);
            if (state.is(Blocks.LAVA)) return new Floor(Double.POSITIVE_INFINITY, true);
            if (!state.getCollisionShape(level, check).isEmpty()) {
                // fences and slabs and stairs are not a full block, use the actual top of the thing
                return new Floor(blaze.getY() - (check.getY() + state.getCollisionShape(level, check).max(Direction.Axis.Y)), false);
            }
        }
        return new Floor(Double.POSITIVE_INFINITY, false);
    }
}
