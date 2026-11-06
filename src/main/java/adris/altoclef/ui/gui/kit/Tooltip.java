package adris.altoclef.ui.gui.kit;

import java.util.List;

// what a hover card says. title is the bold first line, body is the paragraph under it (wrapped, 8 lines at most),
// footer goes under a hairline: the first footer line reads like a command, the rest are quiet. any part may be empty
public record Tooltip(String title, String body, List<String> footer) {

    public Tooltip {
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        footer = footer == null ? List.of() : List.copyOf(footer);
    }

    // just a sentence, for the plain "what does this button do" kind
    public static Tooltip of(String body) {
        return new Tooltip("", body, List.of());
    }

    public boolean empty() {
        return title.isEmpty() && body.isEmpty() && footer.isEmpty();
    }
}
