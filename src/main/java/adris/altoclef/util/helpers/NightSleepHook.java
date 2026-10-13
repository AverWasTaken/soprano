package adris.altoclef.util.helpers;

import adris.altoclef.tasks.speedrun.gamer.NightSleep;

// the one fact the mob defense chain needs from the night trip: which stage it is in, null when there is no trip. same shape as
// StationHook, the gamer writes it every tick and clears it when the run ends. the chain asks NightSleep.standsDown with the
// live health, so the low health bail does not wait for the next gamer tick
public final class NightSleepHook {
    private static volatile NightSleep.Stage stage;

    private NightSleepHook() {
    }

    public static void set(NightSleep.Stage now) {
        stage = now;
    }

    public static void clear() {
        stage = null;
    }

    public static NightSleep.Stage stage() {
        return stage;
    }

    public static boolean standsDown(float health) {
        NightSleep.Stage now = stage;
        return now != null && NightSleep.standsDown(now, health);
    }
}
