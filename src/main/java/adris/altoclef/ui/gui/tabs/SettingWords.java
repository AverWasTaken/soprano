package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Popover;
import adris.altoclef.ui.gui.model.NumberRules;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntFunction;

// the words a settings tile shows: the name, the short value in its corner, the default in the tooltip. nothing in
// here is ever sent back to #set, the real name and the #set text forms stay as they are
public final class SettingWords {

    private SettingWords() {
    }

    // allowParkour -> Allow Parkour, primaryTimeoutMS -> Primary Timeout MS. the real name split, nothing renamed
    public static String split(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String s = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // what a tile is called. the alto block all starts with "alto", which says where the setting came from and
    // nothing about what it does, so it goes on screen only (altoMobDefense -> Mob Defense). #set still wants the
    // real name, the tooltip says it
    public static String label(String name) {
        if (name != null && name.length() > 4 && name.startsWith("alto") && Character.isUpperCase(name.charAt(4))) {
            return split(name.substring(4));
        }
        return split(name);
    }

    // the corner of the tile
    public static String shown(SettingView v, IntFunction<String> keyName) {
        return text(v, v.value(), keyName);
    }

    // "Default: ..." in the tooltip, in the same words the corner would use
    public static String shownDefault(SettingView v, IntFunction<String> keyName) {
        return text(v, v.defaultValue(), keyName);
    }

    private static String text(SettingView v, String raw, IntFunction<String> keyName) {
        String s = raw == null ? "" : raw;
        return switch (v.kind()) {
            case BOOLEAN -> Boolean.parseBoolean(s.trim()) ? "On" : "Off";
            case INTEGER, LONG, FLOAT, DOUBLE -> number(s);
            case ENUM -> word(s);
            case COLOR -> {
                int[] rgb = NumberRules.parseRgb(s);
                yield rgb == null ? s : Popover.colourName((rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
            }
            case KEY -> key(s, keyName);
            case STRING -> s.isEmpty() ? "None" : s;
            case OTHER -> other(v.typeName(), s);
        };
    }

    // 12.0 -> 12, 0.75 stays. the #set text of a double always has a point, a tile corner has no room for one
    public static String number(String s) {
        String t = s == null ? "" : s.trim();
        try {
            BigDecimal d = new BigDecimal(t).stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        } catch (NumberFormatException e) {
            return t;
        }
    }

    // SMART -> Smart, LEFT_RIGHT -> Left right
    public static String word(String constant) {
        if (constant == null || constant.isEmpty()) {
            return "";
        }
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String key(String s, IntFunction<String> keyName) {
        int k;
        try {
            k = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return s;
        }
        String n = keyName == null ? null : keyName.apply(k);
        if (n == null || n.isEmpty()) {
            return k < 0 ? "None" : s;
        }
        return n;
    }

    // lists and maps say how many, the whole thing only fits in the popover. anything else (a position, a range) is
    // short enough to show as typed
    static String other(String typeName, String s) {
        String type = typeName == null ? "" : typeName;
        boolean list = type.startsWith("List<");
        boolean map = type.startsWith("Map<");
        if (!list && !map) {
            return s.isEmpty() ? "None" : s;
        }
        if (s.isBlank()) {
            return "None";
        }
        // a map entry is "a->b" and the value side may itself be a list with commas in it, so count the arrows
        int n = map ? s.split("->", -1).length - 1 : s.split(",", -1).length;
        String one;
        if (map) {
            one = "entry";
        } else if (type.equals("List<Block>")) {
            one = "block";
        } else {
            one = "item";
        }
        if (n == 1) {
            return "1 " + one;
        }
        return n + " " + (one.equals("entry") ? "entries" : one + "s");
    }

    // a number kind for the popover, null for anything else
    public static boolean isNumber(Kind k) {
        return k == Kind.INTEGER || k == Kind.LONG || k == Kind.FLOAT || k == Kind.DOUBLE;
    }

    // the default typed in by hand counts as the default: "12" is fastModeMinHealth's "12.0"
    public static boolean sameValue(Kind k, String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (isNumber(k)) {
            try {
                return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (k == Kind.BOOLEAN || k == Kind.ENUM) {
            return a.trim().equalsIgnoreCase(b.trim());
        }
        if (k == Kind.COLOR) {
            int[] x = NumberRules.parseRgb(a);
            int[] y = NumberRules.parseRgb(b);
            return x != null && y != null && Arrays.equals(x, y);
        }
        return a.equals(b);
    }

    // every word of the query has to turn up somewhere in what the player can see or type: the name on the tile, the
    // real one, an old one, the tooltip sentence. "parkour place" finds allowParkourPlace, "experimental" finds fast mode
    public static boolean matches(String query, String name, Iterable<String> oldNames, String sentence) {
        if (query == null || query.isBlank()) {
            return true;
        }
        StringBuilder hay = new StringBuilder();
        hay.append(label(name)).append('\n').append(split(name)).append('\n').append(name).append('\n');
        if (oldNames != null) {
            for (String o : oldNames) {
                hay.append(o).append('\n');
            }
        }
        hay.append(sentence == null ? "" : sentence);
        String h = hay.toString().toLowerCase(Locale.ROOT);
        for (String t : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!h.contains(t)) {
                return false;
            }
        }
        return true;
    }
}
