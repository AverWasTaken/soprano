package adris.altoclef.commands;

// one step of a saved routine: what kind of step it is and the command line it runs (no prefix). the kind decides when
// the step counts as done, the line is what actually happens
public record RoutineStep(Kind kind, String line) {

    public enum Kind {
        GET,
        EQUIP,
        GOTO,
        WAIT,
        RAW
    }
}
