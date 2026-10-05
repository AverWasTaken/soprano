package adris.altoclef.tasks.speedrun.gamer.config;

import adris.altoclef.util.helpers.ConfigHelper;

// the one place the gamer reads configs/beat_minecraft.json from. #altoreload re-reads it through ConfigHelper, and the
// file is versioned: the old BeatMinecraftConfig shape has no "version", so it gets replaced by the new defaults instead of
// leaking its numbers (minimumEyes 12, requiredBeds 10, minFoodUnits 180...) into them
public final class GamerConfigs {
    public static final String PATH = "configs/beat_minecraft.json";

    private static volatile GamerConfig current;

    private GamerConfigs() {
    }

    public static GamerConfig get() {
        GamerConfig c = current;
        return c != null ? c : load();
    }

    // reads the file now (creating it with the defaults if it is missing), and hooks #altoreload up to it
    public static synchronized GamerConfig load() {
        ConfigHelper.loadVersionedConfig(PATH, GamerConfig.VERSION, GamerConfig::new, GamerConfig.class, c -> current = sanitize(c));
        return current;
    }

    // "nether": null in a hand edited file would be an NPE hours into a run. a missing key is fine, gson keeps the default
    static GamerConfig sanitize(GamerConfig c) {
        if (c.budgets == null) {
            c.budgets = new Budgets();
        }
        if (c.death == null) {
            c.death = new GamerConfig.Death();
        }
        if (c.overworld == null) {
            c.overworld = new OverworldConfig();
        }
        if (c.nether == null) {
            c.nether = new NetherConfig();
        }
        if (c.stronghold == null) {
            c.stronghold = new StrongholdConfig();
        }
        if (c.end == null) {
            c.end = new EndConfig();
        }
        return c;
    }
}
