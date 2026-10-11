package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.WaypointEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// what the waypoint list shows and in which order. pure, so the tab only has to draw it
final class WaypointRules {

    private WaypointRules() {
    }

    // -1 when nobody is standing anywhere
    static double distance(WaypointEntry w, WorldAccess.Position p) {
        if (p == null) {
            return -1;
        }
        double dx = w.x() + 0.5 - p.x();
        double dy = w.y() - p.y();
        double dz = w.z() + 0.5 - p.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    static boolean matches(WaypointEntry w, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() || lower(w.name()).contains(q) || lower(w.tag()).contains(q);
    }

    static List<WaypointEntry> arrange(List<WaypointEntry> all, String query, boolean byName, WorldAccess.Position p) {
        List<WaypointEntry> out = new ArrayList<>();
        for (WaypointEntry w : all) {
            if (matches(w, query)) {
                out.add(w);
            }
        }
        Comparator<WaypointEntry> name = Comparator.comparing((WaypointEntry w) -> lower(w.name()))
                .thenComparing(w -> lower(w.tag()));
        if (byName || p == null) {
            out.sort(name);
        } else {
            out.sort(Comparator.comparingDouble((WaypointEntry w) -> distance(w, p)).thenComparing(name));
        }
        return out;
    }

    static String coords(WaypointEntry w) {
        return w.x() + " " + w.y() + " " + w.z();
    }

    static String count(int shown, int total) {
        String noun = total == 1 ? " waypoint" : " waypoints";
        return shown == total ? total + noun : shown + " of " + total + noun;
    }
}
