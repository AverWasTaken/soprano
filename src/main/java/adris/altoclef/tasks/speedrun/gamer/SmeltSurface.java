package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

// a speedrunner smelts where the next work is, not where the ore was. every job the filler has (sheep, food, logs) is on the
// surface, and a furnace dropped in the iron tunnel at y 38 meant climbing out, getting leashed, and climbing back. so with
// async smelting on and all the ore mined, the raw iron and the furnace ride up to the surface first and the smelt places
// it there. the catalogue's smelt task is not ours to split, so this runs in front of it: the order is mine everything ->
// surface -> load -> filler. the decisions are static and pure, the instance is the little state machine around them
public final class SmeltSurface {
    // deeper than this below the open sky and we are in a mine, not in a ditch
    public static final int GO_UP_DEPTH = 8;
    // keep climbing until this close, so a hair under the line does not flip it back and forth
    public static final int ARRIVED_DEPTH = 2;
    private static final double GIVE_UP_SECONDS = 90;

    private boolean climbing;
    // we already surfaced for this batch. coal or fuel underground must not send us up again and again
    private boolean settled;
    private boolean gaveUp;
    private long climbSince;
    private int bestDepth;
    private Task climb;

    public void reset() {
        climbing = false;
        settled = false;
        gaveUp = false;
        climb = null;
    }

    public boolean climbing() {
        return climbing;
    }

    // all the raw iron the iron need asks for is in the bag (held ingots and ingots already cooking count towards it)
    public static boolean oreDone(String needName, int needCount, int rawIron, int ingots, int pending) {
        return "iron_ingot".equals(needName) && rawIron > 0 && rawIron + ingots + pending >= needCount;
    }

    // blocks between us and the open air above, from the heightmap (the first free block over the top solid one)
    public static int depth(int surfaceY, int feetY) {
        return surfaceY - feetY;
    }

    public static boolean wantsUp(boolean alreadyClimbing, int depth) {
        return alreadyClimbing ? depth > ARRIVED_DEPTH : depth > GO_UP_DEPTH;
    }

    // the task to run in front of the smelt, null = carry on with the plan
    public Task tick(AltoClef mod, GamerContext ctx, KitNeed head) {
        GamerFacts f = ctx.facts();
        int raw = f.count(Items.RAW_IRON);
        if (raw == 0) {
            // the batch went in the furnace (or was never there), the next one starts fresh
            settled = false;
            gaveUp = false;
        }
        boolean on = Baritone.settings().altoAsyncSmelting.value;
        boolean done = head != null && oreDone(head.catalogueName(), head.count(), raw, f.count(Items.IRON_INGOT),
                f.pendingOutput(Items.IRON_INGOT));
        if (!on || !done || settled || gaveUp) {
            climbing = false;
            climb = null;
            return null;
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        int surface = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        int depth = depth(surface, feet.getY());
        long now = f.gameTime();
        if (!wantsUp(climbing, depth)) {
            if (climbing) {
                settled = true;
                ctx.log("up at the surface, smelting here");
            }
            climbing = false;
            climb = null;
            return null;
        }
        if (!climbing) {
            climbing = true;
            climbSince = now;
            bestDepth = depth;
            climb = new GetToYTask(surface);
            adris.altoclef.Debug.logInternal("smelting: " + raw + " raw iron in the bag and " + depth + " blocks under the surface, going up first");
        }
        if (depth < bestDepth) {
            bestDepth = depth;
            ctx.progress("heading up to smelt");
        }
        if ((now - climbSince) / 20.0 > GIVE_UP_SECONDS) {
            gaveUp = true;
            climbing = false;
            climb = null;
            ctx.log("could not get up to the surface, smelting down here");
            return null;
        }
        return climb;
    }

    public String hud() {
        return "Heading to the surface to smelt";
    }
}
