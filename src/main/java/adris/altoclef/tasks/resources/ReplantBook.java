package adris.altoclef.tasks.resources;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

// the holes we made in the field and which one we are walking to. knows nothing about minecraft (ticks come in as
// numbers) so the stall rules can be tested without a world
final class ReplantBook<T> {
    private final Set<T> holes = new LinkedHashSet<>();
    // trampled, unreachable or just refusing seeds. never offered again, a loop on one bad cell starves the good ones
    private final Set<T> gaveUp = new HashSet<>();
    private final int budgetTicks;
    private T target;
    private int targetSince;

    ReplantBook(int budgetTicks) {
        this.budgetTicks = budgetTicks;
    }

    void add(T cell) {
        if (!gaveUp.contains(cell)) {
            holes.add(cell);
        }
    }

    // a cell that stopped being a hole (planted, trampled to dirt) is forgotten, and a target that has eaten its whole
    // budget is given up on. planting takes a few ticks, ten seconds on one cell means something is wrong with the cell
    // returns the cell it just gave up on (so the caller can say so in the log), null otherwise
    T refresh(int now, Predicate<T> stillAHole) {
        holes.removeIf(cell -> !stillAHole.test(cell));
        if (target == null) {
            return null;
        }
        if (!holes.contains(target)) {
            target = null;
        } else if (now - targetSince > budgetTicks) {
            T dud = target;
            gaveUp.add(dud);
            holes.remove(dud);
            target = null;
            return dud;
        }
        return null;
    }

    // the committed hole, or the nearest one in range when we have none. null when there is nothing worth the walk
    T pick(int now, ToDoubleFunction<T> distSq, double rangeSq) {
        if (target != null) {
            return target;
        }
        T best = null;
        double bestDist = rangeSq;
        for (T cell : holes) {
            double d = distSq.applyAsDouble(cell);
            if (d <= bestDist) {
                best = cell;
                bestDist = d;
            }
        }
        if (best != null) {
            target = best;
            targetSince = now;
        }
        return target;
    }

    // out of seeds: the walk is off, and the clock shouldn't run while we are busy finding some
    void release() {
        target = null;
    }

    int near(ToDoubleFunction<T> distSq, double rangeSq) {
        int n = 0;
        for (T cell : holes) {
            if (distSq.applyAsDouble(cell) <= rangeSq) {
                n++;
            }
        }
        return n;
    }

    boolean committed() {
        return target != null;
    }

    boolean any() {
        return !holes.isEmpty();
    }
}
