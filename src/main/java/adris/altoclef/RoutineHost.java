package adris.altoclef;

import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.commands.CrossDimensionGoto;
import adris.altoclef.commands.GameChecks;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.tasks.movement.RoutineGotoTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.altoclef.AltoClefBridge;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;

// RoutineRunner's game side: how each kind of step starts, and what "done" means for it
final class RoutineHost implements RoutineRunner.Host {

    @Override
    public String check(RoutineStep step) {
        return RoutineLines.problem(step, GameChecks.INSTANCE);
    }

    @Override
    public RoutineRunner.Start start(RoutineStep step, RoutineRunner.StepEnd end) {
        AltoClef mod;
        try {
            mod = AltoClefBridge.require();
        } catch (CommandException e) {
            return RoutineRunner.Start.failed(e.getMessage());
        }
        if (step.kind() == RoutineStep.Kind.GOTO) {
            return startGoto(mod, step.line(), end);
        }
        if (step.kind() == RoutineStep.Kind.RAW) {
            // raw is done the moment its command returns, that's the deal (no waiting promised). a task it started
            // keeps going until the next step takes the bot, so a raw follow or gamer belongs at the end
            RoutineRunner.Start s = AltoClefCommands.runForRoutine(step.line(), outcome -> {
            });
            return s.failed() ? s : RoutineRunner.Start.DONE;
        }
        // get, equip and wait start a user task and are done when it is
        return AltoClefCommands.runForRoutine(step.line(), outcome -> end.ended(outcome, null));
    }

    private static RoutineRunner.Start startGoto(AltoClef mod, String line, RoutineRunner.StepEnd end) {
        Task task;
        String rest = RoutineLines.rest(line);
        List<String> words = rest.isEmpty() ? List.of() : Arrays.asList(rest.split("\s+"));
        if (!RoutineLines.isWaypointGoto(line) && CrossDimensionGoto.endsInDimension(words)
                && CrossDimensionGoto.parseDimension(words.get(words.size() - 1)) == WorldHelper.getCurrentDimension()) {
            // the dimension we're already in: that's plain baritone, with the same done / gave up rules as any goto
            List<String> coords = words.subList(0, words.size() - 1);
            if (coords.isEmpty()) {
                return RoutineRunner.Start.DONE;
            }
            task = new RoutineGotoTask("goto " + String.join(" ", coords));
        } else if (!RoutineLines.isWaypointGoto(line) && CrossDimensionGoto.endsInDimension(words)) {
            // another dimension is altoclef's goto, a task that ends once it's there. baritone alone can't cross a portal,
            // so this one finishes on arrival and keeps trying instead of giving up
            try {
                task = CrossDimensionGoto.parse(words);
            } catch (CrossDimensionGoto.ParseException e) {
                return RoutineRunner.Start.failed("goto: " + e.getMessage());
            }
        } else {
            task = new RoutineGotoTask(line);
        }
        Task t = task;
        mod.runUserTask(t, outcome -> end.ended(outcome, t instanceof RoutineGotoTask g ? g.failure() : null));
        return RoutineRunner.Start.WAITING;
    }

    @Override
    public Object currentTask() {
        AltoClef mod = AltoClefBridge.instanceOrNull();
        return mod == null ? null : mod.getUserTaskChain().getCurrentTask();
    }

    @Override
    public void cancelTask() {
        AltoClefBridge.cancelUserTask();
    }

    @Override
    public void ownerTook() {
        AltoClef mod = AltoClefBridge.instanceOrNull();
        if (mod != null) {
            mod.getButler().onOwnerTask();
        }
    }
}
