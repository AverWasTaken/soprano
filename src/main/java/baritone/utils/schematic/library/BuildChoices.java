package baritone.utils.schematic.library;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// what the player chose about building one file in one world, next to where it's placed. excluded = item ids left out
// of the build (the unchecked rows), empty = everything is in. immutable, every edit hands back a new one
public record BuildChoices(Set<String> excluded) {

    public static final BuildChoices NONE = new BuildChoices(Set.of());

    public BuildChoices {
        // insertion order, so the file and the chat lines list them the way they were unchecked
        excluded = Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
    }

    // nothing to write down: the file only keeps choices somebody actually made
    public boolean isEmpty() {
        return excluded.isEmpty();
    }

    public BuildChoices withIncluded(String itemId, boolean included) {
        Set<String> next = new LinkedHashSet<>(excluded);
        if (included) {
            next.remove(itemId);
        } else {
            next.add(itemId);
        }
        return new BuildChoices(next);
    }
}
