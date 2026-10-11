package adris.altoclef.ui.gui.model;

import java.util.List;
import java.util.Locale;

// the creative inventory's tab order, so the get tab can group items the way the game itself does. the names are
// the english tab titles; a localised install that does not match one still sorts its categories together, just
// alphabetically after the ones that do. good enough, and nobody browses for "Spawn Eggs" in japanese anyway
public final class CreativeCategories {

    public static final List<String> ORDER = List.of(
            "Building Blocks",
            "Colored Blocks",
            "Natural Blocks",
            "Functional Blocks",
            "Redstone Blocks",
            "Tools & Utilities",
            "Combat",
            "Food & Drinks",
            "Ingredients",
            "Spawn Eggs",
            "Op Blocks"
    );

    private CreativeCategories() {
    }

    // where a category sits in the creative tab bar, a large number for one nobody expected
    public static int order(String category) {
        if (category == null) {
            return Integer.MAX_VALUE;
        }
        String c = category.toLowerCase(Locale.ROOT);
        for (int i = 0; i < ORDER.size(); i++) {
            if (ORDER.get(i).toLowerCase(Locale.ROOT).equals(c)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
