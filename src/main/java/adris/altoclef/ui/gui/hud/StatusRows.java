package adris.altoclef.ui.gui.hud;

import java.util.ArrayList;
import java.util.List;

// the status panel's lines from a task chain. this is CommandStatusOverlay's old line building moved out of the
// minecraft class so it can be tested: header, the chain top to bottom, plumbing hidden unless it's the leaf, twins
// merged. the overlay turns its tasks into TaskLines and hands them over, the panel does the rest
public final class StatusRows {

    // one task as the hud sees it. the debug pair is what the developer view (altoHudDetailed) shows
    public record TaskLine(String hudName, String hudState, String debugName, String debugState, boolean plumbing) {
    }

    private record Entry(String name, String state, String iconName, boolean leaf) {
    }

    private StatusRows() {
    }

    // header is the chain's name (the debug one in the detailed view), headerHud its hud name for the icon, null for no
    // header. empty tasks is an empty list: nothing running, nothing to say
    public static List<StatusPanel.Row> build(String header, String headerHud, List<TaskLine> tasks, boolean detailed) {
        List<StatusPanel.Row> rows = new ArrayList<>();
        if (tasks == null || tasks.isEmpty()) {
            return rows;
        }
        // the developer view is the old hud exactly: class names, raw states, nothing hidden
        List<Entry> entries = new ArrayList<>();
        if (header != null) {
            entries.add(new Entry(header, "", headerHud == null ? header : headerHud, false));
        }
        int n = tasks.size();
        for (int i = 0; i < n; i++) {
            TaskLine t = tasks.get(i);
            boolean leaf = i == n - 1;
            if (!detailed && !leaf && t.plumbing()) {
                // slot shuffling under a craft is not something the player wants a line for, unless it's what's
                // happening right now
                continue;
            }
            Entry e = new Entry(nz(detailed ? t.debugName() : t.hudName()), nz(detailed ? t.debugState() : t.hudState()), nz(t.hudName()), leaf);
            Entry prev = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            if (!detailed && prev != null && prev.name.equals(e.name)) {
                // "Crafting 4 Oak Planks" twice (the 2x2 task and the generic one under it) is one line. the leaf
                // flag and a state move up so the line still reads as the active one
                entries.set(entries.size() - 1, new Entry(prev.name, prev.state.isEmpty() ? e.state : prev.state, prev.iconName, prev.leaf || e.leaf));
                continue;
            }
            entries.add(e);
        }
        int last = entries.size() - 1;
        for (int i = 0; i <= last; i++) {
            Entry e = entries.get(i);
            StatusPanel.Role role;
            if (i == 0) {
                // the chain name, or with no chain name the root task: what you asked for is the title
                role = last == 0 ? StatusPanel.Role.ONLY : StatusPanel.Role.ROOT;
            } else {
                role = e.leaf || i == last ? StatusPanel.Role.LEAF : StatusPanel.Role.MID;
            }
            rows.add(new StatusPanel.Row(e.name, e.state, role, TaskIcons.forName(e.iconName)));
        }
        return rows;
    }

    // how many rows at the top a fold keeps: the chain name and the root task, or just the root task
    public static int keepTop(String header) {
        return header == null ? 1 : 2;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
