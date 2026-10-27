package adris.altoclef.tasks.speedrun.gamer.config;

// configs/beat_minecraft.json. "version" is checked before gson sees the file: any other value (including the old
// BeatMinecraftConfig shape, which has none) means defaults, saved over it, one log line. nested objects are one class
// per phase group so the workers do not step on each other.
// an older file from OLDEST_KEPT up is not thrown away: its numbers stay and the kit lists (every List<KitItem> in here)
// go back to the defaults, see GamerConfigs.migrate
public class GamerConfig {
    // bump VERSION when you change a default kit list (or add one). a saved file replaces the default list outright, so
    // without the bump every existing run keeps the old kit forever (that is how the bot went to the nether with no
    // ladders). GamerConfigsTest has a hash of the defaults that fails until you do
    // (5 moved the old station pickup defaults. those keys are gone now, an old file still loads, gson skips them)
    // (6 moved the sweep spacing default 9 -> 14, a saved 9 is the old default and goes along, see GamerConfigs.migrate)
    // (7 swapped the stone and iron axes for swords, the sword is the weapon now)
    // (8 moved the spare shields default 2 -> 3, a saved 2 is the old default and goes along, see GamerConfigs.migrate)
    public static final int VERSION = 8;
    // files from here up to VERSION get the migration instead of the defaults. raise it if a bump ever changes the shape
    // in a way the migration can not paper over
    public static final int OLDEST_KEPT = 2;

    public int version = VERSION;
    // a failing phase gets this many tries before it is skipped or the run goes STUCK
    public int maxAttempts = 2;
    // eyes of ender: craft this many in the nether, the portal needs 12 minus the ~1.2 that are pre filled,
    // and every throw loses an eye one time in five
    public int targetEyes = 14;
    // leave the nether / start the stronghold with at least this many when the pearl budget ran out
    public int floorEyes = 12;

    public Budgets budgets = new Budgets();
    public Death death = new Death();
    public OverworldConfig overworld = new OverworldConfig();
    public NetherConfig nether = new NetherConfig();
    public StrongholdConfig stronghold = new StrongholdConfig();
    public EndConfig end = new EndConfig();

    public static class Death {
        // go back for our stuff when we died this close (flat distance) in the dimension we respawned in, and the pile was worth it
        // (DeathRules.refuse). far is fine: a pile in a chunk nobody has loaded does not age. this used to be recoverBlocks (300),
        // a saved file keeps its numbers, so a new name is how the new default reaches the old files
        public int recoverReach = 1000;
        // items despawn after 5 minutes, leave margin. counted from when the pile can be loaded (RecoverRules.Allowance), the
        // walk there is not part of it
        public int recoverSeconds = 240;
        public int maxPerPhase = 3;
        // deaths since the run was (re)started by hand, over every phase together
        public int maxTotal = 12;
        // a death in the nether leaves a pile that does not age while we are away: go back through the portal for it
        public boolean netherRecover = true;
        // the nether part of the trip (walk, pile), game seconds, counted from the moment we arrive. the overworld stages have caps of their own. over it we rebuild the kit like before
        public int netherTripSeconds = 360;
        // throwaway blocks to get by hand before the portal, and how long we spend on them before going with what we have
        public int netherBlocks = 24;
        public int netherBlocksSeconds = 60;
    }
}
