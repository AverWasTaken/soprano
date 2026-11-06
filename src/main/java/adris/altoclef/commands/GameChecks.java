package adris.altoclef.commands;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.commands.graph.GraphStore;
import adris.altoclef.commands.graph.GraphValidator;
import adris.altoclef.commands.graph.RoutineGraph;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMeta;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

// RoutineLines' questions for the running game. a check that throws (no world yet, registries not up) counts as fine:
// the run finds out for real, the editor shouldn't go red over something it can't know
public final class GameChecks implements GraphValidator.Checks {

    public static final GameChecks INSTANCE = new GameChecks();

    private GameChecks() {
    }

    @Override
    public boolean commandExists(String label) {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().getCommand(label) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public String itemProblem(RoutineStep.Kind kind, String name) {
        AltoItem as = kind == RoutineStep.Kind.EQUIP ? AltoItem.ARMOR : AltoItem.CATALOGUE;
        try {
            if (as.accepts(name)) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return as == AltoItem.ARMOR
                ? "\"" + name + "\" is not armor altoclef knows about"
                : "\"" + name + "\" is not something altoclef can get, " + AltoClefCommands.prefix() + "list shows what there is";
    }

    @Override
    public String blockProblem(String name) {
        try {
            new BlockOptionalMeta(name);
            return null;
        } catch (IllegalArgumentException e) {
            return "\"" + name + "\" is not a block";
        } catch (Throwable t) {
            return null;
        }
    }

    // the plain registry ids a condition takes. stricter than a get line on purpose: "has_item log" has no catalogue to
    // ask and would just never be true
    @Override
    public String idProblem(GraphValidator.IdKind kind, String id) {
        try {
            return switch (kind) {
                case ITEM -> known(BuiltInRegistries.ITEM, id) ? null : "\"" + id + "\" is not an item";
                case BLOCK -> known(BuiltInRegistries.BLOCK, id) ? null : "\"" + id + "\" is not a block";
                case MOB -> known(BuiltInRegistries.ENTITY_TYPE, id) ? null : "\"" + id + "\" is not a mob";
            };
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String routineProblem(String id) {
        try {
            GraphStore.Entry entry = GraphStore.get().find(id);
            if (entry != null) {
                return entry.graph() == null ? "routines/" + entry.id() + ".json has a problem: " + entry.error() : null;
            }
            return CommandsStore.get().find(id) != null ? null : "there's no routine called " + id;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public List<String> callsOf(String id) {
        List<String> out = new ArrayList<>();
        try {
            GraphStore.Entry entry = GraphStore.get().find(id);
            if (entry != null && entry.graph() != null) {
                for (RoutineGraph.Node n : entry.graph().nodes()) {
                    if (n.type() == RoutineGraph.Type.SUB && !n.param("routine").isBlank()) {
                        out.add(n.param("routine").trim());
                    }
                }
            }
        } catch (Throwable t) {
            // the run has its own guard
        }
        return out;
    }

    @Override
    public boolean craftable(String item) {
        try {
            if (TaskCatalogue.recipeOf(item) != null) {
                return true;
            }
            TaskCatalogue.BillEdge edge = TaskCatalogue.billEdge(item);
            return edge != null && edge.step() == TaskCatalogue.BillEdge.Step.CRAFT;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean known(Registry<?> registry, String id) {
        Identifier parsed = Identifier.tryParse(id);
        return parsed != null && registry.containsKey(parsed);
    }

    // the user's prefix, for stripping it off a pasted line. # when there are no settings to ask
    public static String prefix() {
        try {
            return AltoClefCommands.prefix();
        } catch (Throwable t) {
            return "#";
        }
    }
}
