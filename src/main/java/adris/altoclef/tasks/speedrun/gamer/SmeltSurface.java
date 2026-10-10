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

    private boolean climbing;
    // we already surfaced for this batch (one flag per thing waiting: the ore, the meat). coal or fuel underground must not
    // send us up again and again
    private final boolean[] settled = new boolean[2];
    private final boolean[] gaveUp = new boolean[2];
    private long climbSince;
    private int bestDepth;
    private Task climb;
    private Why climbWhy = Why.IRON;

    public void reset() {
        climbing = false;
        settled[0] = settled[1] = false;
        gaveUp[0] = gaveUp[1] = false;
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

    // close enough to the open sky that a trip for something on the surface is not a trip out of a mine (same line as wantsUp)
    public static boolean shallow(int depth) {
        return depth <= GO_UP_DEPTH;
    }

    // how far below the open sky we are standing right now
    public static int depthBelowSky(AltoClef mod) {
        BlockPos feet = mod.getPlayer().blockPosition();
        return depth(mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()), feet.getY());
    }

    // where a split smelt may be decided (SmeltSplit.atSite): up top, surfaced for this batch, or the climb gave up and we smelt
    // down here. never on the way up, and never down a mine we are still working (the furnaces would end up half down there)
    public static boolean smeltSite(boolean climbing, boolean surfaced, boolean gaveUp, int depth) {
        return !climbing && (surfaced || gaveUp || shallow(depth));
    }

    public boolean smeltSite(AltoClef mod) {
        return smeltSite(climbing, settled[IRON], gaveUp[IRON], depthBelowSky(mod));
    }

    public static boolean wantsUp(boolean alreadyClimbing, int depth) {
        return alreadyClimbing ? depth > ARRIVED_DEPTH : depth > GO_UP_DEPTH;
    }

    // what is waiting for the surface
    public enum Why {
        NONE, IRON, COOK
    }

    private static final int IRON = 0;
    private static final int COOK = 1;

    // the cook is at the front of the plan and there is raw meat for it to turn into dinner
    public static boolean cookDone(KitNeed head, int rawMeat) {
        return head != null && KitNeed.isCookName(head.catalogueName()) && rawMeat > 0;
    }

    // the work after the head is still ore: the bot goes on mining right here, so a furnace left cooking by the vein is
    // where it will be. an iron need whose ore is all in the bag is a smelt, and a smelt goes to the surface
    public static boolean nextWorkDown(KitNeed next, int rawIron, int ingots, int pending) {
        return next != null && oreLeft(next, rawIron, ingots, pending);
    }

    private static boolean oreLeft(KitNeed need, int rawIron, int ingots, int pending) {
        return "iron_ingot".equals(need.catalogueName()) && !oreDone(need.catalogueName(), need.count(), rawIron, ingots, pending);
    }

    // a furnace or smoker that gets loaded and left is a trip back for the output. underground that is a walk down a cave
    // (22:08 smoker at y 32, 40 s of walking back to it after a hunt on the surface), so the thing to be loaded rides up first
    // unless the work after it is down here too. the iron smelt has always done this; the cook is the same rule
    // `cookRunning` = the cook task already picked its station (CookTrip) and is walking to it. that one is a smoker or furnace
    // that is already standing, wherever it is, and climbing out just to come back down to it is a round trip for nothing
    public static Why why(KitNeed head, KitNeed next, int rawIron, int ingots, int pending, int rawMeat, boolean cookRunning) {
        return why(head, next, rawIron, ingots, pending, rawMeat, cookRunning, false);
    }

    // `earlyBatch` = the head is the first three ingots for the pick (EarlyIronPick.isEarlyBatch). they are smelted right where the
    // ore is, so a count 3 iron head reads as "all the ore is in" and without this it climbed out of the mine for three ore, once
    // from the plain loop and once while some other job (a smoker) was cooking
    public static Why why(KitNeed head, KitNeed next, int rawIron, int ingots, int pending, int rawMeat, boolean cookRunning,
                          boolean earlyBatch) {
        if (earlyBatch) {
            return Why.NONE;
        }
        if (head != null && oreDone(head.catalogueName(), head.count(), rawIron, ingots, pending)) {
            return Why.IRON;
        }
        if (cookDone(head, rawMeat) && !cookRunning && !nextWorkDown(next, rawIron, ingots, pending)) {
            return Why.COOK;
        }
        return Why.NONE;
    }

    // what the surface is wanted for right now, after the settings and the latches. NONE = carry on with the plan
    private Why wanted(GamerContext ctx, KitNeed head, KitNeed next) {
        GamerFacts f = ctx.facts();
        int raw = f.count(Items.RAW_IRON);
        int meat = CookGate.raw(f);
        // the batch went in the furnace (or was never there), the next one starts fresh
        if (raw == 0) {
            settled[IRON] = false;
            gaveUp[IRON] = false;
        }
        if (meat == 0) {
            settled[COOK] = false;
            gaveUp[COOK] = false;
        }
        Why why = why(head, next, raw, f.count(Items.IRON_INGOT), f.pendingOutput(Items.IRON_INGOT), meat, f.cookStation() != null,
                EarlyIronPick.isEarlyBatch(head, f, ctx.cfg().overworld));
        if (why == Why.NONE || !Baritone.settings().altoAsyncSmelting.value
                || (why == Why.COOK && !Baritone.settings().altoAsyncCooking.value)) {
            return Why.NONE;
        }
        int kind = why == Why.IRON ? IRON : COOK;
        return settled[kind] || gaveUp[kind] ? Why.NONE : why;
    }

    // the surface is wanted and we are not on our way yet, and we are deep enough for it to be a climb: the moment to pack up
    // whatever we left cooking in the mine first (PackUp)
    public boolean aboutToClimb(AltoClef mod, GamerContext ctx, KitNeed head, KitNeed next) {
        return !climbing && wanted(ctx, head, next) != Why.NONE && wantsUp(false, depthBelowSky(mod));
    }

    // the task to run in front of the smelt (or the cook), null = carry on with the plan
    public Task tick(AltoClef mod, GamerContext ctx, KitNeed head, KitNeed next) {
        GamerFacts f = ctx.facts();
        Why why = wanted(ctx, head, next);
        if (why == Why.NONE) {
            climbing = false;
            climb = null;
            return null;
        }
        int kind = why == Why.IRON ? IRON : COOK;
        BlockPos feet = mod.getPlayer().blockPosition();
        int surface = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        int depth = depth(surface, feet.getY());
        long now = f.gameTime();
        if (!wantsUp(climbing, depth)) {
            if (climbing) {
                settled[kind] = true;
                ctx.log(why == Why.IRON ? "up at the surface, smelting here" : "up at the surface, cooking here");
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
            adris.altoclef.Debug.logInternal(why == Why.IRON
                    ? "smelting: " + f.count(Items.RAW_IRON) + " raw iron in the bag and " + depth + " blocks under the surface, going up first"
                    : "cooking: " + CookGate.raw(f) + " raw meat in the bag and " + depth + " blocks under the surface, going up first");
        }
        climbWhy = why;
        if (depth < bestDepth) {
            bestDepth = depth;
            ctx.progress(why == Why.IRON ? "heading up to smelt" : "heading up to cook");
        }
        if (FurnacePlan.climbGaveUp(climbSince, now)) {
            gaveUp[kind] = true;
            climbing = false;
            climb = null;
            ctx.log(why == Why.IRON ? "could not get up to the surface, smelting down here" : "could not get up to the surface, cooking down here");
            return null;
        }
        return climb;
    }

    public String hud() {
        return climbWhy == Why.COOK ? "Heading to the surface to cook" : "Heading to the surface to smelt";
    }
}
