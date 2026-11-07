package adris.altoclef.ui.gui.kit;

import java.util.List;
import java.util.Locale;

// one card as the node canvas sees it. nothing here knows a node type, so a type added to the backend tomorrow shows up
// with the group's look and a fallback icon instead of a crash. group is act, flow or end, anything else looks like end
public record NodeCard(String id, int x, int y, boolean small, String title, Icon icon, String group, String summary,
                       List<String> itemIds, List<Port> outs, boolean hasIn, boolean inWired, State state,
                       String badgeText, Icon badgeIcon, String why, boolean warn) {

    public record Port(String name, boolean wired) {
    }

    // one at a time: mid run the validator's problems are blanked anyway
    public enum State {
        IDLE, ACTIVE, DONE, FAILED, BAD
    }

    public int outIndex(String port) {
        for (int i = 0; i < outs.size(); i++) {
            if (outs.get(i).name().equals(port)) {
                return i;
            }
        }
        return 0;
    }

    // by name, so a new type shows at once with its group's icon until somebody draws it one
    public static Icon iconFor(String type, String group) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "start" -> Icon.PLAY;
            case "get" -> Icon.GET;
            case "equip" -> Icon.EQUIP;
            case "goto" -> Icon.GOTO;
            case "mine" -> Icon.PICKAXE;
            case "craft" -> Icon.CRAFT;
            case "eat" -> Icon.EAT;
            case "follow" -> Icon.FOLLOW;
            case "raw" -> Icon.HASH;
            case "sub", "run" -> Icon.ROUTINE;
            case "if" -> Icon.IF;
            case "repeat" -> Icon.REPEAT;
            case "wait" -> Icon.CLOCK;
            case "stop" -> Icon.STOP;
            default -> switch (group == null ? "" : group) {
                case "act" -> Icon.BOLT;
                case "flow" -> Icon.NODES;
                default -> Icon.RING;
            };
        };
    }
}
