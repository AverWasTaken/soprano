package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.CommittedRunTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.MobReachability;
import adris.altoclef.util.helpers.Provocations;
import baritone.Baritone;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.Items;

// the world half of CombatCommit, for the overworld. MobDefenseChain calls tick() once per game tick (that is the only
// place the machine moves) and then asks what to hold the wheel with. nothing in here hands the wheel back: that is the
// chain's call, and it only does it when the machine says NONE
final class OverworldCombat {

    // the steady priorities of a commitment. above the user task (50) and the food chain (55), same numbers the old
    // kill (65) and flee (80) branches used, so nothing that was ordered against them moved
    static final float FIGHT_PRIORITY = 65;
    static final float RUN_PRIORITY = 80;
    // a little past the clear line, so the run goal's own buffer (CommittedRunTask.CROWD_CLEAR) sees what it is buffering
    // against. the machine only ever asks "within 16", the extra four blocks are for the goal
    private static final double FOE_RANGE = CombatCommit.RUN_CLEAR + 4;

    private final CombatCommit _commit = new CombatCommit();
    // this tick's angry hostiles, nearest first, and the entities they came from
    private List<Foe> _foes = List.of();
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

    Mode mode() {
        return _commit.mode();
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
        _foes = List.of();
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
                    // armed includes a pickaxe, so the fight has to be willing to swing one
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

    // one game tick. danger: something the old rules always ran from is close and we are hurt. fuseNear: a lit creeper close
    // by, the chain is stepping away from it so the fight's clocks sit still. creeperClose: the wider ring that keeps us
    // from sitting down to a meal next to one
    void tick(AltoClef mod, long now, boolean danger, boolean fuseNear, boolean creeperClose) {
        LocalPlayer player = mod.getPlayer();
        // a new player is a respawn or a new world: whatever we were committed to died with the old one
        if (player != _player) {
            reset();
            _player = player;
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
                // a task that is fighting this one itself (golem on its pillar) does not want a second opinion
                if (mod.getBehaviour().shouldExcludeFromMobDefense(mob) || !EntityHelper.isAngryAtPlayer(mod, mob)) continue;
                // a size 1 slime cannot hurt us, it is a bouncing pet
                if (mob instanceof Slime slime && slime.getSize() <= 1) continue;
                long sinceHit = book.sinceHit(mob.getId(), now);
                // something that hit us can hurt us, no questions. the rest has to be able to get at us (or shoot)
                if (sinceHit > CombatCommit.HIT_MEMORY && !EntityHelper.canMobHarmPlayer(mod, mob)) continue;
                mobs.add(mob);
                foes.add(foe(mob, distance, sinceHit));
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes, one tick of stale combat state is fine
        }
        sortNearestFirst(mobs, foes);
        _foeMobs = mobs;
        _foes = foes;

        Foe target = null;
        if (_commit.mode() == Mode.FIGHT) {
            Entity entity = mod.getWorld().getEntity(_commit.targetId());
            if (entity instanceof Mob mob && mob.isAlive()) {
                target = foe(mob, mob.distanceTo(player), book.sinceHit(mob.getId(), now));
            }
        }

        // the name is remembered while the target is alive, a dead mob is already gone from the world by the time we say so
        if (target != null && _commit.mode() == Mode.FIGHT) _fightName = nameOf(mod, _commit.targetId());
        String fighting = _fightName;
        int crowdSize = Math.max(1, Baritone.settings().altoSwarmThreshold.value);
        // (a scan of the bag every tick is for nobody when nothing is around and nothing is going on)
        boolean armed = foes.isEmpty() && _commit.mode() == Mode.NONE || AbstractKillEntityTask.canFight(mod);
        Event event = _commit.step(new CombatCommit.Tick(now, player.getHealth(), armed, player.getX(), player.getZ(), crowdSize,
                foes, target, danger, fuseNear));
        if (event != Event.NONE) {
            log(mod, event, fighting, mobs);
            // a new run is a new origin, a new fight is a new target, whatever the old ones were
            _run = null;
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
            if (foe.distance() <= CombatCommit.CROWD_RANGE) meleeAround++;
        }
        if (_commit.mode() == Mode.FIGHT) _swingAt.add(_commit.targetId());
        _meleeNear = meleeNear;
        _meleeAround = meleeAround;
        _stance = stance(mod, nearest, creeperClose);
    }

    private CombatRules.Stance stance(AltoClef mod, double nearest, boolean creeperClose) {
        // gapples are never picked as food (FoodSelector keeps them for exactly this), so check the bag ourselves
        boolean gapple = mod.getItemStorage().hasItem(Items.GOLDEN_APPLE) || mod.getItemStorage().hasItem(Items.ENCHANTED_GOLDEN_APPLE);
        return CombatCommit.stance(_commit.mode(), mod.getPlayer().getHealth(), nearest, creeperClose, gapple);
    }

    private static Foe foe(Mob mob, double distance, long sinceHit) {
        return new Foe(mob.getId(), distance, MobReachability.isRanged(mob), mob instanceof Creeper, sinceHit);
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

    // one line per transition, never per tick
    private void log(AltoClef mod, Event event, String fighting, List<Mob> mobs) {
        int hp = Math.round(mod.getPlayer().getHealth());
        String target = nameOf(mod, _commit.targetId());
        String crowd = describe(mobs);
        switch (event) {
            case FIGHT_START -> Debug.logInternal("combat: FIGHT " + target + " (" + (_commit.why() == CombatCommit.Why.HIT ? "hit us" : "in contact")
                    + ", armed, hp " + hp + ")");
            case FIGHT_NEXT -> Debug.logInternal(_commit.cornered() ? "combat: cornered, next " + target
                    : "combat: target dead, next " + target + " (" + (_commit.why() == CombatCommit.Why.HIT ? "hit us" : "in contact") + ", hp " + hp + ")");
            case RUN_START -> Debug.logInternal("combat: RUN from " + crowd + " (" + runWhy(mod, hp) + ")");
            case FIGHT_TO_RUN -> Debug.logInternal("combat: FIGHT -> RUN from " + crowd + " (" + runWhy(mod, hp) + ")");
            case RUN_TO_FIGHT -> Debug.logInternal("combat: cornered, fighting " + target);
            case FIGHT_DEAD -> Debug.logInternal("combat: fight over, " + fighting + " dead");
            case FIGHT_LOST -> Debug.logInternal("combat: fight over, lost track of " + fighting);
            case FIGHT_STALLED -> Debug.logInternal("combat: fight over, can't get to " + fighting + ", ignoring it until it hits us again");
            case RUN_CLEAR -> Debug.logInternal("combat: run over, " + Math.round(runBlocks(mod)) + " blocks, clear");
            case RUN_CAP -> Debug.logInternal("combat: run over, " + CombatCommit.RUN_CAP / 20 + " s cap, " + Math.round(runBlocks(mod)) + " blocks");
            default -> {
            }
        }
    }

    private static String nameOf(AltoClef mod, int id) {
        return mod.getWorld().getEntity(id) instanceof Mob mob ? MobReachability.shortName(mob) : "it";
    }

    private double runBlocks(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        return Math.hypot(player.getX() - _commit.originX(), player.getZ() - _commit.originZ());
    }

    private String runWhy(AltoClef mod, int hp) {
        return switch (_commit.why()) {
            case UNARMED -> "unarmed, hp " + hp;
            case CROWD -> "crowd of " + CombatCommit.crowd(_foes) + ", hp " + hp;
            case DANGER -> "something nasty close, hp " + hp;
            case UNREACHABLE -> "it hit us again and we can't get to it, hp " + hp;
            default -> "hp " + hp;
        };
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
