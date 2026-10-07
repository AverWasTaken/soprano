package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

// which fuel a furnace may take when somebody has plans for the wood
public class FuelPolicyTest {
    // coal is 8 smelts, a log 1.5, planks 1.5 and so on, close enough for picking between them
    private static double fuel(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) return 8;
        return 1.5;
    }

    private static boolean supported(Item item) {
        return true;
    }

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @After
    public void cleanUp() {
        FuelPolicy.clear();
    }

    @Test
    public void withNoReserveEverythingBurns() {
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.OAK_PLANKS, 5));
        assertEquals(22.5, FuelPolicy.usableFuel(bag, FuelPolicyTest::supported, FuelPolicyTest::fuel), 1e-9);
        assertEquals(10, FuelPolicy.choose(bag, 15, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
    }

    @Test
    public void theReserveComesOffTheTopAcrossSpecies() {
        FuelPolicy.set(12, 0, false);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.BIRCH_LOG, 5));
        assertEquals(List.of(0, 3), java.util.Arrays.stream(FuelPolicy.usable(bag)).boxed().toList());
    }

    @Test
    public void reservedWoodIsNotFuel() {
        FuelPolicy.set(10, 4, false);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.OAK_PLANKS, 4));
        assertEquals(0, FuelPolicy.usableFuel(bag, FuelPolicyTest::supported, FuelPolicyTest::fuel), 1e-9);
        assertNull(FuelPolicy.choose(bag, 3, FuelPolicyTest::supported, FuelPolicyTest::fuel));
    }

    @Test
    public void coalGoesFirstWhenItCoversTheJob() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 3), new ItemStack(Items.COAL, 2));
        // 3 smelts: the closest stack would be the logs (4.5), but coal alone covers it
        FuelPolicy.Pick pick = FuelPolicy.choose(bag, 3, FuelPolicyTest::supported, FuelPolicyTest::fuel);
        assertNotNull(pick);
        assertEquals(Items.COAL, pick.stack().getItem());
    }

    @Test
    public void coalThatFallsShortDoesNotForceItself() {
        FuelPolicy.set(0, 0, true);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.COAL, 1));
        // 12 smelts: one coal is 8, the logs are 15. the normal pick stands
        assertEquals(Items.OAK_LOG, FuelPolicy.choose(bag, 12, FuelPolicyTest::supported, FuelPolicyTest::fuel).stack().getItem());
    }

    @Test
    public void onlyTheSurplusOfALogStackIsHandedOver() {
        FuelPolicy.set(6, 0, false);
        List<ItemStack> bag = List.of(new ItemStack(Items.OAK_LOG, 10));
        assertEquals(4, FuelPolicy.choose(bag, 6, FuelPolicyTest::supported, FuelPolicyTest::fuel).count());
    }
}
