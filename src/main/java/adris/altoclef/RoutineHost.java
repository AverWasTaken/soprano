package adris.altoclef;

import adris.altoclef.commands.AltoClefCommands;
import adris.altoclef.commands.CrossDimensionGoto;
import adris.altoclef.commands.GameChecks;
import adris.altoclef.commands.RoutineLines;
import adris.altoclef.commands.RoutineStep;
import adris.altoclef.commands.graph.GraphAction;
import adris.altoclef.tasks.misc.ConditionWaitTask;
import adris.altoclef.tasks.misc.EatToHungerTask;
import adris.altoclef.tasks.movement.FollowForTask;
import adris.altoclef.tasks.movement.RoutineGotoTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.squashed.GatherBillTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.altoclef.AltoClefBridge;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockOptionalMeta;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

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
    public RoutineRunner.Start startQuiet(RoutineStep step, RoutineRunner.StepEnd end) {
        AltoClef mod = AltoClefBridge.instanceOrNull();
        if (mod == null) {
            return start(step, end);
        }
        mod.getUserTaskChain().signalNextTaskQuiet();
        try {
            return start(step, end);
        } finally {
            // a raw line that never started a task must not make somebody else's next one quiet
            mod.getUserTaskChain().clearNextTaskQuiet();
        }
    }

    @Override
    public RoutineRunner.Start startWait(Supplier<RoutineRunner.Result> poll, double timeoutSeconds, String label, RoutineRunner.WaitEnd end) {
        AltoClef mod;
        try {
            mod = AltoClefBridge.require();
        } catch (CommandException e) {
            return RoutineRunner.Start.failed(e.getMessage());
        }
        ConditionWaitTask task = new ConditionWaitTask(poll, timeoutSeconds, label);
        mod.getUserTaskChain().signalNextTaskQuiet();
        mod.runUserTask(task, outcome -> end.ended(outcome, task.result()));
        return RoutineRunner.Start.WAITING;
    }

    @Override
    public RoutineRunner.Start startAction(GraphAction action, RoutineRunner.StepEnd end) {
        AltoClef mod;
        Task task;
        try {
            mod = AltoClefBridge.require();
            task = taskFor(mod, action);
        } catch (CommandException | IllegalArgumentException e) {
            return RoutineRunner.Start.failed(e.getMessage());
        }
        mod.getUserTaskChain().signalNextTaskQuiet();
        mod.runUserTask(task, outcome -> end.ended(outcome, failureOf(task)));
        return RoutineRunner.Start.WAITING;
    }

    private static String failureOf(Task task) {
        if (task instanceof EatToHungerTask eat) {
            return eat.failure();
        }
        return task instanceof FollowForTask follow ? follow.failure() : null;
    }

    // counts go on top of what we hold when the node starts. "hold 16" would be done on the spot on every lap after the
    // first, which is the same do nothing loop a raw line is
    private static Task taskFor(AltoClef mod, GraphAction action) {
        return switch (action) {
            case GraphAction.Mine mine -> {
                Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(mine.block())).orElse(null);
                if (block == null) {
                    throw new IllegalArgumentException("\"" + mine.block() + "\" is not a block");
                }
                Item[] drops = dropsOf(block);
                if (drops.length == 0) {
                    throw new IllegalArgumentException(mine.block() + " has no drop we can count, try a Get node");
                }
                ItemTarget target = new ItemTarget(drops, mod.getItemStorage().getItemCount(drops) + mine.count());
                yield new MineAndCollectTask(new ItemTarget[]{target}, new Block[]{block}, MiningRequirement.getMinimumRequirementForBlock(block));
            }
            case GraphAction.Craft craft -> {
                // the same road as get: the whole shopping list, then craft. it gets the ingredients first if it has to
                Item item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(craft.item())).orElse(null);
                if (item == null || !TaskCatalogue.taskExists(craft.item())) {
                    throw new IllegalArgumentException("\"" + craft.item() + "\" is not something altoclef can make");
                }
                yield new GatherBillTask(new ItemTarget(craft.item(), mod.getItemStorage().getItemCount(item) + craft.count()));
            }
            case GraphAction.Eat eat -> new EatToHungerTask(eat.hunger());
            case GraphAction.Follow follow -> new FollowForTask(follow.player(), follow.seconds());
        };
    }

    // what breaking it really gets us. the block's own item only counts next to that: silk touch can give it, but counting
    // glass as a drop of glass is a mine that smashes every window around and never gets to its number. the roll is one
    // sample with no player in it, so a few real drops read as none (snow). a failed wire beats a node that never ends
    private static Item[] dropsOf(Block block) {
        List<Item> out = new ArrayList<>(BlockOptionalMeta.plainDrops(block));
        if (!out.isEmpty() && block.asItem() != Items.AIR && !out.contains(block.asItem())) {
            out.add(block.asItem());
        }
        return out.toArray(Item[]::new);
    }

    @Override
    public void quietRunEnded(String name, RoutineRunner.State state, String message) {
        // a stop or a replace has somebody who already knows
        if (state == RoutineRunner.State.DONE) {
            Debug.logMessage(name + " is done");
        } else if (state == RoutineRunner.State.FAILED) {
            // the log gets the whole reason. chat gets it too unless it's a java exception talking, nobody can act on
            // "NullPointerException: Cannot invoke..."
            Debug.logInternal("routine " + name + " failed: " + message);
            Debug.logMessage(looksLikeException(message) ? name + " failed. See the game log." : name + " failed: " + message);
        }
    }

    static boolean looksLikeException(String message) {
        return message != null && (message.contains("Exception") || message.contains("java.") || message.contains("Error:"));
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
