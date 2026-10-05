/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.CoverWithBlocksTask;
import adris.altoclef.tasks.construction.CoverWithSandTask;
import adris.altoclef.tasks.entity.HeroTask;
import adris.altoclef.tasks.entity.SelfCareTask;
import adris.altoclef.tasks.movement.FollowPlayerTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.speedrun.MarvionBeatMinecraftTask;
import adris.altoclef.tasksystem.Task;
import baritone.altoclef.AltoClefBridge;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.exception.CommandException;
import baritone.api.command.manager.ICommandManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

// altoclef's commands as soprano commands, plus the one way altoclef itself (butler, idle command, death command,
// custom tasks) runs a command line. everything goes through the primary baritone's command manager, there is no
// second command system anymore
public final class AltoClefCommands {

    // every name and alias we registered, for the butler allow-list
    private static final Set<String> OWNED = new HashSet<>();
    // butler lines that altoclef answers itself instead of asking the manager, see runOne
    private static final Set<String> BUTLER_INTERNAL = Set.of("goto", "follow", "stop", "cancel");
    // ours, but not something a whisper gets to run. punk kills a player and gamma is the owner's own screen. subtracted
    // from everything else, so a new alias of these has to be added here too
    private static final Set<String> BUTLER_DENIED = Set.of("punk", "gamma", "setgamma", "set_gamma");

    private static final Runnable NOOP = () -> {
    };

    // bumped by #stop. a "a;b;c" line that sees a different number than it started with was stopped on purpose, and
    // starting b after the user hit stop would be rude
    private static int generation;
    // the line being run right now, null outside of execute. main thread only, like everything that runs commands
    private static Dispatch current;

    private AltoClefCommands() {
    }

    private static final class Dispatch {
        final Runnable onFinish;
        boolean taken;
        Exception failure;

        Dispatch(Runnable onFinish) {
            this.onFinish = onFinish;
        }
    }

    public static List<ICommand> createAll(IBaritone baritone) {
        return List.of(
                new GetCommand(baritone),
                new GiveCommand(baritone),
                new EquipCommand(baritone),
                new DepositCommand(baritone),
                new StashCommand(baritone),
                new FoodCommand(baritone),
                new MeatCommand(baritone),
                new PunkCommand(baritone),
                new CoordsCommand(baritone),
                new InventoryCommand(baritone),
                new ListCommand(baritone),
                new LocateStructureCommand(baritone),
                new StatusCommand(baritone),
                new SetGammaCommand(baritone),
                new ReloadSettingsCommand(baritone),
                new CustomCommand(baritone),
                new TaskCommand(baritone, new String[]{"gamer"}, "Beats the game",
                        BeatMinecraft2Task::new,
                        "Starts altoclef's full speedrun: gear up, enter the nether, find the stronghold and kill the dragon.",
                        "It takes a long while and it will eat, fight and respawn on its own. Use stop to call it off."),
                new TaskCommand(baritone, new String[]{"marvion"}, "Beats the game (Marvion version)",
                        MarvionBeatMinecraftTask::new,
                        "The same goal as gamer, but with Marvion's strategy for it.",
                        "Use stop to call it off."),
                new TaskCommand(baritone, new String[]{"hero"}, "Kill all hostile mobs",
                        HeroTask::new,
                        "Hunts down every hostile mob it can find and keeps going until stopped."),
                new TaskCommand(baritone, new String[]{"selfcare"}, "Care for self (not finished)",
                        SelfCareTask::new,
                        "Looks after the bot: eating, healing and generally not dying. Altoclef marks this one as unfinished."),
                new TaskCommand(baritone, new String[]{"idle"}, "Stand still",
                        IdleTask::new,
                        "Does nothing, but holds the bot in place with altoclef's survival chains still running."),
                new TaskCommand(baritone, new String[]{"coverwithblocks"}, "Cover nether lava with blocks",
                        CoverWithBlocksTask::new,
                        "Walks around the nether placing blocks on top of any lava it can see."),
                new TaskCommand(baritone, new String[]{"coverwithsand"}, "Cover nether lava with sand",
                        CoverWithSandTask::new,
                        "Collects sand and uses it to cover any nether lava it can see.")
        );
    }

    // the primary baritone owns the command manager that the chat control talks to, so that is where we live. runs once
    // at startup, before altoclef itself exists, which is why none of the commands touch it until they are executed
    public static void register(IBaritone primary) {
        ICommandManager manager = primary.getCommandManager();
        for (ICommand command : createAll(primary)) {
            // the registry hands out the newest command first, so a clashing name would quietly steal a stock command
            boolean clash = command.getNames().stream().anyMatch(n -> manager.getCommand(n) != null);
            if (clash) {
                System.err.println("altoclef command " + command.getNames() + " clashes with an existing command, not registering it");
                continue;
            }
            if (manager.getRegistry().register(command)) {
                OWNED.addAll(command.getNames());
            }
        }
    }

    public static String prefix() {
        return BaritoneAPI.getSettings().prefix.value;
    }

    // butlers get altoclef's commands and nothing else. #set could rewrite settings and #build could do whatever, from
    // anyone who can whisper at you. goto/follow/stop are soprano's names but the butler gets altoclef's versions of them
    public static boolean isButlerAllowed(String label) {
        return isButlerAllowed(label, OWNED);
    }

