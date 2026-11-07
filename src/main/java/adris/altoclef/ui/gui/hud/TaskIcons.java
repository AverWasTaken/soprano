package adris.altoclef.ui.gui.hud;

import adris.altoclef.ui.gui.kit.Icon;

import java.util.Locale;
import java.util.Map;

// which glyph a task line gets on the status panel. it goes off the hud name, the words the player reads, so a task
// that never bothered with a label still lands somewhere sensible through its humanized class name. phrases first
// (they know better than the verb: "Getting food" is eating, not collecting), then the first word. nothing matched
// is a dot, which is honest: something is happening, we just don't have a picture for it
public final class TaskIcons {

    // checked in this order, anywhere in the name
    private static final String[][] PHRASES = {
            {"for the path", "GOTO"},
            {"getting within", "GOTO"},
            {"get to chunk", "GOTO"},
            {"getting out", "RUN"},
            {"mine and collect", "GET"},
            {"breaking a fall", "WATER"},
            {"breaking the fall", "WATER"},
            {"food", "EAT"},
            {"bucket", "WATER"},
            {"ladder", "LADDER"},
            {"eye of ender", "EYE"},
            {"boat", "BOAT"},
    };

    private static final Map<String, Icon> VERBS = Map.ofEntries(
            // going somewhere
            Map.entry("going", Icon.GOTO),
            Map.entry("heading", Icon.GOTO),
            Map.entry("coming", Icon.GOTO),
            Map.entry("approaching", Icon.GOTO),
            Map.entry("travelling", Icon.GOTO),
            Map.entry("traveling", Icon.GOTO),
            Map.entry("fast", Icon.GOTO),
            Map.entry("walking", Icon.GOTO),
            Map.entry("wandering", Icon.GOTO),
            Map.entry("bridging", Icon.GOTO),
            Map.entry("goto", Icon.GOTO),
            // getting away from something
            Map.entry("escaping", Icon.RUN),
            Map.entry("fleeing", Icon.RUN),
            Map.entry("running", Icon.RUN),
            Map.entry("backing", Icon.RUN),
            Map.entry("dodging", Icon.RUN),
            Map.entry("staying", Icon.RUN),
            Map.entry("stepping", Icon.RUN),
            // digging
            Map.entry("mining", Icon.PICKAXE),
            Map.entry("mine", Icon.PICKAXE),
            Map.entry("breaking", Icon.PICKAXE),
            Map.entry("digging", Icon.PICKAXE),
            Map.entry("branch", Icon.PICKAXE),
            Map.entry("clearing", Icon.PICKAXE),
            Map.entry("clear", Icon.PICKAXE),
            Map.entry("carving", Icon.PICKAXE),
            // getting stuff
            Map.entry("collecting", Icon.GET),
            Map.entry("collect", Icon.GET),
            Map.entry("getting", Icon.GET),
            Map.entry("get", Icon.GET),
            Map.entry("gathering", Icon.GET),
            Map.entry("taking", Icon.GET),
            Map.entry("looting", Icon.GET),
            Map.entry("picking", Icon.GET),
            Map.entry("harvesting", Icon.GET),
            Map.entry("pickup", Icon.GET),
            // making stuff
            Map.entry("crafting", Icon.CRAFT),
            Map.entry("craft", Icon.CRAFT),
            Map.entry("making", Icon.CRAFT),
            Map.entry("smelting", Icon.CRAFT),
            Map.entry("smelt", Icon.CRAFT),
            Map.entry("upgrading", Icon.CRAFT),
            // food
            Map.entry("eating", Icon.EAT),
            Map.entry("cooking", Icon.EAT),
            Map.entry("healing", Icon.EAT),
            Map.entry("caring", Icon.EAT),
            // putting blocks down
            Map.entry("placing", Icon.BLOCK),
            Map.entry("place", Icon.BLOCK),
            Map.entry("building", Icon.BLOCK),
            Map.entry("build", Icon.BLOCK),
            Map.entry("covering", Icon.BLOCK),
            Map.entry("construct", Icon.BLOCK),
            Map.entry("constructing", Icon.BLOCK),
            Map.entry("filling", Icon.BLOCK),
            // inventory and containers
            Map.entry("storing", Icon.BACKPACK),
            Map.entry("putting", Icon.BACKPACK),
            Map.entry("giving", Icon.BACKPACK),
            Map.entry("dropping", Icon.BACKPACK),
            Map.entry("tidying", Icon.BACKPACK),
            Map.entry("opening", Icon.BACKPACK),
            Map.entry("clicking", Icon.BACKPACK),
            Map.entry("moving", Icon.BACKPACK),
            Map.entry("sorting", Icon.BACKPACK),
            Map.entry("equipping", Icon.EQUIP),
            // fights
            Map.entry("fighting", Icon.EQUIP),
            Map.entry("attacking", Icon.EQUIP),
            Map.entry("hunting", Icon.EQUIP),
            Map.entry("killing", Icon.EQUIP),
            Map.entry("kill", Icon.EQUIP),
            Map.entry("defending", Icon.EQUIP),
            Map.entry("bedding", Icon.EQUIP),
            Map.entry("shooting", Icon.EQUIP),
            // looking for something
            Map.entry("finding", Icon.SEARCH),
            Map.entry("searching", Icon.SEARCH),
            Map.entry("exploring", Icon.SEARCH),
            Map.entry("locating", Icon.SEARCH),
            Map.entry("looking", Icon.SEARCH),
            Map.entry("scanning", Icon.SEARCH),
            Map.entry("following", Icon.FOLLOW),
            Map.entry("follow", Icon.FOLLOW),
            // waiting it out
            Map.entry("waiting", Icon.CLOCK),
            Map.entry("sleeping", Icon.CLOCK),
            Map.entry("night", Icon.CLOCK),
            Map.entry("respawning", Icon.RESET),
            Map.entry("beating", Icon.BOLT),
            Map.entry("hanging", Icon.LADDER),
            Map.entry("swimming", Icon.WATER)
    );

    private TaskIcons() {
    }

    public static Icon forName(String name) {
        if (name == null || name.isBlank()) {
            return Icon.DOT;
        }
        String s = name.toLowerCase(Locale.ROOT).trim();
        for (String[] p : PHRASES) {
            if (s.contains(p[0])) {
                return Icon.valueOf(p[1]);
            }
        }
        int end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) {
            end++;
        }
        Icon icon = VERBS.get(s.substring(0, end));
        return icon == null ? Icon.DOT : icon;
    }
}
