package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.RoutinesAccess.CondPreset;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondSpec;
import adris.altoclef.ui.gui.facade.RoutinesAccess.CondTree;
import adris.altoclef.ui.gui.facade.RoutinesAccess.FieldSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// a condition as the inspector's rows: a flat list, one join between all of them, and a not per row. a tree that has
// nesting past that can't be shown this way, so fits goes false and the inspector shows it read only. silently flattening
// somebody's hand written file would change what it means
public final class CondDraft {

    public static final String ALL = "all";
    public static final String ANY = "any";
    private static final String NOT = "not";
    private static final String LEAF = "leaf";

    public static final class Row {
        public String kind;
        public boolean negate;
        public final Map<String, String> args = new LinkedHashMap<>();

        public Row(String kind) {
            this.kind = kind;
        }

        public String arg(String key) {
            String v = args.get(key);
            return v == null ? "" : v;
        }
    }

    public String join = ALL;
    public final List<Row> rows = new ArrayList<>();
    public final boolean fits;

    private CondDraft(boolean fits) {
        this.fits = fits;
    }

    public static CondDraft of(CondTree t) {
        if (t == null) {
            return new CondDraft(true);
        }
        if (isRow(t)) {
            CondDraft d = new CondDraft(true);
            d.rows.add(rowOf(t));
            return d;
        }
        if (!(ALL.equals(t.op()) || ANY.equals(t.op())) || t.children() == null) {
            return new CondDraft(false);
        }
        for (CondTree k : t.children()) {
            if (!isRow(k)) {
                return new CondDraft(false);
            }
        }
        CondDraft d = new CondDraft(true);
        d.join = t.op();
        for (CondTree k : t.children()) {
            d.rows.add(rowOf(k));
        }
        return d;
    }

    private static boolean isLeaf(CondTree t) {
        return LEAF.equals(t.op());
    }

    private static boolean isRow(CondTree t) {
        return isLeaf(t) || NOT.equals(t.op()) && t.children() != null && t.children().size() == 1 && isLeaf(t.children().get(0));
    }

    private static Row rowOf(CondTree t) {
        boolean negate = !isLeaf(t);
        CondTree leaf = negate ? t.children().get(0) : t;
        Row r = new Row(leaf.kind() == null ? "" : leaf.kind());
        r.negate = negate;
        if (leaf.args() != null) {
            r.args.putAll(leaf.args());
        }
        return r;
    }

    // no rows is no condition, which is what the validator's "Add a condition" wants to see
    public CondTree toTree() {
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() == 1) {
            return treeOf(rows.get(0));
        }
        List<CondTree> kids = new ArrayList<>();
        for (Row r : rows) {
            kids.add(treeOf(r));
        }
        return new CondTree(join, null, Map.of(), kids);
    }

    private static CondTree treeOf(Row r) {
        CondTree leaf = leaf(r.kind, r.args);
        return r.negate ? new CondTree(NOT, null, Map.of(), List.of(leaf)) : leaf;
    }

    public static CondTree leaf(String kind, Map<String, String> args) {
        return new CondTree(LEAF, kind, new LinkedHashMap<>(args), List.of());
    }

    // a fresh row that already says something sensible, so you only fill in what's yours. numbers a person would pick
    // first are cosmetic, a kind nobody's heard of gets its lower limit
    public static Row blankRow(CondSpec spec) {
        Row r = new Row(spec.kind());
        for (FieldSpec f : spec.fields()) {
            String v = blankArg(spec.kind(), f);
            if (!v.isEmpty()) {
                r.args.put(f.key(), v);
            }
        }
        return r;
    }

    private static String blankArg(String kind, FieldSpec f) {
        return switch (f.kind()) {
            case "OP" -> f.choices() != null && !f.choices().isEmpty() ? f.choices().get(0) : ">=";
            case "CHOICE" -> f.choices() != null && !f.choices().isEmpty() ? f.choices().get(0) : "";
            case "INT" -> blankInt(kind, f);
            default -> "";
        };
    }

    private static String blankInt(String kind, FieldSpec f) {
        if ("r".equals(f.key())) {
            return String.valueOf(Math.min(16, Math.max(f.min(), f.max())));
        }
        if ("health".equals(kind) || "hunger".equals(kind)) {
            return "10";
        }
        return String.valueOf(Math.max(1, f.min()));
    }

    public static CondTree blankTree(List<CondSpec> specs) {
        return specs == null || specs.isEmpty() ? null : treeOf(blankRow(specs.get(0)));
    }

    // what the picker should say for this row: "Inventory full" reads better than the free_slots = 0 it's made of. -1 = no preset
    public static int presetOf(Row r, List<CondPreset> presets) {
        for (int i = 0; i < presets.size(); i++) {
            CondPreset p = presets.get(i);
            if (Objects.equals(p.kind(), r.kind) && p.args().equals(r.args)) {
                return i;
            }
        }
        return -1;
    }
}
