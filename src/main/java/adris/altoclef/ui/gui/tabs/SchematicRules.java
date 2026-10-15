package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// the words and numbers of the schematics tab, kept away from the drawing so a test can read them as strings
final class SchematicRules {

    private SchematicRules() {
    }

    // rows whose path has the query in it, case insensitive. the path and not the name, so "villages" finds the farm
    static List<Row> filter(List<Row> rows, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return rows;
        }
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.relativePath().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(r);
            }
        }
        return out;
    }

    // "4 schematics", "2 of 4 schematics", and how many of them didn't parse on the end
    static String count(int shown, int total, int broken) {
        String noun = total == 1 ? " schematic" : " schematics";
        String head = shown == total ? total + noun : shown + " of " + total + noun;
        return broken == 0 ? head : head + " · " + broken + " didn't parse";
    }

    static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    // "47 KB", "120 B", "1.2 MB": the file size is a hint about how long a parse takes, not a number to compare
    static String fileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    // the small line under the name in the pane: format, size and the file size, or just what we know of a broken one
    static String meta(Row r) {
        return r.ok() ? r.formatText() + " · " + r.sizeText() + " · " + fileSize(r.fileSize())
                : r.formatText() + " · " + fileSize(r.fileSize());
    }

    // the list row's second line
    static String sub(Row r) {
        return r.ok() ? r.formatText() + " · " + r.sizeText() : "Didn't parse";
    }

    // "3,557 blocks · 7 kinds", the shortfall is drawn separately in red by summaryTail
    static String summaryHead(Materials m) {
        int kinds = m.items().size();
        return num(m.totalNeeded()) + (m.totalNeeded() == 1 ? " block" : " blocks") + " · " + kinds + (kinds == 1 ? " kind" : " kinds");
    }

    // " · 3,490 short", or " · all in your inventory" when there is nothing to fetch
    static String summaryTail(Materials m) {
        long missing = m.totalMissing();
        return missing > 0 ? " · " + num(missing) + " short" : " · all in your inventory";
    }

    // the line next to the build button, "" when there is nothing to warn about
    static String warning(Materials m) {
        long missing = m.totalMissing();
        if (missing > 0) {
            return num(missing) + " short. It pauses when it runs out.";
        }
        if (!m.cantPlace().isEmpty()) {
            return "Some blocks can't be placed, those spots stay empty.";
        }
        return "";
    }

    // "40 / 212" for an item, just the count for the folded kinds
    static String counts(Line l) {
        return l.kind() == adris.altoclef.ui.gui.facade.SchematicsAccess.Kind.ITEM ? num(l.have()) + " / " + num(l.needed()) : num(l.needed());
    }

    // 0..1 of the stack the player holds
    static float coverage(Line l) {
        if (l.needed() <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, l.have() / (float) l.needed()));
    }
}
