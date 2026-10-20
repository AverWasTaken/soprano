package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.CommittedRunTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.helpers.AnchorWatch;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FightNames;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.Provocations;
import adris.altoclef.util.helpers.ReachRules;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WeaponPick;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Dimension;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// the world half of CombatCommit, for every dimension. MobDefenseChain calls tick() once per game tick (that is the only
// place the machine moves) and then asks what to hold the wheel with. nothing in here hands the wheel back: that is the
// chain's call, and it only does it when the machine says NONE. who counts as a foe in which dimension is FoeRules
final class CombatBrain {

    // the steady priorities of a commitment. above the user task (50) and the food chain (55), same numbers the old
    // kill (65) and flee (80) branches used, so nothing that was ordered against them moved
    static final float FIGHT_PRIORITY = 65;
    static final float RUN_PRIORITY = 80;
    // the tracker's hostile list does not reach past 16 anyway (it keeps mobs closer than 16), and the warden's boom
    // counts out to 15 (CombatCommit.BOOM_RANGE), so this stays 16 even though a run now calls 12 clear
    private static final double FOE_RANGE = CombatCommit.FOE_RANGE;

    private final CombatCommit _commit = new CombatCommit();
    // standing about is a different fight from walking about (see AnchorWatch)
    private final AnchorWatch _anchor = new AnchorWatch();
    // this tick's angry hostiles as entities, nearest first (the run goal steers around them)
    private List<Mob> _foeMobs = List.of();
    // who the aura may swing at: the fight target and anything in contact that has hit us
    private final Set<Integer> _swingAt = new HashSet<>();
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    private int _meleeNear;
    private int _meleeAround;
    private boolean _shotAt;
    // a normal fight with a second mob hitting us, the shield is up for it (CombatRules.pileShield)
    private boolean _pile;
    private static final long STANCE_LOG_GAP = 20;
    private CombatRules.Stance _loggedStance = CombatRules.Stance.CALM;
    private long _stanceLogAt = Long.MIN_VALUE / 2;
    private float _lastHealth = -1;
    private DamageSource _lastSource;
    private CommittedRunTask _run;
    private KillEntityTask _fight;
    private int _fightId = -1;
    private String _fightName = FightNames.UNKNOWN;
    private final FightNames _names = new FightNames();
    private LocalPlayer _player;
    private Dimension _dimension;

    Mode mode() {
        return _commit.mode();
    }

    CombatCommit commit() {
        return _commit;
    }

    // "zombie", for the card's red strip. "it" until a fight has named its target
    String fightName() {
        return _fightName;
    }

    CombatRules.Stance stance() {
        return _stance;
    }

    boolean shotAt() {
        return _shotAt;
    }

    int meleeNear() {
        return _meleeNear;
    }

    int meleeAround() {
        return _meleeAround;
    }

    boolean swingsAt(int id) {
        return _swingAt.contains(id);
    }

    // the aura is all feet and no hands while running. the shield is for a fight that is boxed in or has a pile on us: one
    // zombie still does not get it, two hitting us do
    boolean auraKiting() {
        return _commit.mode() == Mode.RUN;
    }

    // in a committed fight the flee line was already the machine's call (a cornered one never bails), so the aura keeps its
    // hands and its shield under it
    boolean auraFighting() {
        return _commit.mode() == Mode.FIGHT;
    }

    boolean auraShield() {
        return _commit.mode() == Mode.FIGHT && (_commit.cornered() || _pile);
    }

    float holdPriority() {
        return switch (_commit.mode()) {
            case FIGHT -> FIGHT_PRIORITY;
            case RUN -> RUN_PRIORITY;
            default -> 0;
        };
    }

    void reset() {
        _commit.reset();
        _anchor.reset();
        _foeMobs = List.of();
        _swingAt.clear();
        _stance = CombatRules.Stance.CALM;
        _meleeNear = 0;
        _meleeAround = 0;
        _shotAt = false;
        _pile = false;
        _loggedStance = CombatRules.Stance.CALM;
        _stanceLogAt = Long.MIN_VALUE / 2;
        _lastHealth = -1;
        _lastSource = null;
        _run = null;
        _fight = null;
        _fightId = -1;
        _fightName = FightNames.UNKNOWN;
        _names.clear();
    }