    // the owned set is passed in so a test can use the real command names without registering them on a game
    static boolean isButlerAllowed(String label, Set<String> owned) {
        String l = label.toLowerCase(Locale.ROOT);
        if (BUTLER_DENIED.contains(l)) {
            return false;
        }
        return owned.contains(l) || BUTLER_INTERNAL.contains(l);
    }

    // called when somebody hits stop, see AltoClefBridge#cancelUserTask
    public static void abortSequences() {
        generation++;
    }

    /**
     * Runs a command line the way the old altoclef executor did: "a ; b" runs b once a's task is done.
     *
     * @param butler true when the text came from somebody else's whisper, which narrows it to {@link #isButlerAllowed}
     */
    public static void execute(String line, Runnable onFinish, Consumer<String> onError, boolean butler) {
        String text = line.trim();
        String prefix = prefix();
        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length());
        }
        List<String> parts = new ArrayList<>();
        for (String p : text.split(";")) {
            if (!p.isBlank()) {
                parts.add(p.trim());
            }
        }
        runParts(parts, 0, generation, onFinish, onError, butler);
    }

    // idle/death/custom lines come from the user's own config, so they get everything, and a bad one is a warning
    public static void executeTrusted(String line) {
        execute(line, NOOP, Debug::logWarning, false);
    }

    private static void runParts(List<String> parts, int index, int gen, Runnable onFinish, Consumer<String> onError, boolean butler) {
        if (index >= parts.size() || gen != generation) {
            onFinish.run();
            return;
        }
        runOne(parts.get(index), butler, () -> runParts(parts, index + 1, gen, onFinish, onError, butler), onError);
    }

    private static void runOne(String part, boolean butler, Runnable next, Consumer<String> onError) {
        String[] split = part.split("\\s+", 2);
        String label = split[0].toLowerCase(Locale.ROOT);
        String rest = split.length > 1 ? split[1].trim() : "";
        if (butler && !isButlerAllowed(label)) {
            onError.accept("\"" + label + "\" is not something the butler is allowed to run");
            return;
        }
        if (runAltoClefVersion(label, rest, butler, next, onError)) {
            return;
        }
        ICommandManager manager = BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager();
        if (manager.getCommand(label) == null) {
            onError.accept("command " + label + " does not exist");
            return;
        }
        Dispatch d = new Dispatch(next);
        Dispatch previous = current;
        current = d;
        try {
            manager.execute(part);
        } finally {
            current = previous;
        }
        if (d.failure != null) {
            onError.accept(String.valueOf(d.failure.getMessage()));
        } else if (!d.taken) {
            // nothing started a task and nothing complained (unknown to us, or finished on the spot), so we are done
            next.run();
        }
    }

    // soprano's goto/follow/stop return the moment they hand things to baritone, but a butler asking for them wants the
    // old behaviour: a user task that finishes when the job does, and the idle command's "goto base" has to behave the
    // same way or the next idle task would start while the bot is still on its way
    private static boolean runAltoClefVersion(String label, String rest, boolean butler, Runnable next, Consumer<String> onError) {
        List<String> words = rest.isEmpty() ? List.of() : Arrays.asList(rest.split("\\s+"));
        switch (label) {
            case "goto": {
                Task task;
                try {
                    task = CrossDimensionGoto.parse(words);
                } catch (CrossDimensionGoto.ParseException e) {
                    if (butler) {
                        onError.accept("goto: " + e.getMessage());
                        return true;
                    }
                    // not coordinates and a dimension, so it is soprano's goto (block names and ~)
                    return false;
                }
                startTask(task, next, onError);
                return true;
            }
            case "follow": {
                if (!butler) {
                    return false;
                }
                String user = !words.isEmpty() ? words.get(0) : null;
                try {
                    if (user == null) {
                        AltoClef mod = AltoClefBridge.require();
                        user = mod.getButler().hasCurrentUser() ? mod.getButler().getCurrentUser() : null;
                    }
                } catch (CommandException e) {
                    onError.accept(e.getMessage());
                    return true;
                }
                if (user == null) {
                    onError.accept("follow needs a player name when there is no butler user");
                    return true;
                }
                startTask(new FollowPlayerTask(user), next, onError);
                return true;
            }
            case "stop":
            case "cancel": {
                if (!butler) {
                    return false;
                }
                AltoClefBridge.cancelUserTask();
                next.run();
                return true;
            }
            default:
                return false;
        }
    }

    private static void startTask(Task task, Runnable next, Consumer<String> onError) {
        try {
            AltoClefBridge.require().runUserTask(task, next);
        } catch (CommandException e) {
            onError.accept(e.getMessage());
        }
    }

    // what AltoClefCommand uses to tell the line that is running about its task. a command that never calls either
    // of these gets treated as finished once it returns, see runOne

    static Runnable takeFinish() {
        Dispatch d = current;
        if (d == null || d.taken) {
            return NOOP;
        }
        d.taken = true;
        return d.onFinish;
    }

    static void finishNow() {
        takeFinish().run();
    }

    static void failed(Exception e) {
        if (current != null) {
            current.failure = e;
        }
    }
}
