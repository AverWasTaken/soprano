/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.behavior.RouteMemory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class RoutesCommand extends Command {

    public RoutesCommand(IBaritone baritone) {
        super(baritone, "routes");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase() : "status";
        args.requireMax(0);
        switch (action) {
            case "forget":
                RouteMemory.forget();
                RouteMemory.save();
                logDirect("Forgot every way through this world (and how the jumps went)");
                break;
            case "save":
                RouteMemory.save();
                logDirect("Saved");
                break;
            default:
                logDirect(RouteMemory.trailCount() + " trails remembered in this world. #routes forget to start over");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String typed = args.peekString().toLowerCase();
            return Stream.of("forget", "save", "status").filter(s -> s.startsWith(typed));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "The ways through courses the bot remembers";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Everywhere you stand goes onto a trail (the bot's or yours), and a #goto towards somewhere a trail goes past",
                "follows it (see the useRouteMemory setting). The trails are kept with the world's other Soprano data.",
                "",
                "Usage:",
                "> routes - how many there are",
                "> routes save - write them out now",
                "> routes forget - forget them all, and how the jumps went"
        );
    }
}
