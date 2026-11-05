package adris.altoclef.commands;

import adris.altoclef.tasks.resources.BranchOres;

import java.util.List;

// "<ore> [count] [y]". ItemArgs can't do this, its numbers are always counts. no game in here so it can be tested
public final class BranchMineArgs {

    // count 0 is until stopped
    public record Request(BranchOres.Ore ore, int count, int y) {
    }

    private BranchMineArgs() {
    }

    public static Request parse(String rest) throws ItemArgs.ParseException {
        List<String> tokens = ItemArgs.tokens(rest);
        if (tokens.isEmpty() || tokens.size() > 3) {
            throw new ItemArgs.ParseException("expected <ore> [count] [y]");
        }
        BranchOres.Ore ore = BranchOres.find(ItemArgs.normalize(tokens.get(0)));
        if (ore == null) {
            throw new ItemArgs.ParseException("\"" + tokens.get(0) + "\" isn't an ore this knows, try one of: " + String.join(", ", BranchOres.names()));
        }
        int count = tokens.size() > 1 ? number(tokens.get(1), "count") : 0;
        if (count < 0) {
            // a lone -59 is somebody going for the y. saying so beats guessing, a lone 16 would read the other way round
            throw new ItemArgs.ParseException("count can't be negative. a y goes after the count, and a count of 0 means until stopped");
        }
        return new Request(ore, count, BranchOres.clampY(y(ore, tokens)));
    }

    private static int y(BranchOres.Ore ore, List<String> tokens) throws ItemArgs.ParseException {
        if (tokens.size() > 2) {
            return number(tokens.get(2), "y");
        }
        if (ore.defaultY() == null) {
            throw new ItemArgs.ParseException(ore.name() + " has no usual y, so give one: " + ore.name() + " <count> <y>. a count of 0 means until stopped");
        }
        return ore.defaultY();
    }

    private static int number(String token, String what) throws ItemArgs.ParseException {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new ItemArgs.ParseException("\"" + token + "\" isn't a " + what);
        }
    }
}
