package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.BlazeFightRules.Mode;
import adris.altoclef.util.helpers.LookHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// the per tick brain of the rods task while there are blazes about. it only decides, BlazeFightRules has the numbers.
// one blaze at a time: pick the nearest one we can reach, hit it until it is dead, then pick the next. null from tick() means
// "nothing to do right now" (camping, or running with nobody in sight)
final class BlazeFight {

    private static final double RUN_DISTANCE = 24;

    private Mode mode = Mode.CAMP;
    private Mode loggedMode = Mode.CAMP;
    private boolean running;
    // the run stall watch: when the idle run started (or last gained hp), what hp was last tick, and whether we gave up
    // waiting. MIN_VALUE is not watching
    private long runIdleSince = Long.MIN_VALUE;
    private float lastHealth = Float.NaN;
    private boolean runGaveUp;
    // blazes with a line on us, and blazes we could walk under and hit, as of the last scan
    private final List<Blaze> threats = new ArrayList<>();
    private final List<Blaze> candidates = new ArrayList<>();
    private int around;
    // the one blaze we are after. it stays ours until it dies, goes away or sits out of reach for a while, so two similar
    // blazes can never have us changing our mind
    private Blaze target;
    private boolean targetTracked;
    private boolean targetInReach;
    private long outOfReachSince = Long.MIN_VALUE;
    private double range = BlazeFightRules.PICK_RANGE;
    // read off the pathfinder thread. fighting and running never place blocks: pillaring up at a hovering blaze is how we ended
    // up building next to one
    private volatile boolean noPlacing;

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

    Task tick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        scan(mod, player);
        updateTarget(player, now);
        float health = player.getHealth();
        // hp going up is what the run waits for, so any rise means it is working again
        if (health > lastHealth) runGaveUp = false;
        Mode next = BlazeFightRules.decide(health, running, runGaveUp, !threats.isEmpty(), target != null);
        running = next == Mode.RUN;
        mode = next;
        noPlacing = mode != Mode.CAMP;
        watchRun(now, health);
        lastHealth = health;
        logChange(health);
        return switch (mode) {
            case CAMP -> null;
            case KILL -> new KillEntityTask(target);
            // nobody is looking at us, so this is just sitting tight until the food chain and regen are done
            case RUN -> threats.isEmpty() ? null : new RunAwayFromHostilesTask(RUN_DISTANCE, true, this::threatEntities);
        };
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

    private void logChange(float health) {
        if (mode == loggedMode) return;
        loggedMode = mode;
        switch (mode) {
            case KILL -> Debug.logInternal("rods: going after a blaze (" + candidates.size() + " in reach)");
            case RUN -> Debug.logInternal("rods: running at " + (int) health + " hp");
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
        around = 0;
        targetTracked = false;
        targetInReach = false;
        for (Blaze blaze : blazes) {
            if (!blaze.isAlive()) continue;
            double distance = player.distanceTo(blaze);
            boolean inReach = false;
            if (distance <= range) {
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
            if (!BlazeFightRules.keepTarget(target.isAlive(), targetTracked, outFor)) target = null;
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
