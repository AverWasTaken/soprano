package adris.altoclef.tasks.speedrun.gamer.config;

// owner: end worker (END_PREP, DRAGON)
public class EndConfig {
    // beds to carry into the End (wool is collected in IRON: 3 per bed)
    public int beds = 8;
    // one more bed to set the spawn near the portal, so a death in the End costs a short walk
    public boolean placeSpawnNearPortal = true;
    public double spawnBedRange = 8;
    // the extra bed (wool hunt + placing it) gets this long from the start of END_PREP, then we go without a spawn bed
    public double spawnBedBudgetSeconds = 300;
    public int buildBlocks = 64;
    // the gate: below this we collect up to buildBlocks. 40 because the arrival platform is ~30 void blocks from the island
    public int minBuildBlocks = 40;
    // inside the End: below this and the dragon not perched, mine end stone
    public int endMinBlocks = 20;
    // cooked steak is 8, the fight is minutes long so this is only here so we do not walk in starving
    public int minFoodUnits = 24;
    // armor points below this and the bed strat is not used (bed self damage), sword and pearl instead
    public int bedMinArmor = 10;
    // every dragon death asks for this many more armor points before beds are tried again
    public int bedArmorPerAttempt = 4;
    // dragon fight attempts (a death sends us back to END_PREP) before the run is STUCK
    public int attempts = 3;
    public double dragonHeadCloseEnoughClickBedRange = 5.3;
    // exit portal not needed: chunk (0,0) loaded and the dragon entity gone this long = dead
    public double dragonGoneSeconds = 5;
    // dropped items despawn after 6000 ticks, leave margin before we count them as gear we still own
    public int dropLifetimeTicks = 5400;
    // right after a death the entity list is empty for a moment, do not believe it before this
    public double dropEmptyWaitSeconds = 2;
    // total time per End visit spent walking to dropped gear, so an unreachable pickaxe cannot eat the fight
    public double pickupBudgetSeconds = 120;
}
