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
            case FIGHT_START -> tag + "FIGHT " + target + " (" + (why == Why.IN_WAY ? "in our way" : "hit us") + ", hp " + hp + ")";
            case FIGHT_NEXT -> cornered ? tag + (why == Why.CHASED ? "still chased, next " : "cornered, next ") + target
                    : tag + "target dead, next " + target + " (hit us, hp " + hp + ")";
            case RUN_START -> tag + "RUN from " + crowd + " (" + runWhy(why, hp) + ")";
            case FIGHT_TO_RUN -> tag + "FIGHT -> RUN from " + crowd + " (" + runWhy(why, hp) + ")";
            case RUN_TO_FIGHT -> tag + "cornered, fighting " + target;
            case FIGHT_DEAD -> tag + "fight over, " + fighting + " dead";
            case FIGHT_LOST -> tag + "fight over, lost track of " + fighting;
            case FIGHT_CLEARED -> tag + "fight over, " + fighting + " is out of the way";
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

    // what the step does before baritone has a path: sprints (degrees off straight away, 0 = straight) or has nowhere safe to
    public static String creeperSprint(Dimension dimension, int degrees) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] creeper step: sprinting "
                + (degrees == 0 ? "straight away" : Math.abs(degrees) + " degrees off straight away");
    }

    public static String creeperNoHeading(Dimension dimension) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] creeper step: no safe heading, waiting on the path";
    }

    // the goal going to baritone, and it being dropped before we got there (so it gets asked again)
    public static String creeperPath(Dimension dimension, boolean failed) {
        return "combat: [" + dimension.name().toLowerCase(Locale.ROOT) + "] creeper step: "
                + (failed ? "no path running, asking again" : "path asked for");
    }

    public static String runWhy(Why why, int hp) {
        return switch (why) {
            case HEAVY -> "something nasty close, hp " + hp;
            default -> "hp " + hp;
        };
    }
}
