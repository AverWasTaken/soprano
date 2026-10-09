package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.MobReachRules.Terrain;
import adris.altoclef.util.helpers.MobReachRules.Verdict;
import baritone.Baritone;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;

// "is this angry mob actually a problem, or is it screaming at us from the wrong side of a wall". the client has no mob
// navigation (the server runs the ai) so this is geometry plus watching whether it ever gets closer. EntityTracker owns
// one and wipes it with the world.
public class MobReachability {

    // a verdict sticks for this long. the isAggressive flag lags and so does everything else, flip flopping every tick
    // would make the bot dance between fighting and ignoring
    private static final int HOLD_TICKS = 10;
    private static final int SIGHT_HOLD_TICKS = 5;
    private static final int PRUNE_EVERY = 100;
    private static final int FORGET_AFTER = 200;
    // not asked about a mob for this long (we were eating, it went out of range) and the fight is over as far as we know
    private static final int ENGAGE_FORGET_TICKS = 40;

    private record Cached(long tick, Verdict verdict) {
    }

    private record Sight(long tick, boolean sees) {
    }

    private final HashMap<Integer, Cached> _verdicts = new HashMap<>();
    private final HashMap<Integer, Sight> _sight = new HashMap<>();
    private final MobReachRules.StallTracker _stall = new MobReachRules.StallTracker();
    // the defense chain says so every tick: we are running, so a mob that keeps pace is not stuck (MobReachRules.FLEEING_STALL_RANGE)
    private volatile boolean _fleeing;
    private final MobReachRules.ClosingTracker _closing = new MobReachRules.ClosingTracker();
    // mobs we are currently fighting or running from, and when we last asked about them. these get the leash instead of
    // the small engage zone
    private final HashMap<Integer, Long> _engaged = new HashMap<>();
    // one line per mob per reason in the log, not one per tick
    private final Set<Long> _logged = new HashSet<>();

    // the player is the same player for every mob, so the enclosure answer lives for the tick
    private long _enclosedTick = Long.MIN_VALUE;
    private long _enclosedPos;
    private boolean _enclosed;
    private long _lastPrune;

    // can it walk up and hit us. this is the one that decides whether we are allowed to go chase it
    public boolean canWalkToPlayer(AltoClef mod, Mob mob) {
        if (isUngated(mob)) return true;
        LocalPlayer player = mod.getPlayer();
        // hitting what is adjacent is always fine, no matter how weird the rest of the geometry is. needs a clear swing
        // though, a zombie on the far side of a wall is "in reach" but we cannot hit it and the chase walks us out
        if (mod.getControllerExtras().inRange(mob) && LookHelper.cleanLineOfSight(player, mob.getEyePosition(), 8)) {
            _verdicts.put(mob.getId(), new Cached(mod.getWorld().getGameTime(), Verdict.REACHABLE));
            return true;
        }
        long now = mod.getWorld().getGameTime();
        Cached cached = _verdicts.get(mob.getId());
        // (a stalled verdict from when it was further out does not outlive it walking up to us)
        if (cached != null && now >= cached.tick && now - cached.tick < HOLD_TICKS
                && MobReachRules.verdictHolds(cached.verdict, mob.distanceTo(player), _fleeing)) {
            return cached.verdict.reachable();
        }
        Verdict verdict = evaluate(mod, mob, now);
        _verdicts.put(mob.getId(), new Cached(now, verdict));
        if (!verdict.reachable()) noteIgnored(mob, verdict);
        prune(now);
        return verdict.reachable();
    }

    // can it hurt us at all. ranged mobs with a clear shot count even if they can never walk to us, we just do not chase them
    public boolean canHarmPlayer(AltoClef mod, Mob mob) {
        if (canWalkToPlayer(mod, mob)) return true;
        return isRanged(mob) && seesPlayer(mod, mob);
    }

