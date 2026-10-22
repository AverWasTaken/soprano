package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Action;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.function.DoubleSupplier;

// walks the sweep the planner lays out: one structure cell after another, waypoint by waypoint. it does not look at
// blocks itself, whoever owns the planner scans for sightings (NetherPhase does it every few ticks, so the same scan
// also catches a warped forest while we are busy with rods). finishes when the goal is met, failed() says why it gave up
public class FindNetherStructureTask extends Task {
    private final NetherSweepPlanner planner;
    private final Goal goal;
    private final DoubleSupplier clock;

    private static final Item[] PICKS = {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE};

    private final NetherRestock restock = new NetherRestock();
    private Task restockTask;
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

    // the whole bag, counted with the list baritone places from (the alto build block list is a different, shorter list).
    // mining goes through MineAndCollectTask, so baritone's own lava checks apply to every block it breaks. no pick = no
    // restock, a hand cannot dig netherrack
    private boolean restocking(AltoClef mod) {
        Item[] placeable = mod.getClientBaritoneSettings().acceptableThrowawayItems.value.toArray(Item[]::new);
        int held = mod.getItemStorage().getItemCount(placeable);
        boolean gaveUp = planner.takeGiveUp();
        boolean on = restock.update(held, gaveUp, mod.getItemStorage().hasItem(PICKS), clock.getAsDouble());
        if (on && restockTask == null) {
            restockTask = new GetBuildingMaterialsTask(NetherRestock.TARGET);
        } else if (!on) {
            restockTask = null;
        }
        return on;
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
