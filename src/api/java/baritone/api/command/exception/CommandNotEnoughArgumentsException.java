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

package baritone.api.command.exception;

import baritone.api.command.ICommand;
import baritone.api.command.argument.ICommandArgument;
import net.minecraft.ChatFormatting;

import java.util.List;

import static baritone.api.utils.Helper.HELPER;

public class CommandNotEnoughArgumentsException extends CommandErrorMessageException {

    public CommandNotEnoughArgumentsException(int minArgs) {
        super(String.format("Not enough arguments, this needs at least %d.", minArgs));
    }

    @Override
    public void handle(ICommand command, List<ICommandArgument> args) {
        String name = command == null || command.getNames().isEmpty() ? null : command.getNames().get(0);
        HELPER.logDirect(getMessage() + (name == null ? "" : HelpHint.forCommand(name)), ChatFormatting.RED);
    }
}
