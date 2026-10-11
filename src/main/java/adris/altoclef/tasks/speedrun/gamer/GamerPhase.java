package adris.altoclef.tasks.speedrun.gamer;

// order matters: ordinal() is "how far along the run is", regress rules compare it
public enum GamerPhase {
    GATHER("Getting started"),
    IRON("Getting iron gear"),
    PORTAL("Building a Nether portal"),
    NETHER("Looking for a fortress"),
    EYES("Making Eyes of Ender"),
    RETURN("Heading home"),
    LOCATE("Finding the stronghold"),
    ROOM("Searching the stronghold"),
    OPEN("Opening the End portal"),
    END_PREP("Getting ready for the End"),
    DRAGON("Fighting the dragon"),
    DONE("Done"),
    STUCK("Gave up");

    private final String hud;

    GamerPhase(String hud) {
        this.hud = hud;
    }

    public String hud() {
        return hud;
    }

    public boolean isTerminal() {
        return this == DONE || this == STUCK;
    }

    // the phases that run the cook (the kit's COOK need, the furnace watch). from the nether on nothing loads a smoker or comes
    // back to empty one, so meat in the bag is eaten raw whatever stands in the overworld
    public boolean cooks() {
        return this == GATHER || this == IRON || this == PORTAL;
    }
}
