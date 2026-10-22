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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class TungstenCommand extends Command {

    public TungstenCommand(IBaritone baritone) {
        super(baritone, "tungsten");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        BetterBlockPos target;
        if (args.hasAny()) {
            args.requireMax(3);
            target = args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.playerFeet());
        } else {
            Goal goal = baritone.getCustomGoalProcess().getGoal();
            if (!(goal instanceof GoalBlock)) {
                throw new CommandInvalidStateException("Give it a block to stand in, or set a block goal first (#goal x y z)");
            }
            GoalBlock block = (GoalBlock) goal;
            target = new BetterBlockPos(block.x, block.y, block.z);
        }
        if (target.distanceTo(ctx.playerFeet()) > 24) {
            throw new CommandInvalidStateException("That's too far for one Tungsten search, it's for jumps a few blocks away. Use #goto (with allowTungstenJumps on)");
        }
        baritone.getPathingBehavior().cancelEverything();
        ((Baritone) baritone).getTungstenProcess().go(target);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
    }

    @Override
    public String getShortDesc() {
        return "Search the keys themselves for a way to a nearby block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Tungsten mode: no block grid, no planner, just one search over which keys to hold on every tick, through the player",
                "physics simulation, to standing in the block you name. For the odd jumps a few blocks away that the normal moves",
                "have no name for (a neo round a wall, a few posts in a row, a jump to something at an angle).",
                "",
                "The search runs in the background and can take a few seconds (tungstenSearchBudget). #stop stops it.",
                "For whole paths with these jumps in, turn on allowTungstenJumps and use #goto as usual.",
                "",
                "Usage:",
                "> tungsten <x> <y> <z> - stand in this block (~ works)",
                "> tungsten - stand in the block of the current goal"
        );
    }
}
