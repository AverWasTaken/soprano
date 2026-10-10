package adris.altoclef.tasks.speedrun.gamer;

// what the planner's held flag for the table remembers from the last look, so the plan does not flip at the line
// (WorkbenchRules.returnRadius, StationChoice.HOLD). MinecraftFacts owns one and building a StationVerdict is the only thing that
// moves it. furnace and smoker have none, see StationVerdict.of
final class StationLatches {
    // a table is recorded and was out of NEAR last look, see WorkbenchRules.returnRadius
    boolean tableFar;
    // the table was held last look by the walk back and not by being near (StationVerdict.of): it stays held out to the walk back
    // line + StationChoice.HOLD, the way the task keeps the one it is walking to
    boolean tableBack;

    void clear() {
        tableFar = false;
        tableBack = false;
    }
}
