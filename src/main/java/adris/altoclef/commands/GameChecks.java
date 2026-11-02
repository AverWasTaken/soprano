package adris.altoclef.commands;

import adris.altoclef.commands.graph.GraphValidator;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMeta;
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
