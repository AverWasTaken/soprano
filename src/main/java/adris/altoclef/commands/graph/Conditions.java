package adris.altoclef.commands.graph;

import java.util.List;
import java.util.stream.Collectors;

// asking a condition. all the game reads sit behind World so this stays testable with a pretend one
public final class Conditions {

    public static final int MAX_DEPTH = 3;

    // the question can't be answered right now (no player, day and night in the nether). the run fails on this, a
    // made up answer would quietly send the bot down the wrong wire
    public static final class Unreadable extends RuntimeException {
        public Unreadable(String why) {
            super(why);
        }
    }

    public interface World {
        // the trackers only refresh once a tick. an If right after a task ended would see the old inventory
        void freshen();

        int itemCount(String item);

        double health();

        int hunger();

        boolean isDay();

        // overworld, nether or end
        String dimension();

        int freeSlots();

        double x();

        double y();

        double z();

        boolean nearMob(String mob, int radius);

        boolean nearBlock(String block, int radius);
    }

    // what only the run itself knows, the walker of the graph answers these
    public interface RunFacts {
        boolean lastActionFailed();

        // the innermost Repeat's lap, Unreadable outside of one
        int laps();

        RunFacts NONE = new RunFacts() {
            @Override
            public boolean lastActionFailed() {
                return false;
            }

            @Override
            public int laps() {
                throw new Unreadable("the loop counter only means something inside a Repeat");
            }
        };
    }

    private Conditions() {
    }

    // what /time set day and /time set night call it
    public static boolean isDayTime(long clock) {
        long time = Math.floorMod(clock, 24000L);
        return time < 13000 || time >= 23000;
    }

    public static boolean eval(Cond c, World w) {
        return eval(c, w, RunFacts.NONE);
    }

    public static boolean eval(Cond c, World w, RunFacts run) {
        return switch (c.op()) {
            case ALL -> c.children().stream().allMatch(k -> eval(k, w, run));
            case ANY -> c.children().stream().anyMatch(k -> eval(k, w, run));
            case NOT -> !eval(c.children().get(0), w, run);
            case LEAF -> leaf(c, w, run);
        };
    }

    // does a leaf of this kind sit anywhere in here
    public static boolean uses(Cond c, String kind) {
        return c != null && (kind.equals(c.kind()) || c.children().stream().anyMatch(k -> uses(k, kind)));
    }

    private static boolean leaf(Cond c, World w, RunFacts run) {
        return switch (c.kind() == null ? "" : c.kind()) {
            case "has_item" -> compare(w.itemCount(c.arg("item")), c);
            case "health" -> compare(w.health(), c);
            case "hunger" -> compare(w.hunger(), c);
            case "time" -> w.isDay() == "day".equals(c.arg("value"));
            case "dimension" -> w.dimension().equals(c.arg("value"));
            case "free_slots" -> compare(w.freeSlots(), c);
            case "near_pos" -> nearPos(c, w);
            case "near_mob" -> w.nearMob(c.arg("mob"), number(c, "r"));
            case "near_block" -> w.nearBlock(c.arg("block"), number(c, "r"));
            case "last_action_failed" -> run.lastActionFailed();
            case "loop_counter" -> compare(run.laps(), c);
            default -> throw new Unreadable("\"" + c.kind() + "\" is not a condition");
        };
    }

    private static boolean compare(double have, Cond c) {
        int v = number(c, "value");
        return switch (c.arg("op")) {
            case "<=" -> have <= v;
            case "=" -> have == v;
            case "<" -> have < v;
            case ">" -> have > v;
            default -> have >= v;
        };
    }

    private static boolean nearPos(Cond c, World w) {
        double dx = w.x() - number(c, "x");
        double dz = w.z() - number(c, "z");
        // no y means anywhere in that column, same as ~ on a goto
        double dy = c.arg("y").isBlank() ? 0 : w.y() - number(c, "y");
        double r = number(c, "r");
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private static int number(Cond c, String key) {
        try {
            return Integer.parseInt(c.arg(key).trim());
        } catch (NumberFormatException e) {
            throw new Unreadable(c.kind() + " needs a number for " + key);
        }
    }

    // the sentence a node card shows
    public static String text(Cond c) {
        if (c == null) {
            return "no condition yet";
        }
        return switch (c.op()) {
            case ALL -> join(c.children(), " and ");
            case ANY -> join(c.children(), " or ");
            case NOT -> c.children().isEmpty() ? "not ?" : "not (" + text(c.children().get(0)) + ")";
            case LEAF -> leafText(c);
        };
    }

    private static String join(List<Cond> of, String with) {
        return of.isEmpty() ? "no condition yet" : of.stream().map(Conditions::text).collect(Collectors.joining(with));
    }

    private static String leafText(Cond c) {
        String kind = c.kind() == null ? "" : c.kind();
        return switch (kind) {
            case "has_item" -> "has " + show(c, "item") + " " + c.arg("op") + " " + show(c, "value");
            case "health", "hunger" -> kind + " " + c.arg("op") + " " + show(c, "value");
            case "time" -> "day".equals(c.arg("value")) ? "day" : "night";
            case "dimension" -> "in the " + show(c, "value");
            // the preset's own name, it reads better on a card than the numbers it's made of
            case "free_slots" -> "=".equals(c.arg("op")) && "0".equals(c.arg("value")) ? "inventory full" : "free slots " + c.arg("op") + " " + show(c, "value");
            case "near_pos" -> "at " + show(c, "x") + " " + (c.arg("y").isBlank() ? "~" : c.arg("y")) + " " + show(c, "z") + " within " + show(c, "r");
            case "near_mob" -> show(c, "mob") + " within " + show(c, "r");
            case "near_block" -> show(c, "block") + " within " + show(c, "r");
            case "last_action_failed" -> "last action failed";
            case "loop_counter" -> "loop counter " + c.arg("op") + " " + show(c, "value");
            default -> "nothing yet";
        };
    }

    private static String show(Cond c, String key) {
        return c.arg(key).isBlank() ? "?" : c.arg(key);
    }
}
