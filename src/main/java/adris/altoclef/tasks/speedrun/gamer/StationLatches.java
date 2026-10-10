package adris.altoclef.tasks.speedrun.gamer;

// what the planner's held flags remember from the last look, so the plan does not flip at the line (WorkbenchRules.returnRadius /
// bandRadius). MinecraftFacts owns one and building a StationVerdict is the only thing that moves it
final class StationLatches {
    // a table is recorded and was out of NEAR last look, see WorkbenchRules.returnRadius
    boolean tableFar;
    // last look's answer for a furnace and a smoker, null while none is recorded (WorkbenchRules.bandRadius)
    Boolean furnaceHeld;
    Boolean smokerHeld;

    void clear() {
        tableFar = false;
        furnaceHeld = null;
        smokerHeld = null;
    }
}
