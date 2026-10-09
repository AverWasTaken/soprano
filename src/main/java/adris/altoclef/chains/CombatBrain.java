package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.CommittedRunTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatLog;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FoeRules;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.Provocations;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

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
    private static final double FOE_RANGE = 16;

    private final CombatCommit _commit = new CombatCommit();
    // this tick's angry hostiles as entities, nearest first (the run goal steers around them)
    private List<Mob> _foeMobs = List.of();
    // who the aura may swing at: the fight target and anything in contact that has hit us
    private final Set<Integer> _swingAt = new HashSet<>();
    private CombatRules.Stance _stance = CombatRules.Stance.CALM;
    private int _meleeNear;
    private int _meleeAround;
    private CommittedRunTask _run;
    private KillEntityTask _fight;
    private int _fightId = -1;
    private String _fightName = "it";
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

    int meleeNear() {
        return _meleeNear;
    }

    int meleeAround() {
        return _meleeAround;
    }

    boolean swingsAt(int id) {
        return _swingAt.contains(id);
    }

    // the aura is all feet and no hands while running, and only uses the shield when boxed in
    boolean auraKiting() {
        return _commit.mode() == Mode.RUN;
    }

    boolean auraShield() {
        return _commit.mode() == Mode.FIGHT && _commit.cornered();
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
        _foeMobs = List.of();
        _swingAt.clear();
        _stance = CombatRules.Stance.CALM;
        _meleeNear = 0;
        _meleeAround = 0;
        _run = null;
        _fight = null;
        _fightId = -1;
        _fightName = "it";
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

        List<Mob> mobs = new ArrayList<>();
        List<Foe> foes = new ArrayList<>();
        try {
            for (Entity entity : tracker.getHostiles()) {
                if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
                double distance = mob.distanceTo(player);
                if (distance > FOE_RANGE) continue;
                Foe foe = FoeRules.accept(dimension, candidate(mod, mob, distance, book.sinceHit(mob.getId(), now)));
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
                target = foe(dimension, mob, mob.distanceTo(player), book.sinceHit(mob.getId(), now));
            }
        }

        // the name is remembered while the target is alive, a dead mob is already gone from the world by the time we say so
        if (target != null && _commit.mode() == Mode.FIGHT) _fightName = nameOf(mod, _commit.targetId());
        String fighting = _fightName;
        if (enabled) {
            // how far the run got, asked before the step: an end chained into a new run has moved the origin by the time we say so
            int blocks = (int) Math.round(Math.hypot(player.getX() - _commit.originX(), player.getZ() - _commit.originZ()));
            Event event = _commit.step(new CombatCommit.Tick(now, player.getHealth(), player.getX(), player.getZ(), foes, target,
                    way(mod, mobs, foes)));
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
        for (Foe foe : foes) {
            nearest = Math.min(nearest, foe.distance());
            if (foe.hitUs() && foe.distance() <= CombatCommit.CONTACT) _swingAt.add(foe.id());
            if (!foe.melee()) continue;
            if (foe.distance() <= CombatCommit.CONTACT) meleeNear++;
            if (foe.distance() <= CombatRules.SWARM_RANGE) meleeAround++;
        }
        if (_commit.mode() == Mode.FIGHT) _swingAt.add(_commit.targetId());
        _meleeNear = meleeNear;
        _meleeAround = meleeAround;
        _stance = stance(mod, nearest, creeperClose);
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

    private CombatRules.Stance stance(AltoClef mod, double nearest, boolean creeperClose) {
        // gapples are never picked as food (FoodSelector keeps them for exactly this), so check the bag ourselves
        boolean gapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        return CombatCommit.stance(_commit.mode(), mod.getPlayer().getHealth(), nearest, creeperClose, gapple);
    }

    // the fight target looked up on its own: it can have stopped being angry (or being a candidate at all) and still be the
    // one we are fighting
    private static Foe foe(Dimension dimension, Mob mob, double distance, long sinceHit) {
        Kind kind = FoeRules.kindOf(dimension, typeOf(mob));
        return new Foe(mob.getId(), distance, FoeRules.shoots(typeOf(mob), MobReachability.isRanged(mob), kind), mob instanceof Creeper, sinceHit, kind);
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
        String line = CombatLog.line(dimension, event, _commit.why(), _commit.cornered(), nameOf(mod, _commit.targetId()), fighting,
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
    private static FoeRules.Candidate candidate(AltoClef mod, Mob mob, double distance, long sinceHit) {
        return new FoeRules.Candidate(typeOf(mob), mob.getId(), distance,
                () -> mod.getBehaviour().shouldExcludeFromMobDefense(mob),
                () -> EntityHelper.isAngryAtPlayer(mod, mob),
                () -> EntityHelper.canMobHarmPlayer(mod, mob),
                sinceHit, mob instanceof Slime slime ? slime.getSize() : 0, MobReachability.isRanged(mob), mob instanceof Creeper);
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
