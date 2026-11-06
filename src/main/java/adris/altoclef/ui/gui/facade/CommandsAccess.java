package adris.altoclef.ui.gui.facade;

// a way to run a line, for the command bar
public interface CommandsAccess {

    // runs "goto 1 2 3" (no prefix). message holds what the command printed if that could be captured, else a
    // short confirmation. a thrown CommandException becomes fail(its message)
    Result run(String line);
}