    // should this angry mob pull us off our task. asked once per tick per hostile, it keeps the closing in history and
    // the "we are in a fight with this one" memory, so ask it before the expensive questions. the dangerous
    // oddballs (flyers, endermen, bosses, a lit creeper) skip the zone entirely, their own logic is better at them
    public boolean shouldEngage(AltoClef mod, Mob mob) {
        return shouldEngage(mod, mob, false);
    }

    // travelling means we are on our way somewhere: a zombie walking at us from ten blocks is not a reason to stop, if it
    // gets to the small zone (or to us) it is a fight like any other. the history still gets fed, it is the same tracker
    public boolean shouldEngage(AltoClef mod, Mob mob, boolean travelling) {
        if (isUngated(mob)) return true;
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        double dx = mob.getX() - player.getX(), dy = mob.getY() - player.getY(), dz = mob.getZ() - player.getZ();
        double range = Baritone.settings().altoHostileEngageRange.value;
        double height = Baritone.settings().altoHostileEngageHeight.value;
        boolean closing = _closing.update(mob.getId(), mob.distanceTo(player), now);
        boolean shooter = isRanged(mob);
        boolean leashed = MobReachRules.inLeash(dx, dy, dz, range, height, shooter);

        Long last = _engaged.get(mob.getId());
        if (leashed && last != null && now >= last && now - last <= ENGAGE_FORGET_TICKS) {
            // already in it with this one, so it only has to stay inside the big zone. checking the small one every
            // tick is how a zombie at exactly 8 blocks makes us dance
            _engaged.put(mob.getId(), now);
            return true;
        }
        _engaged.remove(mob.getId());
        // the line of sight raycast is not free, only the ones that could shoot us from out here get one
        boolean ranged = leashed && shooter;
        boolean sees = ranged && seesPlayer(mod, mob);
        if (MobReachRules.shouldEngage(dx, dy, dz, range, height, closing && !travelling, ranged, sees)) {
            _engaged.put(mob.getId(), now);
            return true;
        }
        noteTooFar(mob, MobReachRules.ignoreReason(dx, dy, dz, range, height));
        prune(now);
        return false;
    }

    // the chase leash: once we are after something, how far it can get before we let it go. no memory, just the box
    public boolean inLeash(AltoClef mod, Mob mob) {
        if (isUngated(mob)) return true;
        LocalPlayer player = mod.getPlayer();
        return MobReachRules.inLeash(mob.getX() - player.getX(), mob.getY() - player.getY(), mob.getZ() - player.getZ(),
                Baritone.settings().altoHostileEngageRange.value, Baritone.settings().altoHostileEngageHeight.value, isRanged(mob));
    }

    public void setFleeing(boolean fleeing) {
        _fleeing = fleeing;
    }

    public void reset() {
        _fleeing = false;
        _verdicts.clear();
        _sight.clear();
        _stall.clear();
        _closing.clear();
        _engaged.clear();
        _logged.clear();
        _enclosedTick = Long.MIN_VALUE;
    }

    private Verdict evaluate(AltoClef mod, Mob mob, long now) {
        LocalPlayer player = mod.getPlayer();
        if (isEnclosed(mod, now)) return Verdict.ENCLOSED;
        // spiders go up walls, everything else has to walk
        if (!(mob instanceof Spider) && MobReachRules.isVerticallyOut(mob.getX() - player.getX(), mob.getY() - player.getY(), mob.getZ() - player.getZ())) {
            return Verdict.VERTICAL_GAP;
        }
        if (_stall.update(mob.getId(), mob.distanceTo(player), now, _fleeing)) return Verdict.STALLED;
        return Verdict.REACHABLE;
    }

    private boolean isEnclosed(AltoClef mod, long now) {
        BlockPos at = mod.getPlayer().blockPosition();
        if (now != _enclosedTick || at.asLong() != _enclosedPos) {
            _enclosed = MobReachRules.isEnclosed(terrain(mod.getWorld()), at.getX(), at.getY(), at.getZ());
            _enclosedTick = now;
            _enclosedPos = at.asLong();
        }
        return _enclosed;
    }

