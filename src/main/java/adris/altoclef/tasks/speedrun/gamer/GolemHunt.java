package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.speedrun.gamer.GolemRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.GolemRules.Verdict;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.GolemFightTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Set;

// 3 to 5 iron ingots stand around in every village, they just hit hard. while iron is the thing we are short on and a
// calm golem is close, GolemFightTask kills one from a pillar. in memory only: a relog forgets which golems we tried,
// which is fine, the attempts cap is per instance and a relog is a fresh run of the phase anyway
public final class GolemHunt {
    private static final int CHECK_EVERY_TICKS = 10;

    private final Set<Integer> tried = new HashSet<>();
    private int attempts;
    private int ticks;
    private GolemFightTask task;

    public void onExit() {
        task = null;
    }

    // a fight that already started has to be finished by us (PrepSupport asks first so a chest does not pull us off the
    // pillar mid fight)
    public boolean active() {
        return task != null;
    }

    public String hud() {
        return task == null ? null : task.hud();
    }

    // the fight task while there is one running, a new one when the trigger says go, otherwise null
    public Task tick(AltoClef mod, GamerContext ctx, KitNeed current) {
        if (task != null) {
            if (!task.isFinished(mod)) {
                return task;
            }
            ctx.log(task.killed() ? "iron golem down" : "done with the golem");
            task = null;
        }
        if (++ticks % CHECK_EVERY_TICKS != 0 || ctx.facts().dimension() != Dimension.OVERWORLD) {
            return null;
        }
        OverworldConfig cfg = ctx.cfg().overworld;
        LocalPlayer player = mod.getPlayer();
        IronGolem golem = mod.getEntityTracker().getClosestEntity(e -> e instanceof IronGolem g && g.isAlive()
                && !tried.contains(e.getId()) && e.closerThan(player, cfg.golemHuntRadius), IronGolem.class)
                .map(e -> (IronGolem) e).orElse(null);
        // iron need only: a hunt for the 4th pickaxe nobody asked for is not what a golem is for
        boolean ironNeeded = current != null && "iron_ingot".equals(current.catalogueName()) && current.count() > 0;
        Inputs in = new Inputs(ironNeeded, true, golem != null, golem != null && golem.isAggressive(),
                false, attempts, cfg.golemMaxAttempts, hasWeapon(ctx.facts()),
                ctx.facts().buildBlocks(), cfg.golemMinBlocks, player.getHealth(), (float) cfg.golemMinHealth,
                // the monsters check is the expensive one, so it only runs when everything else already said yes
                golem != null && ironNeeded && GolemFightTask.monstersNear(mod, cfg.golemHostileRadius, golem.getId()),
                player.onGround(), player.isInWater() || player.isInLava());
        Verdict verdict = GolemRules.shouldHunt(in);
        if (!verdict.go()) {
            return null;
        }
        tried.add(golem.getId());
        attempts++;
        task = new GolemFightTask(golem.getId(), ctx);
        ctx.progress("hunting an iron golem");
        return task;
    }

    private static boolean hasWeapon(GamerFacts facts) {
        for (Item sword : GolemFightTask.SWORDS) {
            if (facts.has(sword)) {
                return true;
            }
        }
        for (Item axe : GolemFightTask.AXES) {
            if (facts.has(axe)) {
                return true;
            }
        }
        return false;
    }
}