    // what to hold the wheel with right now, null for nothing (the chain lets go of whatever it has)
    Task wheelTask(AltoClef mod) {
        switch (_commit.mode()) {
            case FIGHT: {
                Entity target = mod.getWorld().getEntity(_commit.targetId());
                if (target == null) return null;
                // the same task object for the whole fight, a new one each tick would restart its stuck checks
                if (_fight == null || _fightId != target.getId() || _fight.stopped()) {
                    _fight = new KillEntityTask(target);
                    // no sword or axe is still a fight, and a pickaxe beats a fist
                    _fight.useToolsWhenUnarmed();
                    _fightId = target.getId();
                }
                return _fight;
            }
            case RUN: {
                if (_run == null || _run.stopped()) {
                    _run = new CommittedRunTask(_commit.originX(), _commit.originZ(), this::crowd);
                }
                return _run;
            }
            default:
                return null;
        }
    }

    // the goal asks this from the main thread once a tick
    private List<Entity> crowd() {
        return new ArrayList<>(_foeMobs);
    }

    // one game tick. creeperClose: a lit creeper close enough that we do not sit down to a meal next to it (we do not
    // run from it either, the task walks on). enabled: false is the off switch (altoCommitCombat), the foes are still
    // worked out for the stance and the crit counts but nothing is ever committed to
    void tick(AltoClef mod, long now, boolean creeperClose, boolean enabled) {
        LocalPlayer player = mod.getPlayer();
        Dimension dimension = WorldHelper.getCurrentDimension();
        // a new player is a respawn or a new world, and a new dimension is a new world too: whatever we were committed to
        // (a run's origin, a target id) died with the old one
        if (player != _player || dimension != _dimension) {
            reset();
            _player = player;
            _dimension = dimension;
        }
        EntityTracker tracker = mod.getEntityTracker();
        Provocations book = tracker.getProvocations();
        MobReachability reach = tracker.getMobReachability();

        boolean anchored = _anchor.update(now, player.getX(), player.getY(), player.getZ());
        List<Mob> mobs = new ArrayList<>();
        List<Foe> foes = new ArrayList<>();
        try {
            for (Entity entity : tracker.getHostiles()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
                double distance = mob.distanceTo(player);
                if (distance > FOE_RANGE) continue;
                Foe foe = FoeRules.accept(dimension, candidate(mod, dimension, mob, distance, book, now, player));
                if (foe == null) continue;
                mobs.add(mob);
                foes.add(foe);
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes, one tick of stale combat state is fine
        }
        sortNearestFirst(mobs, foes);
        _foeMobs = mobs;

        Foe target = null;
        if (_commit.mode() == Mode.FIGHT) {
            Entity entity = mod.getWorld().getEntity(_commit.targetId());
            if (entity instanceof Mob mob && mob.isAlive()) {
                target = foe(dimension, mob, mob.distanceTo(player), book, now, player);
            }
        }

        // the name is written down while the target is alive, a dead mob is already gone from the world by the time we say so.
        // by id: "the last name we saw" said zombie for a skeleton whose fight ended before anyone looked at it
        if (target != null && _commit.mode() == Mode.FIGHT) _names.note(_commit.targetId(), nameOf(mod, _commit.targetId()));
        String fighting = _names.of(_commit.targetId());
        if (enabled) {
            // how far the run got, asked before the step: an end chained into a new run has moved the origin by the time we say so
            int blocks = (int) Math.round(Math.hypot(player.getX() - _commit.originX(), player.getZ() - _commit.originZ()));
            Event event = _commit.step(new CombatCommit.Tick(now, player.getHealth(), player.getX(), player.getZ(), foes, target,
                    way(mod, mobs, foes), armed(mod, foes), tryingToMove(mod, player), anchored));
            // a fight that just started has its target in the world right now, which is the only chance to learn its name
            if (_commit.mode() == Mode.FIGHT) {
                _names.note(_commit.targetId(), nameOf(mod, _commit.targetId()));
                _fightName = _names.of(_commit.targetId());
            }
            if (event != Event.NONE) {
                // the old one ended and the next one started on the same step, both get their line, in that order
                if (_commit.endedFirst() != Event.NONE) log(mod, dimension, _commit.endedFirst(), fighting, mobs, blocks);
                log(mod, dimension, event, fighting, mobs, blocks);
                // a new run is a new origin, a new fight is a new target, whatever the old ones were. an extended run is
                // the same run from the same origin, so it keeps its task (a new one would cancel the path again)
                if (event != Event.RUN_EXTENDED) _run = null;
            }
        } else if (_commit.mode() != Mode.NONE) {
            // switched off in the middle of one: let go of it, no line (it is not a transition the machine made)
            _commit.reset();
        }
        if (_commit.mode() != Mode.RUN) _run = null;
        if (_commit.mode() != Mode.FIGHT) _fight = null;

        // running: a zombie that keeps pace with us is the one we are running from, not one that is stuck
        reach.setFleeing(_commit.mode() == Mode.RUN);

        _swingAt.clear();
        int meleeNear = 0;
        int meleeAround = 0;
        double nearest = Double.POSITIVE_INFINITY;
        boolean shotAt = false;
        double nearestFast = Double.POSITIVE_INFINITY;
        for (int i = 0; i < foes.size(); i++) {
            Foe foe = foes.get(i);
            nearest = Math.min(nearest, foe.distance());
            // (the nearest fast one gets to say when a bite may start, see CombatRules.RUN_BITE_START_FAST. shooters keep
            // their distance, so they are not chasers)
            if (foe.melee() && foe.distance() < nearestFast
                    && mobs.get(i).getAttributeValue(Attributes.MOVEMENT_SPEED) >= CombatRules.FAST_MOB_SPEED) {
                nearestFast = foe.distance();
            }
            // a shooter close enough to hit us with a line to us. the los ray is only cast for the ones that are in range
            if (!shotAt && foe.ranged() && !foe.creeper() && foe.distance() <= CombatRules.SHOOTER_RANGE
                    && (foe.sight() || mobs.get(i).hasLineOfSight(player))) {
                shotAt = true;
            }
            if (foe.hitUs() && foe.distance() <= CombatCommit.CONTACT) _swingAt.add(foe.id());
            if (!foe.melee()) continue;
            if (foe.distance() <= CombatCommit.CONTACT) meleeNear++;
            if (foe.distance() <= CombatRules.SWARM_RANGE) meleeAround++;
        }
        if (_commit.mode() == Mode.FIGHT) _swingAt.add(_commit.targetId());
        // (a cornered fight has the shield anyway, and the pile starts over from the raise rule when the next pile comes)
        _pile = _commit.mode() == Mode.FIGHT && !_commit.cornered() && CombatRules.pileShield(_pile, _commit.targetId(), foes);
        _meleeNear = meleeNear;
        _meleeAround = meleeAround;
        _shotAt = shotAt;
        CombatCommit.Bite bite = new CombatCommit.Bite(shotAt, nearestFast, mod.getFoodChain().isTryingToEat());
        _stance = stance(mod, nearest, creeperClose, bite);
        logStance(mod, dimension, now, nearest, creeperClose, bite);
        logHurt(mod, dimension);
    }

    // a stance change is a line, but a follower hovering right on the bite line would flip it every tick, so a line is at most
    // one per STANCE_LOG_GAP ticks and carries from the last stance we said to the one we are in now. the reason string is only
    // built when the line goes out
    private void logStance(AltoClef mod, Dimension dimension, long now, double nearest, boolean creeperClose, CombatCommit.Bite bite) {
        if (_stance == _loggedStance || (now >= _stanceLogAt && now - _stanceLogAt < STANCE_LOG_GAP)) return;
        LocalPlayer player = mod.getPlayer();
        String why = CombatCommit.stanceWhy(_commit.mode(), player.getHealth(), nearest, creeperClose, bite);
        Debug.logInternal(CombatLog.stance(dimension, _loggedStance, _stance, why, Math.round(player.getHealth())));
        _loggedStance = _stance;
        _stanceLogAt = now;
    }

    // hp went down: one line per new damage source object (the client makes a fresh one per hit), naming who if it knows
    private void logHurt(AltoClef mod, Dimension dimension) {
        LocalPlayer player = mod.getPlayer();
        float hp = player.getHealth();
        float before = _lastHealth;
        _lastHealth = hp;
        if (before < 0 || hp >= before) return;
        DamageSource source = player.getLastDamageSource();
        if (source != null && source == _lastSource) return;
        _lastSource = source;
        String who = null;
        String how = null;
        if (source != null) {
            how = source.getMsgId();
            Entity attacker = source.getEntity();
            if (attacker != null) {
                who = attacker instanceof Mob mob ? MobReachability.shortName(mob)
                        : BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
            }
        }
        Debug.logInternal(CombatLog.hurt(dimension, before - hp, who, how));
    }

    private final CombatCommit.Drive _drive = new CombatCommit.Drive();

    // the run is really trying to get somewhere: a path executing, or the search came back with none, and we are not using an
    // item (a run bite crawls by design), in the air or in water. the no room rule only counts these ticks
    private boolean tryingToMove(AltoClef mod, LocalPlayer player) {
        var pathing = mod.getClientBaritone().getPathingBehavior();
        return _drive.trying(_commit.mode() == Mode.RUN, pathing.isPathing(), pathing.getInProgress().isPresent(), pathing.calcFailures(),
                player.isUsingItem(), player.onGround(), player.isInWater());
    }

    // a sword, an axe, or a tool that hits harder than a fist (the fight task swings those, see useToolsWhenUnarmed). only the
    // machine's idle and run ask, so the bag is only walked when something is on us or we are already running
    private boolean armed(AltoClef mod, List<Foe> foes) {
        boolean asked = _commit.mode() == Mode.RUN;
        for (Foe foe : foes) {
            // (a sniper that is pinning us down is a fight we would start, so it wants to know too)
            if (foe.distance() <= CombatCommit.CONTACT || foe.pinning(false)) asked = true;
        }
        if (!asked) return true;
        List<WeaponPick.Candidate> candidates = new ArrayList<>();
        candidates.add(WeaponPick.Candidate.of(StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot())));
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            candidates.add(WeaponPick.Candidate.of(stack));
        }
        return WeaponPick.best(candidates) != null || WeaponPick.bestTool(candidates) != null;
    }

    // how many path nodes ahead count as "in the way". three is the next couple of steps, further out the mob has time to move
    private static final int WAY_NODES = 3;

    // the path's side of the tick: which of the mobs in contact sit in the next few nodes (feet or head) or in the block the
    // current movement would place under its dest (a bridge or a step up into a zombie). baritone does wait for a mob to
    // leave the place cell, but only 20 ticks, then it replans into the same cell
    private static CombatCommit.Way way(AltoClef mod, List<Mob> mobs, List<Foe> foes) {
        IPathingBehavior pathing = mod.getClientBaritone().getPathingBehavior();
        boolean moving = pathing.isPathing() || pathing.getInProgress().isPresent();
        double y = mod.getPlayer().getY();
        IPathExecutor current = pathing.getCurrent();
        if (current == null) return new CombatCommit.Way(Set.of(), moving, y);
        List<AABB> cells = new ArrayList<>();
        try {
            IPath path = current.getPath();
            List<BetterBlockPos> positions = path.positions();
            int from = Math.max(0, current.getPosition());
            // (from + 1: the node we are standing on is ours, a zombie hugging us is not "in the path")
            for (int i = from + 1; i < positions.size() && i <= from + WAY_NODES; i++) {
                BetterBlockPos p = positions.get(i);
                cells.add(new AABB(p.x, p.y, p.z, p.x + 1, p.y + 2, p.z + 1));
            }
            List<IMovement> movements = path.movements();
            if (from < movements.size()) {
                BetterBlockPos dest = movements.get(from).getDest();
                cells.add(new AABB(dest.x, dest.y - 1, dest.z, dest.x + 1, dest.y, dest.z + 1));
            }
        } catch (IndexOutOfBoundsException e) {
            // the executor moved on between two reads, next tick has it
        }
        Set<Integer> inWay = new HashSet<>();
        for (int i = 0; i < foes.size(); i++) {
            if (foes.get(i).distance() > CombatCommit.CONTACT) continue;
            // a hair bigger, a zombie leaning into the cell is in it
            AABB box = mobs.get(i).getBoundingBox().inflate(0.05);
            for (AABB cell : cells) {
                if (cell.intersects(box)) {
                    inWay.add(foes.get(i).id());
                    break;
                }
            }
        }
        return new CombatCommit.Way(inWay, moving, y);
    }

    private CombatRules.Stance stance(AltoClef mod, double nearest, boolean creeperClose, CombatCommit.Bite bite) {
        // gapples are never picked as food (FoodSelector keeps them for exactly this), so check the bag ourselves
        boolean gapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        return CombatCommit.stance(_commit.mode(), mod.getPlayer().getHealth(), nearest, creeperClose, gapple, bite);
    }

    // the fight target looked up on its own: it can have stopped being angry (or being a candidate at all) and still be the
    // one we are fighting
    private static Foe foe(Dimension dimension, Mob mob, double distance, Provocations book, long now, LocalPlayer player) {
        Kind kind = FoeRules.kindOf(dimension, typeOf(mob));
        long sinceHit = book.sinceHit(mob.getId(), now);
        return new Foe(mob.getId(), distance, FoeRules.shoots(typeOf(mob), MobReachability.isRanged(mob), kind), mob instanceof Creeper, sinceHit, kind,
                reachGap(player, mob), sees(dimension, mob, sinceHit, player), book.sinceHitBefore(mob.getId(), now));
    }

    // a line to us, asked only of a shooter that hit us lately: the ray is the costly bit and nothing else wants the answer.
    // "shooter" is the same word the Foe gets (a blaze is one, though it is not a ranged attack mob by class)
    private static boolean sees(Dimension dimension, Mob mob, long sinceHit, LocalPlayer player) {
        if (sinceHit > CombatCommit.HIT_MEMORY || mob instanceof Creeper) return false;
        Kind kind = FoeRules.kindOf(dimension, typeOf(mob));
        return FoeRules.shoots(typeOf(mob), MobReachability.isRanged(mob), kind) && mob.hasLineOfSight(player);
    }

    // eye to its box, the number the reach rules use
    private static double reachGap(LocalPlayer player, Mob mob) {
        Vec3 eye = player.getEyePosition();
        AABB box = mob.getBoundingBox();
        return ReachRules.gap(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static void sortNearestFirst(List<Mob> mobs, List<Foe> foes) {
        // tiny lists, insertion sort keeps the two in step without a pairing record
        for (int i = 1; i < foes.size(); i++) {
            for (int j = i; j > 0 && foes.get(j).distance() < foes.get(j - 1).distance(); j--) {
                java.util.Collections.swap(foes, j, j - 1);
                java.util.Collections.swap(mobs, j, j - 1);
            }
        }
    }

    // one line per transition, never per tick (the text is CombatLog's)
    private void log(AltoClef mod, Dimension dimension, Event event, String fighting, List<Mob> mobs, int blocks) {
        LocalPlayer player = mod.getPlayer();
        String line = CombatLog.line(dimension, event, _commit.why(), _commit.cornered(), _commit.noRoom(), nameOf(mod, _commit.targetId()), fighting,
                describe(mobs), Math.round(player.getHealth()), blocks);
        if (line != null) Debug.logInternal(line);
    }

    private static String nameOf(AltoClef mod, int id) {
        return mod.getWorld().getEntity(id) instanceof Mob mob ? MobReachability.shortName(mob) : "it";
    }

    private static String typeOf(Mob mob) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
    }

    // the costly questions are asked lazily, FoeRules only asks them when the cheap ones left the mob in the running
    private static FoeRules.Candidate candidate(AltoClef mod, Dimension dimension, Mob mob, double distance, Provocations book, long now,
                                                LocalPlayer player) {
        long sinceHit = book.sinceHit(mob.getId(), now);
        return new FoeRules.Candidate(typeOf(mob), mob.getId(), distance,
                () -> mod.getBehaviour().shouldExcludeFromMobDefense(mob),
                () -> EntityHelper.isAngryAtPlayer(mod, mob),
                () -> EntityHelper.canMobHarmPlayer(mod, mob),
                sinceHit, mob instanceof Slime slime ? slime.getSize() : 0, MobReachability.isRanged(mob), mob instanceof Creeper,
                reachGap(player, mob), sees(dimension, mob, sinceHit, player), book.sinceHitBefore(mob.getId(), now));
    }

    // how many of each kind, in the order they showed up
    private static String describe(List<Mob> mobs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Mob mob : mobs) counts.merge(MobReachability.shortName(mob), 1, Integer::sum);
        if (counts.isEmpty()) return "nothing in sight";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (out.length() > 0) out.append(", ");
            out.append(e.getValue()).append(' ').append(e.getKey()).append(e.getValue() == 1 ? "" : "s");
        }
        return out.toString();
    }
}
