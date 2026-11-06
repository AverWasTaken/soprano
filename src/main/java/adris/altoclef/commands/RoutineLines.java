package adris.altoclef.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// the rules for a routine's lines: what each step kind looks like, the editor fields <-> line round trip, and the
// legacy command+parameters spelling of CustomTasks.json. no minecraft in here, whatever needs the game comes in
// through Checks so the tests can say yes to everything
public final class RoutineLines {

    // what only the running game knows. every answer is "fine" unless it says otherwise
    public interface Checks {
        boolean commandExists(String label);

        // null when the name is fine for that kind of step (GET: the catalogue, EQUIP: armor)
        String itemProblem(RoutineStep.Kind kind, String name);

        String blockProblem(String name);

        Checks SHAPE_ONLY = new Checks() {
            @Override
            public boolean commandExists(String label) {
                return true;
            }

            @Override
            public String itemProblem(RoutineStep.Kind kind, String name) {
                return null;
            }

            @Override
            public String blockProblem(String name) {
                return null;
            }
        };
    }

    // a list longer than this is a typo in a hand edit, or somebody trying something
    public static final int MAX_STEPS = 100;
    public static final int MAX_ID = 40;
    // a day. a wait longer than that is a step nobody meant
    public static final double MAX_WAIT_SECONDS = 86400;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_ID + "}");
    // what baritone's own goto takes for a coordinate: a number, ~, or ~ with an offset
    private static final Pattern COORD = Pattern.compile("[+-]?\\d{1,9}|~([+-]?\\d{1,9})?");
    private static final Set<String> SELF = Set.of("custom", "custom2");
    private static final Set<String> WAYPOINT_LABELS = Set.of("wp", "waypoint", "waypoints");

    private RoutineLines() {
    }

    // a step's line as it gets saved: no prefix, single spaces. a pasted "#get dirt" is just get dirt
    public static String normalize(String line, String prefix) {
        if (line == null) {
            return "";
        }
        String t = line.trim();
        if (prefix != null && !prefix.isEmpty() && t.startsWith(prefix)) {
            t = t.substring(prefix.length()).trim();
        }
        if (t.startsWith("#")) {
            t = t.substring(1).trim();
        }
        return t.replaceAll("\\s+", " ");
    }

    public static String label(String line) {
        String t = line == null ? "" : line.trim();
        int space = t.indexOf(' ');
        return (space < 0 ? t : t.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    public static String rest(String line) {
        String t = line == null ? "" : line.trim();
        int space = t.indexOf(' ');
        return space < 0 ? "" : t.substring(space + 1).trim();
    }

    private static List<String> words(String rest) {
        List<String> out = new ArrayList<>();
        for (String w : rest.trim().split("\\s+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    // "wp goto home", the one goto that doesn't start with goto
    public static boolean isWaypointGoto(String line) {
        List<String> w = words(line == null ? "" : line);
        return w.size() >= 2 && WAYPOINT_LABELS.contains(w.get(0).toLowerCase(Locale.ROOT)) && w.get(1).equalsIgnoreCase("goto");
    }

    // "goto 1 64 -3 nether" or "goto the_end": a goto that ends in a dimension. those are raw steps now, a Goto step
    // walks around the world it's in
    public static boolean isDimensionGoto(String line) {
        return label(line).equals("goto") && CrossDimensionGoto.endsInDimension(words(rest(line)));
    }

    // what a line is when nobody said (a hand written file, a legacy entry). raw is the honest answer for the rest
    public static RoutineStep.Kind kindOf(String line) {
        if (isWaypointGoto(line)) {
            return RoutineStep.Kind.GOTO;
        }
        // an old #custom entry that crossed dimensions keeps running, it just runs as the raw line it always was
        if (isDimensionGoto(line)) {
            return RoutineStep.Kind.RAW;
        }
        return switch (label(line)) {
            case "get" -> RoutineStep.Kind.GET;
            case "equip" -> RoutineStep.Kind.EQUIP;
            case "goto" -> RoutineStep.Kind.GOTO;
            case "wait" -> RoutineStep.Kind.WAIT;
            default -> RoutineStep.Kind.RAW;
        };
    }

    public static RoutineStep.Kind kindNamed(String name) {
        if (name == null) {
            return null;
        }
        for (RoutineStep.Kind k : RoutineStep.Kind.values()) {
            if (k.name().equalsIgnoreCase(name.trim())) {
                return k;
            }
        }
        return null;
    }

    public static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    // the one word #custom takes, made out of whatever the tab's name box says. null when nothing usable is left
    public static String idFor(String name) {
        if (name == null) {
            return null;
        }
        String id = name.trim().replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_-]", "");
        if (id.length() > MAX_ID) {
            id = id.substring(0, MAX_ID);
        }
        return id.isEmpty() ? null : id;
    }

    // the problems a line has before anybody asks the game: these would break the file or the run itself
    public static String shapeProblem(String line) {
        if (line == null || line.isBlank()) {
            return "this step is empty";
        }
        if (line.indexOf(';') >= 0) {
            return "one command per step, no ;";
        }
        if (SELF.contains(label(line))) {
            // a routine that starts another one would have two runs fighting over the same callbacks
            return "a routine can't run custom, add those steps instead";
        }
        return null;
    }

    // why this step can't run, or null. same rules for the editor's red rows and the runner's last look before a run
    public static String problem(RoutineStep step, Checks checks) {
        if (step == null || step.kind() == null) {
            return "this step has no type";
        }
        String line = step.line();
        String shape = shapeProblem(line);
        if (shape != null) {
            return shape;
        }
        String label = label(line);
        String kindProblem = switch (step.kind()) {
            case GET -> label.equals("get") ? itemsProblem(RoutineStep.Kind.GET, rest(line), checks) : "a Get step runs get";
            case EQUIP -> label.equals("equip") ? equipProblem(rest(line), checks) : "an Equip step runs equip";
            case GOTO -> gotoProblem(line, checks);
            case WAIT -> label.equals("wait") ? waitProblem(rest(line)) : "a Wait step runs wait";
            case RAW -> null;
        };
        if (kindProblem != null) {
            return kindProblem;
        }
        if (!checks.commandExists(label)) {
            return "there is no command called " + label;
        }
        return null;
    }

    private static String itemsProblem(RoutineStep.Kind kind, String rest, Checks checks) {
        List<ItemArgs.Entry> entries;
        try {
            entries = ItemArgs.parse(rest);
        } catch (ItemArgs.ParseException e) {
            return e.getMessage();
        }
        if (entries.isEmpty()) {
            return kind == RoutineStep.Kind.GET ? "get what? add an item" : "equip what? add an item";
        }
        for (ItemArgs.Entry e : entries) {
            String p = checks.itemProblem(kind, e.name());
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static String equipProblem(String rest, Checks checks) {
        List<String> w = words(rest);
        // "equip iron" is the whole set, EquipCommand reads it the same way
        if (w.size() == 1 && AltoItem.ARMOR_SETS.contains(w.get(0).toLowerCase(Locale.ROOT))) {
            return null;
        }
        return itemsProblem(RoutineStep.Kind.EQUIP, rest, checks);
    }

    private static String waitProblem(String rest) {
        List<String> w = words(rest);
        if (w.size() != 1) {
            return "wait takes one number of seconds";
        }
        double s;
        try {
            s = Double.parseDouble(w.get(0));
        } catch (NumberFormatException e) {
            return "\"" + w.get(0) + "\" is not a number of seconds";
        }
        if (!(s > 0) || s > MAX_WAIT_SECONDS) {
            return "wait between 0 and " + (int) MAX_WAIT_SECONDS + " seconds";
        }
        return null;
    }

    // goto x y z / goto x z / goto y, goto <block>, wp goto <name>
    private static String gotoProblem(String line, Checks checks) {
        if (isWaypointGoto(line)) {
            return words(line).size() > 2 ? null : "which waypoint?";
        }
        if (!label(line).equals("goto")) {
            return "a Goto step runs goto or wp goto";
        }
        List<String> w = words(rest(line));
        if (w.isEmpty()) {
            return "goto where?";
        }
        // a dimension change is a portal trip with its own ways to go wrong, the goto step doesn't promise that.
        // the raw step still runs the same line
        if (CrossDimensionGoto.endsInDimension(w)) {
            return "use a Raw step to change dimension";
        }
        if (w.size() > 3) {
            return "that is " + w.size() + " numbers, a position is at most 3 (x y z)";
        }
        if (w.size() == 1 && !COORD.matcher(w.get(0)).matches()) {
            return checks.blockProblem(w.get(0));
        }
        for (String c : w) {
            if (!COORD.matcher(c).matches()) {
                return "\"" + c + "\" is not a coordinate";
            }
        }
        return null;
    }

    // the editor's fields -> the step's line. never throws, a half filled row just makes a line the checks turn down
    public static String lineFor(RoutineStep.Kind kind, Map<String, String> fields) {
        Map<String, String> f = fields == null ? Map.of() : fields;
        return switch (kind == null ? RoutineStep.Kind.RAW : kind) {
            case GET -> join("get", field(f, "items"));
            case EQUIP -> join("equip", field(f, "item"));
            case WAIT -> join("wait", field(f, "seconds"));
            case RAW -> normalize(field(f, "line"), null);
            case GOTO -> gotoLine(f);
        };
    }

    private static String gotoLine(Map<String, String> f) {
        String waypoint = field(f, "waypoint");
        if (!waypoint.isEmpty()) {
            return "wp goto " + waypoint;
        }
        String block = field(f, "block");
        if (!block.isEmpty()) {
            return "goto " + block;
        }
        String x = field(f, "x");
        String y = field(f, "y");
        String z = field(f, "z");
        String coords;
        if (x.isEmpty() && z.isEmpty()) {
            coords = y;
        } else if (y.isEmpty()) {
            coords = join(x.isEmpty() ? "~" : x, z.isEmpty() ? "~" : z);
        } else {
            // a missing one stays where the player is, which is what ~ means to baritone's goto
            coords = join(join(x.isEmpty() ? "~" : x, y), z.isEmpty() ? "~" : z);
        }
        return join("goto", coords);
    }

    // the line -> the editor's fields, so a saved step opens the way it was typed
    public static Map<String, String> fieldsOf(RoutineStep step) {
        Map<String, String> out = new LinkedHashMap<>();
        if (step == null) {
            return out;
        }
        String line = step.line() == null ? "" : step.line();
        switch (step.kind() == null ? RoutineStep.Kind.RAW : step.kind()) {
            case GET -> out.put("items", rest(line));
            case EQUIP -> out.put("item", rest(line));
            case WAIT -> out.put("seconds", rest(line));
            case RAW -> out.put("line", line);
            case GOTO -> gotoFields(line, out);
        }
        return out;
    }

    private static void gotoFields(String line, Map<String, String> out) {
        if (isWaypointGoto(line)) {
            List<String> w = words(line);
            out.put("waypoint", String.join(" ", w.subList(2, w.size())));
            return;
        }
        List<String> coords = words(rest(line));
        boolean allCoords = coords.stream().allMatch(c -> COORD.matcher(c).matches());
        // not something the coordinate boxes can hold, so it opens as typed and the checks say what is wrong. that
        // includes a dimension on the end: there's no box for it, and dropping it on the next save would be sneaky
        if (!allCoords || coords.isEmpty() || coords.size() > 3) {
            out.put("block", rest(line));
            return;
        }
        switch (coords.size()) {
            case 1 -> out.put("y", coords.get(0));
            case 2 -> {
                out.put("x", coords.get(0));
                out.put("z", coords.get(1));
            }
            case 3 -> {
                out.put("x", coords.get(0));
                out.put("y", coords.get(1));
                out.put("z", coords.get(2));
            }
            default -> {
            }
        }
    }

    // CustomTasks.json's old spelling: a command and rows of words. get and equip take lists so every row goes on the
    // line, anything else only ever used the first row. same rules #custom always had
    public static String legacyLine(String command, String[][] parameters) {
        StringBuilder line = new StringBuilder(command == null ? "" : command.trim());
        String[][] rows = parameters == null ? new String[0][] : parameters;
        boolean listy = "get".equals(command) || "equip".equals(command);
        for (int i = 0; i < rows.length; i++) {
            if (!listy && i > 0) {
                break;
            }
            if (rows[i] != null && rows[i].length > 0) {
                line.append(' ').append(String.join(" ", rows[i]));
            }
        }
        return line.toString();
    }

    // the legacy fields written next to line, so an older soprano reading the file runs the same thing
    public static String[][] legacyParameters(String line) {
        List<String> w = words(rest(line));
        return w.isEmpty() ? new String[0][] : new String[][]{w.toArray(new String[0])};
    }

    // whitespace and the label's case don't make two lines different
    public static boolean sameLine(String a, String b) {
        String na = normalize(a, null);
        String nb = normalize(b, null);
        return label(na).equals(label(nb)) && rest(na).equals(rest(nb));
    }

    private static String field(Map<String, String> f, String key) {
        String v = f.get(key);
        return v == null ? "" : v.trim().replaceAll("\\s+", " ");
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) {
            return b;
        }
        return b.isEmpty() ? a : a + " " + b;
    }
}
