package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.RoutinesAccess;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Routine;
import adris.altoclef.ui.gui.facade.RoutinesAccess.Step;
import adris.altoclef.ui.gui.facade.RoutinesAccess.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// the commands tab's editor state: one routine as boxes. the facade only ever sees lines, the boxes turn into a line
// through lineFor every time somebody asks, so there is one place that knows the grammar and it isn't here
public final class RoutineDraft {

    // GOTO is one step type with three shapes. the picker lists them as three kinds so a row never needs a second
    // dropdown for "coords or waypoint or block"
    public enum Kind {
        GET("Get", StepType.GET),
        EQUIP("Equip", StepType.EQUIP),
        GOTO_COORDS("Go to coords", StepType.GOTO),
        GOTO_WAYPOINT("Go to waypoint", StepType.GOTO),
        GOTO_BLOCK("Go to block", StepType.GOTO),
        WAIT("Wait", StepType.WAIT),
        RAW("Command", StepType.RAW);

        public final String label;
        public final StepType type;

        Kind(String label, StepType type) {
            this.label = label;
            this.type = type;
        }

        public static List<String> labels() {
            List<String> out = new ArrayList<>();
            for (Kind k : values()) {
                out.add(k.label);
            }
            return out;
        }
    }

    // count stays a string while you type in it, "" is a box somebody just emptied
    public static final class Item {
        public String id;
        public String count;

        public Item(String id, String count) {
            this.id = id;
            this.count = count;
        }
    }

    public static final class Row {
        public Kind kind;
        public final List<Item> items = new ArrayList<>();
        // item, seconds, line, x, y, z, waypoint, block. which ones matter depends on the kind
        public final Map<String, String> f = new LinkedHashMap<>();

        public Row(Kind kind) {
            this.kind = kind;
        }

        public String get(String key) {
            String v = f.get(key);
            return v == null ? "" : v;
        }

        public int itemTotal() {
            int n = 0;
            for (Item it : items) {
                n += countOf(it);
            }
            return n;
        }
    }

    public final String id;
    public final String savedName;
    public String name;
    public String description;
    public final List<Row> rows = new ArrayList<>();
    private String saved;

    private RoutineDraft(String id, String name, String description) {
        this.id = id;
        this.savedName = name;
        this.name = name;
        this.description = description;
    }

    public static RoutineDraft blank() {
        RoutineDraft d = new RoutineDraft(null, "", "");
        d.saved = null;
        return d;
    }

    public static RoutineDraft open(Routine r, RoutinesAccess a) {
        if (r == null) {
            return blank();
        }
        RoutineDraft d = new RoutineDraft(r.id(), nz(r.name()), nz(r.description()));
        if (r.steps() != null) {
            for (Step s : r.steps()) {
                d.rows.add(rowOf(s, a));
            }
        }
        d.saved = d.snap(a);
        return d;
    }

    public boolean isNew() {
        return id == null;
    }

    // a new one is always unsaved, even empty: Run has to save it before there is anything to run
    public boolean dirty(RoutinesAccess a) {
        return saved == null || !saved.equals(snap(a));
    }

    private String snap(RoutinesAccess a) {
        StringBuilder b = new StringBuilder(name).append('\n').append(description);
        for (Step s : steps(a)) {
            b.append('\n').append(s.type()).append(' ').append(s.line());
        }
        return b.toString();
    }

    public List<Step> steps(RoutinesAccess a) {
        List<Step> out = new ArrayList<>();
        for (Row r : rows) {
            String line = a.lineFor(r.kind.type, fieldsFor(r));
            out.add(new Step(r.kind.type, line == null ? "" : line));
        }
        return out;
    }

    public Routine toRoutine(RoutinesAccess a) {
        return new Routine(id, name, description, steps(a));
    }

    // the id the routine runs as. the saved one sticks until the name changes (a hand written or legacy id doesn't
    // have to follow its name), after that it's whatever the store will make of the new name
    public String shownId() {
        return id != null && name.equals(savedName) ? id : idFor(name);
    }

