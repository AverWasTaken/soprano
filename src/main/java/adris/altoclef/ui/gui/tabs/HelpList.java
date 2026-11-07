package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;

import java.util.List;

// the help tab went, its word wrap did not. it lives in the kit as TextWrap.words, this name stays for the node
// editor's commands tab, which still calls it and lands on its own branch
final class HelpList {

    private HelpList() {
    }

    static List<String> wrap(TextMeasurer m, String s, TextStyle style, float maxW) {
        return TextWrap.words(m, s, style, maxW);
    }
}
