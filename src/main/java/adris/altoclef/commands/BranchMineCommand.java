package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.BranchMiningTask;
import adris.altoclef.tasks.resources.BranchOres;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.Dimension;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class BranchMineCommand extends AltoClefCommand {

    public BranchMineCommand(IBaritone baritone) {
        super(baritone, "branchmine");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        BranchMineArgs.Request request;
        try {
            request = BranchMineArgs.parse(args.rawRest());
        } catch (ItemArgs.ParseException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
        while (args.hasAny()) {
            args.get();
        }
        // the y table is the overworld's, and a nether strip mine at y -59 is a very short conversation with the void
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            throw new CommandInvalidStateException("branch mining only works in the overworld");
        }
        // the way we're looking is the way the trunk goes, decided now and not after the shaft spun us around
        startTask(mod, new BranchMiningTask(request.ore(), request.count(), request.y(), mod.getPlayer().getDirection()));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(BranchOres.names().stream())
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Strip mine for an ore";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The branchmine command digs down to the ore's usual y and strip mines there: a trunk the way you are looking, with a 32 block branch off both sides every 4 blocks. It only mines ore it can see from the tunnel.",
                "",
                "The count is the total held, same as get, and leaving it out (or 0) means until stopped. Silk touched ore blocks count too. It ends by itself when the trunk reaches 256 blocks or there is nowhere left to dig. Overworld only.",
                "",
                "Ores: diamond, redstone, raw_gold (gold), lapis_lazuli (lapis), raw_iron (iron), raw_copper (copper), coal and emerald. Emerald has no usual y, so it needs one.",
                "",
                "Usage:",
                "> branchmine <ore> - Mine until stopped.",
                "> branchmine <ore> <count> - Mine until holding that many.",
                "> branchmine <ore> <count> <y> - The same at a y you pick.",
                "",
                "Examples:",
                "> branchmine diamond 8 - Eight diamonds at y -59.",
                "> branchmine emerald 0 100 - Emeralds at y 100 until stopped."
        );
    }
}
