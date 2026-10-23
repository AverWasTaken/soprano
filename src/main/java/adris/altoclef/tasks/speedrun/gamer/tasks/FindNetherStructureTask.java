package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Action;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LeavesBlock;

import java.util.List;
import java.util.function.DoubleSupplier;

// walks the sweep the planner lays out: one structure cell after another, waypoint by waypoint. it does not look at
// blocks itself, whoever owns the planner scans for sightings (NetherPhase does it every few ticks, so the same scan
// also catches a warped forest while we are busy with rods). finishes when the goal is met, failed() says why it gave up
public class FindNetherStructureTask extends Task {
    private final NetherSweepPlanner planner;
    private final Goal goal;
    private final DoubleSupplier clock;

    private final NetherRestock restock = new NetherRestock();
    private MineAndCollectTask restockTask;
    private GetToXZTask walk;
    private int walkX;
    private int walkZ;
    private String failure;
    private String step = "Looking for a fortress";

    public FindNetherStructureTask(NetherSweepPlanner planner, Goal goal, DoubleSupplier clock) {
        this.planner = planner;
        this.goal = goal;
        this.clock = clock;
        if (goal == Goal.WARPED) {
            step = "Looking for a warped forest";
        }
    }

    public String failure() {
        return failure;
    }

    public String step() {
        return step;
    }

    @Override
    protected void onStart(AltoClef mod) {
        failure = null;
        // bastions are where piglin brutes live, the path planner treats the avoid zone as a wall
        mod.getBehaviour().push();
        mod.getBehaviour().avoidWalkingThrough(p -> planner.blocksPath(p.getX(), p.getZ()));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        int px = mod.getPlayer().blockPosition().getX();
        int pz = mod.getPlayer().blockPosition().getZ();
        // mid restock the planner is not asked: the idle gap is handled there like any other fight or meal
        if (restock.isActive() && restocking(mod)) {
            setDebugState("Restocking building blocks", step);
            return restockTask;
        }
        Action action = planner.step(goal, px, pz, clock.getAsDouble());
        switch (action.kind()) {
            case GOTO -> {
                if (restocking(mod)) {
                    setDebugState("Restocking building blocks", step);
                    return restockTask;
                }
                setDebugState("Sweeping " + action.why() + " towards " + action.x() + "," + action.z(), step);
                return walkTo(action.x(), action.z());
            }
            case FAIL -> {
                failure = action.why();
                setDebugState("Giving up the sweep: " + failure, step);
                return null;
            }
            default -> {
                setDebugState("Found it", step);
                return null;
            }
        }
    }

    // the whole bag, counted with the list baritone places from, and the dig goes for exactly that list too (the alto build
    // list also has soul sand and magma in it, which baritone never places, so a bag of those would end the dig and stay
    // "empty"). MineAndCollectTask breaks through baritone, so its lava checks apply to every block. no pick (the same check
    // MineAndCollectTask makes) = no restock, a hand cannot dig netherrack
    // a block with lava on any side is either holding the lava back or holding us up over it (a bridge is all lava
    // underneath). neither is ours to mine for a few spare blocks
    private static boolean touchesLava(AltoClef mod, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (mod.getWorld().getFluidState(p.relative(d)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    // the count the restock goes by, for the phase that decides on a trip home when the dig keeps coming up empty
    public static int heldThrowaways(AltoClef mod) {
        return mod.getItemStorage().getItemCount(placeableOnly(mod.getClientBaritoneSettings().acceptableThrowawayItems.value));
    }

    public int exhaustedEnds() {
        return restock.exhaustedEnds();
    }

    public void clearExhaustedEnds() {
        restock.clearExhaustedEnds();
    }

    private boolean restocking(AltoClef mod) {
        Item[] placeable = placeableOnly(mod.getClientBaritoneSettings().acceptableThrowawayItems.value);
        int held = mod.getItemStorage().getItemCount(placeable);
        boolean gaveUp = planner.takeGiveUp();
        boolean exhausted = restockTask != null && restockTask.wandering();
        boolean on = restock.update(held, gaveUp, StorageHelper.miningRequirementMet(mod, MiningRequirement.WOOD),
                exhausted, clock.getAsDouble());
        if (on && restockTask == null) {
            // the band is frozen where the dig starts, following live feet would walk it down its own shaft
            BlockPos origin = mod.getPlayer().blockPosition();
            // the same array counts and digs, two lists would disagree on done and hand a finished task back every tick
            restockTask = new MineAndCollectTask(new ItemTarget[]{new ItemTarget(placeable, NetherRestock.TARGET)},
                    MiningRequirement.WOOD).onlyWhere(p -> NetherRestock.inRestockBand(origin, p) && !touchesLava(mod, p));
        } else if (!on) {
            restockTask = null;
        }
        return on;
    }

    // baritone's list has items it counts but can never place (nether brick the item, leaves), and the merge can list an
    // item twice. distinct and block items only
    static Item[] placeableOnly(List<Item> items) {
        return items.stream().distinct()
                .filter(i -> i instanceof BlockItem b && !(b.getBlock() instanceof LeavesBlock))
                .toArray(Item[]::new);
    }

    // a new GetToXZTask object only when the waypoint changes, a new one per tick would restart its pathing every tick
    private Task walkTo(int x, int z) {
        if (walk == null || walkX != x || walkZ != z) {
            walk = new GetToXZTask(x, z, planner.arriveRadius());
            walkX = x;
            walkZ = z;
        }
        return walk;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return planner.goalMet(goal);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FindNetherStructureTask task && task.planner == planner && task.goal == goal;
    }

    @Override
    protected String toDebugString() {
        return "Sweeping the Nether for " + goal;
    }

    @Override
    protected String toHudString() {
        return goal == Goal.WARPED ? "Searching for a warped forest" : "Searching for a Nether fortress";
    }
}
