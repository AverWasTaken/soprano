package adris.altoclef;

import adris.altoclef.RoutineRunner.Op;
import adris.altoclef.RoutineRunner.Result;
import adris.altoclef.commands.RoutineStep;
import java.util.List;
import java.util.function.Function;

// a list routine: down the list, the first failure ends it. the node is just the index
final class LinearProgram implements RoutineRunner.Program {

    private final List<RoutineStep> steps;
    private int index;

    LinearProgram(List<RoutineStep> steps) {
        this.steps = List.copyOf(steps);
    }

    @Override
    public String precheck(Function<RoutineStep, String> check) {
        for (int i = 0; i < steps.size(); i++) {
            String why = check.apply(steps.get(i));
            if (why != null) {
                index = i;
                return why;
            }
        }
        return null;
    }

    @Override
    public Op first() {
        return steps.isEmpty() ? new Op.End(RoutineRunner.State.DONE, "there are no steps in it") : current();
    }

    @Override
    public Op next(String node, Result result) {
        if (result.kind() == Result.Kind.FAILED) {
            return new Op.End(RoutineRunner.State.FAILED, result.message());
        }
        if (index + 1 >= steps.size()) {
            return new Op.End(RoutineRunner.State.DONE, null);
        }
        index++;
        return current();
    }

    private Op current() {
        return new Op.Step(String.valueOf(index), steps.get(index));
    }

    // a list is at most 100 steps and always ran them in one go
    @Override
    public int inlineBudget() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean quiet() {
        return false;
    }

    @Override
    public int step() {
        return steps.isEmpty() ? 0 : index + 1;
    }

    @Override
    public int total() {
        return steps.size();
    }

    @Override
    public String line() {
        return steps.isEmpty() ? null : steps.get(index).line();
    }

    @Override
    public RoutineRunner.Trace trace() {
        return null;
    }
}
