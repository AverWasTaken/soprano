package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import java.util.Locale;

// the one `combat:` line a transition gets, as text, so every event can be checked to say what happened and where. the
// dimension goes in square brackets right after the prefix, everything after it reads the same in all three
public final class CombatLog {

    private CombatLog() {
    }

    // null for no transition. target is who the fight is about (the new one for a chain), fighting the name remembered from the
    // fight that just ended (the mob is already gone from the world by then), crowd the "2 zombies, 1 skeleton" list
    public static String line(Dimension dimension, Event event, Why why, boolean cornered, String target, String fighting,
                              String crowd, int hp, int blocks) {
        String tag = "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] ";
        return switch (event) {
            case NONE -> null;
            case FIGHT_START -> tag + "FIGHT " + target + " (hit us, hp " + hp + ")";
            case FIGHT_NEXT -> cornered ? tag + (why == Why.CHASED ? "still chased, next " : "cornered, next ") + target
                    : tag + "target dead, next " + target + " (hit us, hp " + hp + ")";
            case RUN_START -> tag + "RUN from " + crowd + " (" + runWhy(why, hp) + ")";
            case FIGHT_TO_RUN -> tag + "FIGHT -> RUN from " + crowd + " (" + runWhy(why, hp) + ")";
            case RUN_TO_FIGHT -> tag + "cornered, fighting " + target;
            case FIGHT_DEAD -> tag + "fight over, " + fighting + " dead";
            case FIGHT_LOST -> tag + "fight over, lost track of " + fighting;
            case FIGHT_STALLED -> tag + "fight over, can't get to " + fighting + ", ignoring it until it hits us again";
            case RUN_CLEAR -> tag + "run over, " + blocks + " blocks, clear";
            case RUN_CAP -> tag + "run over, " + CombatCommit.RUN_CAP / 20 + " s cap, " + blocks + " blocks";
            case RUN_STUCK -> tag + "run over, stuck " + blocks + " blocks from where it started";
            case RUN_EXTENDED -> tag + "run extended, still chased (hp " + hp + ")";
            case RUN_CHASED_FIGHT -> tag + "chased too long, turning to fight " + target;
        };
    }

    // the lit creeper preempt (CreeperStep), not a transition of the machine, so it gets its own line
    public static String creeperStep(Dimension dimension, double distance) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] lit creeper at " + Math.round(distance)
                + " blocks, stepping out of the blast";
    }

    public static String runWhy(Why why, int hp) {
        return switch (why) {
            case HEAVY -> "something nasty close, hp " + hp;
            default -> "hp " + hp;
        };
    }
}
