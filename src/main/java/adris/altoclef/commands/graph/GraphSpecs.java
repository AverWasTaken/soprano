package adris.altoclef.commands.graph;

import java.util.List;
import java.util.Map;

// what each node and condition asks for. the validator and the inspector both read this, so a limit only lives here
public final class GraphSpecs {

    public enum FieldKind {
        INT,
        // a whole number, no ~: "at position" asks where you are, not where you are relative to you
        COORD,
        CHOICE,
        OP,
        ITEM,
        BLOCK,
        MOB,
        // a command line, the inspector opens it in the existing boxes (RoutinesAccess.fieldsOf / lineFor)
        LINE,
        SECONDS,
        COND
    }

    // whenMode = only asked for in that mode, null = always. an empty required field is what the validator flags
    public record Field(String key, String label, FieldKind kind, boolean required, int min, int max, List<String> choices, String whenMode) {
        static Field of(String key, String label, FieldKind kind) {
            return new Field(key, label, kind, true, 0, 0, List.of(), null);
        }

        static Field number(String key, String label, int min, int max) {
            return new Field(key, label, FieldKind.INT, true, min, max, List.of(), null);
        }

        static Field choice(String key, String label, String... choices) {
            return new Field(key, label, FieldKind.CHOICE, true, 0, 0, List.of(choices), null);
        }

        Field when(String mode) {
            return new Field(key, label, kind, required, min, max, choices, mode);
        }

        Field optional() {
            return new Field(key, label, kind, false, min, max, choices, whenMode);
        }
    }

    public record NodeSpec(RoutineGraph.Type type, String group, String label, List<Field> fields, List<String> inputs, List<String> outputs) {
    }

    public record CondSpec(String kind, String label, List<Field> fields) {
    }

    public static final List<String> OPS = List.of(">=", "<=", "=", ">", "<");
    public static final int MAX_LOOPS = 10000;
    public static final int MAX_POS_RADIUS = 256;
    public static final int MAX_MOB_RADIUS = 64;
    // 33^3 blocks worst case. further than this is what #goto is for
    public static final int MAX_BLOCK_RADIUS = 16;
    public static final int MAX_COORD = 30_000_000;

    private static final Field OP = Field.of("op", "Compare", FieldKind.OP);

    public static final List<CondSpec> CONDS = List.of(
            new CondSpec("has_item", "Has item", List.of(Field.of("item", "Item", FieldKind.ITEM), OP, Field.number("value", "Count", 0, 9999))),
            new CondSpec("health", "Health", List.of(OP, Field.number("value", "Health", 0, 20))),
            new CondSpec("hunger", "Hunger", List.of(OP, Field.number("value", "Hunger", 0, 20))),
            new CondSpec("time", "Time of day", List.of(Field.choice("value", "Time", "day", "night"))),
            new CondSpec("dimension", "Dimension", List.of(Field.choice("value", "Dimension", "overworld", "nether", "end"))),
            new CondSpec("free_slots", "Free slots", List.of(OP, Field.number("value", "Slots", 0, 36))),
            new CondSpec("near_pos", "At position", List.of(Field.of("x", "X", FieldKind.COORD), Field.of("y", "Y", FieldKind.COORD).optional(), Field.of("z", "Z", FieldKind.COORD), Field.number("r", "Within", 1, MAX_POS_RADIUS))),
            new CondSpec("near_mob", "Near mob", List.of(Field.of("mob", "Mob", FieldKind.MOB), Field.number("r", "Within", 1, MAX_MOB_RADIUS))),
            new CondSpec("near_block", "Near block", List.of(Field.of("block", "Block", FieldKind.BLOCK), Field.number("r", "Within", 1, MAX_BLOCK_RADIUS)))
    );

    private static final Field LINE = Field.of("line", "Command", FieldKind.LINE);
    private static final Field COND = Field.of("cond", "Condition", FieldKind.COND);

    public static final List<NodeSpec> NODES = List.of(
            node(RoutineGraph.Type.START, "end"),
            node(RoutineGraph.Type.GET, "act", LINE),
            node(RoutineGraph.Type.EQUIP, "act", LINE),
            node(RoutineGraph.Type.GOTO, "act", LINE),
            node(RoutineGraph.Type.RAW, "act", LINE),
            node(RoutineGraph.Type.IF, "flow", COND),
            node(RoutineGraph.Type.REPEAT, "flow",
                    Field.choice("mode", "Mode", RoutineGraph.MODE_TIMES, RoutineGraph.MODE_UNTIL, RoutineGraph.MODE_FOREVER),
                    Field.number("n", "Times", 1, MAX_LOOPS).when(RoutineGraph.MODE_TIMES),
                    COND.when(RoutineGraph.MODE_UNTIL),
                    Field.number("cap", "Give up after", 1, MAX_LOOPS).when(RoutineGraph.MODE_UNTIL)),
            node(RoutineGraph.Type.WAIT, "flow",
                    Field.choice("mode", "Mode", RoutineGraph.MODE_SECS, RoutineGraph.MODE_UNTIL),
                    Field.of("secs", "Seconds", FieldKind.SECONDS).when(RoutineGraph.MODE_SECS),
                    COND.when(RoutineGraph.MODE_UNTIL),
                    Field.of("timeout", "Time out after", FieldKind.SECONDS).when(RoutineGraph.MODE_UNTIL)),
            node(RoutineGraph.Type.STOP, "end", Field.choice("ok", "Ends as", "true", "false").optional())
    );

    private GraphSpecs() {
    }

    // outputs = every port the type can ever have, RoutineGraph.outputs is the one that looks at the mode
    private static NodeSpec node(RoutineGraph.Type type, String group, Field... fields) {
        RoutineGraph.Node widest = new RoutineGraph.Node("", type, 0, 0, Map.of("mode", RoutineGraph.MODE_UNTIL), null);
        return new NodeSpec(type, group, type.label, List.of(fields), RoutineGraph.inputs(type), RoutineGraph.outputs(widest));
    }

    public static CondSpec cond(String kind) {
        for (CondSpec c : CONDS) {
            if (c.kind().equals(kind)) {
                return c;
            }
        }
        return null;
    }
}
