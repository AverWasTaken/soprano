package adris.altoclef.util.helpers;

import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.FuelValues;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// on 1.21.11 the fuel table is the level's FuelValues, built in code by vanillaBurnTimes. ItemHelper reads it as is,
// so this holds the numbers the smelting code plans around against what vanilla actually hands it
public class FuelTableTest {

    private static Map<Item, Integer> fuel;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        fuel = ItemHelper.fuelTimes(FuelValues.vanillaBurnTimes(VanillaRegistries.createLookup(), FeatureFlags.DEFAULT_FLAGS));
    }

    @Test
    public void coalAndLavaBurnTheirKnownAmounts() {
        // 1600 ticks of coal is 8 smelts of 200, the number the smelting code plans around
        assertEquals(1600, (int) fuel.get(Items.COAL));
        assertEquals(20000, (int) fuel.get(Items.LAVA_BUCKET));
        assertEquals(100, (int) fuel.get(Items.STICK));
        assertFalse(fuel.containsKey(Items.STONE));
    }

    @Test
    public void theTableIsNotEmpty() {
        assertTrue("found " + fuel.size() + " fuel items, vanilla's table didn't build", fuel.size() > 50);
    }
}
