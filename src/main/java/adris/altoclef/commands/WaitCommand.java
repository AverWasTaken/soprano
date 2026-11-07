package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.misc.TimedWaitTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import java.util.List;

// "wait 5": a routine's pause between steps, and a ";" line's too. a task and not a sleep, so stop ends it
public class WaitCommand extends AltoClefCommand {

    public WaitCommand(IBaritone baritone) {
        super(baritone, "wait");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        String raw = args.getString();
        double seconds;
        try {
            seconds = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new CommandInvalidStateException("\"" + raw + "\" is not a number of seconds");
        }
        if (!(seconds > 0) || seconds > RoutineLines.MAX_WAIT_SECONDS) {
            throw new CommandInvalidStateException("wait between 0 and " + (int) RoutineLines.MAX_WAIT_SECONDS + " seconds");
        }
        startTask(mod, new TimedWaitTask(seconds));
    }

    @Override
    public String getShortDesc() {
        return "Wait a few seconds";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "The wait command stands still for that many seconds and then counts as done, so in a routine or a ; line the next step starts after it. Stop ends it early.",
                "",
                "Usage:",
                "> wait <seconds> - Wait that long, like wait 5 or wait 0.5."
        );
    }
}
