package adris.altoclef.util.helpers;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Set;

// what the bot is allowed to eat. anything with a FOOD component used to count, which includes pufferfish
public final class FoodHelper {
    public enum Kind {
        // fine to eat whenever hungry
        NORMAL,
        // only when starving and there is nothing better
        ROTTEN_FLESH,
        // teleports you, so only when there is nothing else at all
        CHORUS_FRUIT,
        // gapples are for fights, not for hunger
        RESERVED,
        // poison, or an effect we can't see (suspicious stew), never
        NEVER,
        NOT_FOOD
    }

    private static final Set<Item> NEVER = Set.of(
            Items.PUFFERFISH,
            Items.POISONOUS_POTATO,
            Items.SPIDER_EYE,
            // the effect lives on the stack, not the item, and it can be blindness or fire
            Items.SUSPICIOUS_STEW
    );

    private static final Set<Item> RESERVED = Set.of(
            Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE
    );

    private FoodHelper() {
    }

    public static Kind kindOf(Item item) {
        if (!item.components().has(DataComponents.FOOD)) {
            return Kind.NOT_FOOD;
        }
        if (NEVER.contains(item)) {
            return Kind.NEVER;
        }
        if (RESERVED.contains(item)) {
            return Kind.RESERVED;
        }
        if (item == Items.ROTTEN_FLESH) {
            return Kind.ROTTEN_FLESH;
        }
        if (item == Items.CHORUS_FRUIT) {
            return Kind.CHORUS_FRUIT;
        }
        return Kind.NORMAL;
    }

    // food worth keeping in the bag: edible, and not poison. gapples count here (we hoard them), they just
    // aren't eaten for hunger. for places where a plain "has a FOOD component" check counted pufferfish as a lunch
    public static boolean isSafeFood(Item item) {
        Kind kind = kindOf(item);
        return kind != Kind.NOT_FOOD && kind != Kind.NEVER;
    }
}
