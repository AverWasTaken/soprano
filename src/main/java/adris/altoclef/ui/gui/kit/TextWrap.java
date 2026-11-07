package adris.altoclef.ui.gui.kit;

import java.util.ArrayList;
import java.util.List;

// word wrap for the few places the gui shows a paragraph (the update card, release notes). the kit otherwise
// ellipsizes, which is right for a row and wrong for a sentence you're meant to read to the end
public final class TextWrap {

    private TextWrap() {
    }

    // breaks on spaces, keeps the newlines that were already there (an empty line stays an empty line), and chops a
    // word that is wider than the box on its own so a url can't push the card open
    public static List<String> wrap(TextMeasurer m, String text, TextStyle style, float maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String para : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            wrapParagraph(m, para, style, maxWidth, out);
        }
        return out;
    }

    private static void wrapParagraph(TextMeasurer m, String para, TextStyle style, float maxWidth, List<String> out) {
        String trimmed = para.strip();
        if (trimmed.isEmpty()) {
            out.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (m.measure(word, style) > maxWidth) {
                // flush what we have, then the long word goes down in pieces
                if (!line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                chop(m, word, style, maxWidth, out, line);
                continue;
            }
            if (line.isEmpty()) {
                line.append(word);
            } else if (m.measure(line + " " + word, style) <= maxWidth) {
                line.append(' ').append(word);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
    }

    // the plain greedy wrap the help list grew up with (moved here so the schematics code can keep it when help goes).
    // newlines are just spaces, a word wider than the line is cut by characters, and nothing in still gives one empty
    // line so a caller can count on lines.get(0)
    public static List<String> words(TextMeasurer m, String s, TextStyle style, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (s == null ? "" : s).split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (m.measure(tryLine, style) <= maxW) {
                line.setLength(0);
                line.append(tryLine);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (m.measure(rest, style) > maxW && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && m.measure(rest.substring(0, cut), style) > maxW) {
                    cut--;
                }
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (line.length() > 0 || out.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    // the tail piece lands in line so the next word can follow it
    private static void chop(TextMeasurer m, String word, TextStyle style, float maxWidth, List<String> out, StringBuilder line) {
        int start = 0;
        while (start < word.length()) {
            int end = start + 1;
            while (end < word.length() && m.measure(word.substring(start, end + 1), style) <= maxWidth) {
                end++;
            }
            String piece = word.substring(start, end);
            if (end >= word.length()) {
                line.append(piece);
            } else {
                out.add(piece);
            }
            start = end;
        }
    }
}