    public boolean seesPlayer(AltoClef mod, Mob mob) {
        long now = mod.getWorld().getGameTime();
        Sight sight = _sight.get(mob.getId());
        if (sight == null || now < sight.tick || now - sight.tick >= SIGHT_HOLD_TICKS) {
            sight = new Sight(now, LookHelper.seesPlayer(mob, mod.getPlayer(), 20));
            _sight.put(mob.getId(), sight);
        }
        return sight.sees;
    }

    private void noteIgnored(Mob mob, Verdict verdict) {
        if (_logged.add(((long) mob.getId() << 3) | verdict.ordinal())) {
            Debug.logInternal("Ignoring " + shortName(mob) + " #" + mob.getId() + ", it can't reach us (" + verdict.name().toLowerCase() + ")");
        }
    }

    // verdict ordinals are 0..3, so 4 is free for "out of the engage zone"
    private void noteTooFar(Mob mob, String reason) {
        if (_logged.add(((long) mob.getId() << 3) | 4)) {
            Debug.logInternal("Ignoring " + shortName(mob) + " #" + mob.getId() + ", " + reason);
        }
    }

    // entity.minecraft.zombie -> zombie
    public static String shortName(Mob mob) {
        String id = mob.getType().getDescriptionId();
        return id.substring(id.lastIndexOf('.') + 1);
    }

    // ids of mobs we have not asked about in a while go away, the world reuses ids and dead mobs never come back
    private void prune(long now) {
        if (now - _lastPrune < PRUNE_EVERY) return;
        _lastPrune = now;
        _verdicts.values().removeIf(c -> now - c.tick > FORGET_AFTER);
        _sight.values().removeIf(s -> now - s.tick > FORGET_AFTER);
        _stall.prune(now);
        _closing.prune(now);
        _engaged.values().removeIf(t -> now - t > FORGET_AFTER);
        // the log set has no timestamps, so it just goes when it gets silly
        if (_logged.size() > 512) _logged.clear();
    }

    // things that fly, teleport, burrow or are already on our face. the gate would only ever be wrong about them
    public static boolean isUngated(Mob mob) {
        if (mob instanceof FlyingMob || mob instanceof Blaze || mob instanceof Vex || mob instanceof EnderMan
                || mob instanceof EnderDragon || mob instanceof WitherBoss || mob instanceof Warden
                || mob instanceof Shulker || mob instanceof Guardian || mob instanceof Bee) {
            return true;
        }
        // a lit fuse is the creeper logic's business, not ours
        if (mob instanceof Creeper creeper && creeper.getSwelling(1) > 0.001) return true;
        // jockeys and anything else riding something go wherever the ride goes
        return mob.isPassenger();
    }

    public static boolean isRanged(Mob mob) {
        // drowned only shoot if they found a trident, piglins only if they hold a crossbow. otherwise they are melee
        if (mob instanceof Piglin) return mob.isHolding(Items.CROSSBOW);
        if (mob instanceof Drowned) return mob.isHolding(Items.TRIDENT);
        return mob instanceof RangedAttackMob || mob instanceof CrossbowAttackMob || mob instanceof SpellcasterIllager;
    }

    private static Terrain terrain(Level level) {
        return new Terrain() {
            @Override
            public boolean wall(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (passable(state)) return false;
                VoxelShape shape = state.getCollisionShape(level, pos);
                return !shape.isEmpty() && shape.max(Direction.Axis.Y) > MobReachRules.STEP_HEIGHT;
            }

            @Override
            public boolean solid(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                return !passable(state) && !state.getCollisionShape(level, pos).isEmpty();
            }
        };
    }

    // zombies break wooden doors and open the rest, so a closed one is not a wall to them. iron stays shut. water and
    // lava have no collision so they already read as open
    private static boolean passable(BlockState state) {
        if (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) return false;
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }
}
