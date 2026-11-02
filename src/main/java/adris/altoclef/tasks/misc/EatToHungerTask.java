package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;

// a routine's eat node: stand there until the bar is at the line. the food chain does the actual chewing, it already knows
// what to pick and when a bite is a bad idea, this just asks for a meal and watches the bar
public class EatToHungerTask extends Task {

    // the tracker's food answer is up to half a second old, one late look must not fail a bag that has food in it
    private static final int NO_FOOD_TICKS = 20;
    // only counts ticks we really ran, a fight in the middle of the meal is not the meal failing. a bite is under 2 seconds
    // so half a minute with the bar not moving means something keeps refusing
    private static final int STALL_TICKS = 600;

    private final int target;
    private int best = -1;
    private int stalled;
    private int noFood;
    private boolean done;
    private String failure;

    public EatToHungerTask(int target) {
        this.target = target;
    }

    // null when it got there
    public String failure() {
        return failure;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        setDebugState("Eating until hunger " + target);
        step(mod.getPlayer().getFoodData().getFoodLevel(), mod.getFoodChain().hasFood());
        if (done || failure != null) {
            // a finished root gets no onStop, and a meal nobody wants anymore must not keep going
            mod.getFoodChain().cancelFillup();
        } else {
            mod.getFoodChain().requestFillup(target);
        }
        return null;
    }

    void step(int hunger, boolean hasFood) {
        if (hunger >= target) {
            done = true;
            return;
        }
        noFood = hasFood ? 0 : noFood + 1;
        if (noFood >= NO_FOOD_TICKS) {
            failure = "nothing to eat";
        } else if (hunger > best) {
            best = hunger;
            stalled = 0;
        } else if (++stalled >= STALL_TICKS) {
            failure = "couldn't eat, hunger stayed at " + hunger;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // back from an interrupt asks again on its first tick
        mod.getFoodChain().cancelFillup();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done || failure != null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Eat until hunger " + target;
    }
}
