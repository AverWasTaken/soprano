package adris.altoclef.tasks.speedrun.gamer.config;

// owner: end worker (END_PREP, DRAGON)
public class EndConfig {
    // beds to carry into the End (wool is collected in IRON: 3 per bed)
    public int beds = 8;
    // one more bed to set the spawn near the portal, so a death in the End costs a short walk
    public boolean placeSpawnNearPortal = true;
    public double spawnBedRange = 8;
    public int buildBlocks = 64;
    public int minBuildBlocks = 5;
    // armor points below this and the bed strat is not used (bed self damage), sword and pearl instead
    public int bedMinArmor = 10;
    // dragon fight attempts (a death sends us back to END_PREP) before the run is STUCK
    public int attempts = 3;
    public double dragonHeadCloseEnoughClickBedRange = 5.3;
}
