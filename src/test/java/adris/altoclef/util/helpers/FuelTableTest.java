package adris.altoclef.util.helpers;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.FuelValues;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// on 1.21.11 the fuel table is the level's FuelValues, built in code by vanillaBurnTimes. ItemHelper reads it as is,
// so this holds the numbers the smelting code plans around against what vanilla actually hands it
public class FuelTableTest {

    private static FuelValues vanilla;
    private static Map<Item, Integer> fuel;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        // the item lookup has to be the live registry, whose tags ItemFixture bound. the datagen lookup only has placeholders
        HolderLookup.Provider items = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.ITEM));
        vanilla = FuelValues.vanillaBurnTimes(items, FeatureFlags.DEFAULT_FLAGS);
        fuel = ItemHelper.fuelTimes(vanilla);
    }

    @Test
    public void coalAndLavaBurnTheirKnownAmounts() {
        // 1600 ticks of coal is 8 smelts of 200, the number the smelting code plans around
        assertEquals(1600, (int) fuel.get(Items.COAL));
        assertEquals(20000, (int) fuel.get(Items.LAVA_BUCKET));
        assertEquals(100, (int) fuel.get(Items.STICK));
        assertEquals(300, (int) fuel.get(Items.OAK_PLANKS));
        assertFalse(fuel.containsKey(Items.STONE));
    }

    @Test
    public void everyFuelItemMatchesTheVanillaData() {
        // fuelTimes only walks fuelItems(), so this asks vanilla about every item in the registry one stack at a time,
        // the way a furnace does. anything fuelTimes drops, invents or mangles shows up here
        List<String> wrong = new ArrayList<>();
        int fuels = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (!vanilla.isFuel(stack)) {
                // and the other way: nothing in the table that vanilla won't burn
                if (fuel.containsKey(item)) {
                    wrong.add(name + " is in the table but not fuel in vanilla");
                }
                continue;
            }
            fuels++;
            int expected = vanilla.burnDuration(stack);
            Integer got = fuel.get(item);
            if (got == null) {
                wrong.add(name + " is not in the table");
            } else if (got != expected) {
                wrong.add(name + " burns " + got + " in the table, " + expected + " in vanilla");
            }
        }
        assertTrue("fuel table disagrees with vanilla: " + wrong, wrong.isEmpty());
        assertTrue("found " + fuels + " fuel items, vanilla's table didn't build", fuels > 50);
        assertEquals(fuels, fuel.size());
    }

    @Test
    public void theTableIsNotEmpty() {
        assertTrue("found " + fuel.size() + " fuel items, vanilla's table didn't build", fuel.size() > 50);
    }
}