    // same rule as RoutineLines.idFor, which the gui can't see from here. RoutineDraftTest holds the two together
    public static String idFor(String name) {
        if (name == null) {
            return null;
        }
        String id = name.trim().replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_-]", "");
        if (id.length() > 40) {
            id = id.substring(0, 40);
        }
        return id.isEmpty() ? null : id;
    }

    // ----- rows

    public static Row rowOf(Step s, RoutinesAccess a) {
        Map<String, String> f = s == null ? Map.of() : a.fieldsOf(s);
        StepType t = s == null || s.type() == null ? StepType.RAW : s.type();
        Row r = switch (t) {
            case GET -> new Row(Kind.GET);
            case EQUIP -> new Row(Kind.EQUIP);
            case WAIT -> new Row(Kind.WAIT);
            case RAW -> new Row(Kind.RAW);
            case GOTO -> new Row(f.containsKey("waypoint") ? Kind.GOTO_WAYPOINT : f.containsKey("block") ? Kind.GOTO_BLOCK : Kind.GOTO_COORDS);
        };
        if (r.kind == Kind.GET) {
            r.items.addAll(parseItems(f.get("items")));
        } else {
            r.f.putAll(f);
        }
        return r;
    }

    public static Row blankRow(Kind kind) {
        Row r = new Row(kind);
        if (kind == Kind.WAIT) {
            r.f.put("seconds", "5");
        }
        return r;
    }

    public void add(Kind kind) {
        rows.add(blankRow(kind));
    }

    // a new kind starts from empty boxes. going between the three gotos would keep half a coordinate in a block box
    public void setKind(int i, Kind kind) {
        if (i >= 0 && i < rows.size() && rows.get(i).kind != kind) {
            rows.set(i, blankRow(kind));
        }
    }

    public void move(int i, int dir) {
        int j = i + dir;
        if (i < 0 || j < 0 || i >= rows.size() || j >= rows.size()) {
            return;
        }
        rows.add(j, rows.remove(i));
    }

    public void remove(int i) {
        if (i >= 0 && i < rows.size()) {
            rows.remove(i);
        }
    }

    // enter in a get step's add box. the same item twice is one more of it, like the get tab's basket
    public static void addItem(Row r, String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.isEmpty()) {
            return;
        }
        for (Item it : r.items) {
            if (it.id.equals(id)) {
                it.count = String.valueOf(Math.min(9999, countOf(it) + 1));
                return;
            }
        }
        r.items.add(new Item(id, "1"));
    }

    public static int countOf(Item it) {
        try {
            return Math.max(0, Integer.parseInt(it.count.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ----- boxes <-> fields

    public static Map<String, String> fieldsFor(Row r) {
        Map<String, String> out = new LinkedHashMap<>();
        switch (r.kind) {
            case GET -> {
                List<String> parts = new ArrayList<>();
                for (Item it : r.items) {
                    // one of a thing is just the thing. "get diamond_sword" reads better in the strip than "... 1",
                    // and get takes a missing count as one anyway
                    String n = it.count == null ? "" : it.count.trim();
                    parts.add(n.isEmpty() || n.equals("1") ? it.id : it.id + " " + n);
                }
                out.put("items", String.join(", ", parts));
            }
            case EQUIP -> out.put("item", r.get("item"));
            case WAIT -> out.put("seconds", r.get("seconds"));
            case RAW -> out.put("line", r.get("line"));
            case GOTO_COORDS -> {
                out.put("x", r.get("x"));
                out.put("y", r.get("y"));
                out.put("z", r.get("z"));
            }
            case GOTO_WAYPOINT -> out.put("waypoint", r.get("waypoint"));
            case GOTO_BLOCK -> out.put("block", r.get("block"));
        }
        return out;
    }

    // "diamond_sword, diamond_chestplate 2" or the old "diamond_sword 1 diamond_chestplate 2": a name, then maybe a count
    public static List<Item> parseItems(String s) {
        List<Item> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String[] tokens = s.replace('[', ' ').replace(']', ' ').replace(',', ' ').trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.isEmpty()) {
                continue;
            }
            String n = "1";
            if (i + 1 < tokens.length && tokens[i + 1].matches("\\d+")) {
                n = tokens[++i];
            }
            out.add(new Item(t.toLowerCase(Locale.ROOT), n));
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
