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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.utils.FreeCam;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class FreeCamCommand extends Command {

    public FreeCamCommand(IBaritone baritone) {
        super(baritone, "freecam", "spectate");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String what = args.hasAny() ? args.getString().toLowerCase() : "toggle";
        args.requireMax(0);
        String msg;
        switch (what) {
            case "on":
                msg = FreeCam.on() ? "Free cam is already on." : FreeCam.start();
                break;
            case "off":
                msg = FreeCam.stop();
                break;
            case "follow":
                msg = FreeCam.toggleFollow();
                break;
            default:
                // (from chat: the chat screen closes right after the command, so the flying starts with the keys up)
                msg = FreeCam.toggle();
        }
        logDirect(msg);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasExactlyOne()) {
            return Stream.empty();
        }
        String typed = args.peekString().toLowerCase();
        return Stream.of("on", "off", "follow").filter(s -> s.startsWith(typed));
    }

    @Override
    public String getShortDesc() {
        return "Fly a spectator camera round the bot";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "A free camera to watch the bot from anywhere while it carries on: the movement keys fly it (jump up, sneak down,",
                "sprint for faster), the mouse looks, the scroll wheel sets the speed. It follows the bot as it moves unless you",
                "turn that off. The free cam key (freecamKey, F6 by default) does the same as #freecam.",
                "",
                "Usage:",
                "> freecam - on, or off if it's on",
                "> freecam on/off",
                "> freecam follow - follow the bot, or stay put (toggles)"
        );
    }
}
