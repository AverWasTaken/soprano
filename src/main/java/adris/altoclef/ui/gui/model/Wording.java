package adris.altoclef.ui.gui.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// the gui is soprano's, all of it. command descriptions and setting javadoc still say altoclef in places,
// so every string that reaches the screen goes through here first. setting names (altoFoo) are not words,
// they stay as they are
public final class Wording {

    private static final Pattern ALTOCLEF = Pattern.compile("altoclef('s)?", Pattern.CASE_INSENSITIVE);

    private Wording() {
    }

    public static String soprano(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        Matcher m = ALTOCLEF.matcher(s);
        if (!m.find()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        do {
            String hit = m.group();
            boolean upper = Character.isUpperCase(hit.charAt(0));
            String word = upper ? "Soprano" : "soprano";
            if (m.group(1) != null) {
                word += "'s";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(word));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }
}
