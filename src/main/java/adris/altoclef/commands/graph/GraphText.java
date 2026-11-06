package adris.altoclef.commands.graph;

// one line about what a node will do. the card and the status strip both use it
public final class GraphText {

    private GraphText() {
    }

    public static String summary(RoutineGraph.Node n) {
        return switch (n.type()) {
            case START -> "When you press Run";
            case GET, EQUIP, GOTO, RAW -> n.param("line").isBlank() ? "No command yet" : n.param("line");
            case MINE -> orWhat(n.param("n")) + " " + orWhat(n.param("block"));
            case CRAFT -> orWhat(n.param("n")) + " " + orWhat(n.param("item"));
            case EAT -> n.param("hunger").isBlank() ? "Until full" : "Until hunger " + n.param("hunger");
            case FOLLOW -> orWhat(n.param("player")) + " for " + orWhat(n.param("secs")) + " seconds";
            case SUB -> n.param("routine").isBlank() ? "No routine yet" : n.param("routine");
            case IF -> Conditions.text(n.cond());
            case REPEAT -> switch (n.param("mode")) {
                case RoutineGraph.MODE_TIMES -> orWhat(n.param("n")) + " times";
                case RoutineGraph.MODE_UNTIL -> "Until " + Conditions.text(n.cond());
                default -> "Forever, until a Stop";
            };
            case WAIT -> RoutineGraph.MODE_UNTIL.equals(n.param("mode"))
                    ? "Until " + Conditions.text(n.cond()) + ", " + orWhat(n.param("timeout")) + " s max"
                    : orWhat(n.param("secs")) + " seconds";
            case STOP -> "false".equals(n.param("ok")) ? "Ends as failed" : "Ends the run";
        };
    }

    private static String orWhat(String v) {
        return v.isBlank() ? "?" : v;
    }
}
