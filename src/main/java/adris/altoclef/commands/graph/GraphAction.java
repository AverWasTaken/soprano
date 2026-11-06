package adris.altoclef.commands.graph;

// what a graph asks the bot to do when it isn't a command line. plain data, so the interpreter can be tested without a
// game and only RoutineHost has to know which task each of these is
public sealed interface GraphAction {

    // count = how many more than we hold when it starts, a mine in a loop has to mine every lap
    record Mine(String block, int count) implements GraphAction {
    }

    record Craft(String item, int count) implements GraphAction {
    }

    record Eat(int hunger) implements GraphAction {
    }

    record Follow(String player, int seconds) implements GraphAction {
    }
}
