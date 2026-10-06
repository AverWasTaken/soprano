package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GolemRules;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

// one iron golem, killed from a pillar. golems cannot reach a player whose feet are above their head (GolemRules has
// the numbers), so: walk up while it is calm, stack blocks next to it until we are high enough, then poke it with full
// cooldown swings. it does not get knocked back so it stays at the foot of the pillar and keeps coming back into reach.
//
// the one thing this task does not do is leave the pillar while the golem is angry. coming down into a 7 to 21 damage
// hit is how the whole plan loses, so the exit is "dead, gone, or has had time to calm down", never "gave up"
public final class GolemFightTask extends Task {
    public static final List<Item> SWORDS = List.of(Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD);
    public static final List<Item> AXES = List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE);
    // we stop walking and start stacking this close (center to center), it fits the reach numbers and leaves room for
    // the block, a golem overlapping our column cell makes the game refuse the placement
    private static final double PILLAR_FROM = 2.5;
    private static final double APPROACH_TO = 2.0;
    private static final int MAX_REPILLARS = 2;
    // a dead golem's drops take a moment to show up
    private static final int DROPS_WAIT_TICKS = 40;
    private static final int LOOT_TICKS = 20 * 20;
    private static final int PROGRESS_EVERY_TICKS = 100;
    private static final int HOSTILE_CHECK_TICKS = 10;
    private static final double FAR_AWAY = 24;

    private enum State {
        APPROACH, PILLAR, FIGHT, LOOT, DONE
    }

    private final int golemId;
    private final GamerContext ctx;
    private final OverworldConfig cfg;

    private State state = State.APPROACH;
    private long stateSince = Long.MIN_VALUE;
    private long startTick = Long.MIN_VALUE;
    private long fightSince = -1;
    private long lastHitTick = Long.MIN_VALUE / 2;
    private long lastProgressTick;
    private long lootSince;
    private int hits;
    private int outOfReachTicks;
    private int repillars;
    private int lootTarget;
    private boolean golemSeenDead;
    private boolean killed;
    private boolean hostilesNear;
    private boolean ownsBehaviour;
    private AltoClef mod;
    private Task subtask;
    private String hud = "Walking up to an iron golem";

    public GolemFightTask(int golemId, GamerContext ctx) {
        this.golemId = golemId;
        this.ctx = ctx;
        this.cfg = ctx.cfg().overworld;
    }

    public int golemId() {
        return golemId;
    }

    public boolean killed() {
        return killed;
    }

    public String hud() {
        return hud;
    }

    // monsters close enough to ruin it (a creeper at the foot of the pillar does not care how tall we are)
    public static boolean monstersNear(AltoClef mod, int radius, int ignoreId) {
        LocalPlayer player = mod.getPlayer();
        AABB box = player.getBoundingBox().inflate(radius);
        // the angry golem we are fighting is in getHostiles too, that one is not "other"
        return !mod.getWorld().getEntitiesOfClass(Monster.class, box, Entity::isAlive).isEmpty()
                || mod.getEntityTracker().getHostiles().stream().anyMatch(e -> e.getId() != ignoreId);
    }

    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;
        // live predicate: the golem is only ours to fight while we are actually above its head. fall off or get
        // squashed down and mob defense gets its say again
        mod.getBehaviour().push();
        mod.getBehaviour().addForceFieldExclusion(this::golemWeFightFromAbove);
        mod.getBehaviour().addMobDefenseExclusion(this::golemWeFightFromAbove);
        ownsBehaviour = true;
        if (startTick == Long.MIN_VALUE) {
            startTick = mod.getWorld().getGameTime();
            stateSince = startTick;
            lastProgressTick = startTick;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (ownsBehaviour) {
            mod.getBehaviour().pop();
            ownsBehaviour = false;
        }
        subtask = null;
    }

    private boolean golemWeFightFromAbove(Entity entity) {
        if (entity.getId() != golemId || state != State.FIGHT) {
            return false;
        }
        LocalPlayer player = mod == null ? null : mod.getPlayer();
        return player != null && !GolemRules.golemCanHitUs(player.getY(), entity.getY());
    }

    private IronGolem golem(AltoClef mod) {
        Entity e = mod.getWorld().getEntity(golemId);
        return e instanceof IronGolem g ? g : null;
    }

    private void enter(State next, long now, String why) {
        state = next;
        stateSince = now;
        subtask = null;
        outOfReachTicks = 0;
        adris.altoclef.Debug.logInternal("golem: " + why);
    }

    private void progress(long now, String what) {
        lastProgressTick = now;
        ctx.progress(what);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return state == State.DONE;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        IronGolem g = golem(mod);
        boolean gone = g == null || !g.isAlive();
        if (g != null && !g.isAlive()) {
            golemSeenDead = true;
        }
        if (now % HOSTILE_CHECK_TICKS == 0) {
            hostilesNear = monstersNear(mod, cfg.golemHostileRadius, golemId);
        }
        if (gone && state != State.LOOT && state != State.DONE) {
            if (hits == 0) {
                enter(State.DONE, now, "golem gone before we hit it");
                return null;
            }
            killed = golemSeenDead;
            lootSince = now;
            lootTarget = ctx.facts().count(Items.IRON_INGOT) + 5;
            hud = "Picking up the golem's iron";
            enter(State.LOOT, now, killed ? "golem dead after " + hits + " hits" : "golem out of sight after " + hits + " hits");
        }
        return switch (state) {
            case APPROACH -> approach(mod, player, g, now);
            case PILLAR -> pillar(mod, player, g, now);
            case FIGHT -> fight(mod, player, g, now);
            case LOOT -> loot(mod, now);
            case DONE -> null;
        };
    }

    private Task approach(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        hud = "Walking up to an iron golem";
        if (g.isAggressive() || hostilesNear) {
            // it noticed us (or something else did) while we are still on the ground. not our plan any more
            enter(State.DONE, now, g.isAggressive() ? "golem is angry on the ground, backing out" : "monsters showed up");
            return null;
        }
        if ((now - stateSince) / 20.0 > cfg.golemApproachSeconds) {
            enter(State.DONE, now, "could not get to the golem in time");
            return null;
        }
        double dx = g.getX() - player.getX();
        double dz = g.getZ() - player.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= PILLAR_FROM && player.onGround()) {
            enter(State.PILLAR, now, "close enough, stacking");
            return null;
        }
        if (subtask == null) {
            subtask = new GetToEntityTask(g, APPROACH_TO);
        }
        return subtask;
    }

    private Task pillar(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        hud = "Pillaring up next to an iron golem";
        double safe = GolemRules.safeFeetY(g.getY(), cfg.golemSafeMargin);
        if (player.onGround() && player.getY() >= safe - 1.0e-6) {
            if (fightSince < 0) {
                fightSince = now;
            }
            enter(State.FIGHT, now, "on top at y=" + player.getY());
            progress(now, "on the pillar next to an iron golem");
            return null;
        }
        if ((now - stateSince) / 20.0 > cfg.golemPillarSeconds || (hostilesNear && hits == 0)) {
            enter(State.DONE, now, "pillar went nowhere");
            return null;
        }
        if (subtask == null) {
            int need = GolemRules.blocksToRaise(player.getY(), g.getY(), cfg.golemSafeMargin, cfg.golemMaxPillar);
            if (need < 0 || ctx.facts().buildBlocks() < need) {
                enter(State.DONE, now, "not worth " + need + " blocks of pillar");
                return null;
            }
            BlockPos here = player.blockPosition();
            subtask = new PillarHereTask(here.above(Math.max(need, 1)));
        }
        return subtask;
    }

    private Task fight(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        hud = "Fighting an iron golem from a pillar";
        if (GolemRules.golemCanHitUs(player.getY(), g.getY()) && player.onGround()) {
            // it walked onto higher ground (or we are standing lower than we thought). one more block, a couple of times
            if (repillars < MAX_REPILLARS) {
                repillars++;
                enter(State.PILLAR, now, "golem got higher than our feet, stacking more");
            } else {
                enter(State.DONE, now, "cannot stay above it");
            }
            return null;
        }
        long sinceStart = now - fightSince;
        boolean wantFight = sinceStart / 20.0 <= cfg.golemFightSeconds && player.getHealth() >= cfg.golemAbortHealth && !hostilesNear;
        boolean reachable = reachable(mod, player, g);
        if (wantFight && reachable) {
            outOfReachTicks = 0;
            if (hit(mod, player, g, now)) {
                return null;
            }
        } else {
            outOfReachTicks++;
        }
        if (now - lastProgressTick > PROGRESS_EVERY_TICKS) {
            // waiting for it to come back or to calm down is the plan, not a stall
            progress(now, "waiting on the pillar for the golem");
        }
        boolean givingUp = !wantFight || outOfReachTicks / 20.0 > cfg.golemOutOfReachSeconds;
        boolean hardCap = sinceStart / 20.0 > cfg.golemFightSeconds + cfg.golemCalmSeconds + 15;
        double dist = Math.sqrt(g.distanceToSqr(player));
        if (hardCap || (givingUp && GolemRules.safeToComeDown(true, g.isAggressive(), dist, now - lastHitTick,
                (long) (cfg.golemCalmSeconds * 20), FAR_AWAY))) {
            enter(State.DONE, now, "leaving the pillar after " + hits + " hits");
        }
        return null;
    }

    private boolean reachable(AltoClef mod, LocalPlayer player, IronGolem g) {
        AABB box = g.getBoundingBox();
        double reach = GolemRules.INTERACT_RANGE;
        var eye = player.getEyePosition();
        return GolemRules.inReach(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, reach)
                && LookHelper.cleanLineOfSight(player, g.getEyePosition(), 8);
    }

    // true when this tick was spent on the weapon swap or the swing
    private boolean hit(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        if (equipWeapon(mod)) {
            return true;
        }
        LookHelper.lookAt(mod, g.getEyePosition());
        // full cooldown only, a half charged swing is half the damage for the same 0.5 s of waiting
        if (player.getAttackStrengthScale(0) < 1) {
            return false;
        }
        mod.getController().attack(player, g);
        player.swing(InteractionHand.MAIN_HAND);
        lastHitTick = now;
        hits++;
        progress(now, "hit an iron golem (" + hits + ")");
        return true;
    }

    // sword if we have one, an axe if that is all there is. same swap rule as the kill tasks: swap now, swing next tick
    private boolean equipWeapon(AltoClef mod) {
        if (AbstractKillEntityTask.bestWeapon(mod) != null) {
            return AbstractKillEntityTask.equipWeapon(mod);
        }
        Item inHand = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        for (Item axe : AXES) {
            if (mod.getItemStorage().hasItem(axe)) {
                if (axe != inHand) {
                    mod.getSlotHandler().forceEquipItem(axe);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    // the drops land at the foot of the pillar. baritone paths down on its own to fetch them (3 block drops are fine
    // for it, taller pillars it digs out), so there is no separate "get down" step
    private Task loot(AltoClef mod, long now) {
        hud = "Picking up the golem's iron";
        boolean dropped = mod.getEntityTracker().itemDropped(Items.IRON_INGOT);
        long waited = now - lootSince;
        boolean enough = ctx.facts().count(Items.IRON_INGOT) >= lootTarget;
        if (enough || waited > LOOT_TICKS || (!dropped && waited > DROPS_WAIT_TICKS)) {
            enter(State.DONE, now, "iron collected, now " + ctx.facts().count(Items.IRON_INGOT) + " ingots");
            return null;
        }
        if (!dropped) {
            return null;
        }
        if (subtask == null) {
            subtask = new PickupDroppedItemTask(Items.IRON_INGOT, lootTarget);
        }
        progress(now, "picking up golem iron");
        return subtask;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GolemFightTask task && task.golemId == golemId;
    }

    @Override
    protected String toDebugString() {
        return "Iron golem fight, state " + state + ", " + hits + " hits";
    }

    @Override
    protected String toHudString() {
        return hud;
    }
}
