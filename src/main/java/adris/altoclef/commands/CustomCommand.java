package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

// runs a named list of commands from configs/CustomTasks.json. it used to exist without ever being registered, now it is
// just another command
public class CustomCommand extends AltoClefCommand {

    public CustomCommand(IBaritone baritone) {
        super(baritone, "custom", "custom2");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        String name = args.getString();
        // the store builds the lines (line, or the old command + parameters), the same ones the commands tab runs
        List<String> lines = CommandsStore.get().customLines(name);
        if (lines == null) {
            CommandsStore.Snapshot file = CommandsStore.get().snapshot();
            throw new CommandInvalidStateException("no custom task called \"" + name + "\" in CustomTasks.json"
                    + (file.ok() ? "" : " (" + file.error() + ")"));
        }
        // the lines are the owner's own config, so they run with everything, unless a whisper asked for them: then they
        // stay on the same leash as the whisper (a task list with a punk in it must not be a way around the deny list).
        // when the last one ends so does this command, which is what a butler is waiting on
        boolean butler = AltoClefCommands.currentIsButler();
        if (butler) {
            // refuse the whole list up front, half of a list that then dies on a denied line is worse than none
            for (String line : lines) {
                String first = line.trim().split("\\s+", 2)[0];
                // execute skips empty lines too
                if (!first.isEmpty() && !AltoClefCommands.isButlerAllowed(first)) {
                    throw new CommandInvalidStateException("custom " + name + " has \"" + first + "\" in it, which a whisper is not allowed to run");
                }
            }
            // a line that fails later has to end the command, or the whisperer waits on a task that never comes
            Runnable taken = AltoClefCommands.takeFinish();
            // once only, whatever order the list's own finish and an error turn up in
            boolean[] ended = {false};
            Runnable finish = () -> {
                if (!ended[0]) {
                    ended[0] = true;
                    taken.run();
                }
            };
            AltoClefCommands.execute(String.join(";", lines), finish, error -> {
                mod.logWarning("custom " + name + ": " + error);
                finish.run();
            }, true);
            return;
        }
        AltoClefCommands.noteOwnerTask(mod);
        AltoClefCommands.execute(String.join(";", lines), AltoClefCommands.takeFinish(), Debug::logWarning, false);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            List<String> names = CommandsStore.get().customIds();
            return new TabCompleteHelper().append(names.stream()).filterPrefix(args.getString()).sortAlphabetically().stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Run a custom task list";
    }

    @Override
    public List<String> getLongDesc() {
        List<String> lines = new ArrayList<>(Arrays.asList(
                "The custom command runs one of the task lists you wrote in the altoclef config folder, in configs/CustomTasks.json. Each list is a few commands that run one after the other, each waiting for the one before it.",
                "",
                "Usage:",
                "> custom <name> - Run that task list."
        ));
        List<StoredRoutine> entries = CommandsStore.get().list();
        if (!entries.isEmpty()) {
            lines.add("");
            lines.add("Your task lists:");
            for (StoredRoutine entry : entries) {
                lines.add("> custom " + entry.id() + (entry.description().isEmpty() ? "" : " - " + entry.description()));
            }
        }
        return lines;
    }
}
