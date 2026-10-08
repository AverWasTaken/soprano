package adris.altoclef.tasks.container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.CookGate;
import adris.altoclef.tasks.speedrun.gamer.FakeFacts;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// fuel units for the smelt tasks: when to believe "out of fuel", and what a coal is worth in a furnace and a smoker
public class FuelShortageTest {
    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void aBlipIsNotAShortage() {
        FuelShortage s = new FuelShortage();
        // the tick after a fuel click: gone for a few ticks, then it is in the slot
        for (long t = 100; t < 100 + FuelShortage.HOLD_TICKS - 1; t++) {
            assertFalse(s.confirmed(true, t));
        }
        assertFalse(s.confirmed(false, 109));
        // and it starts over, the earlier blip does not count towards the next
        assertFalse(s.confirmed(true, 110));
        assertFalse(s.confirmed(true, 118));
    }

    @Test
    public void aShortageThatHoldsIsConfirmed() {
        FuelShortage s = new FuelShortage();
        assertFalse(s.confirmed(true, 100));
        assertFalse(s.confirmed(true, 105));
        assertTrue(s.confirmed(true, 100 + FuelShortage.HOLD_TICKS));
        assertTrue(s.confirmed(true, 130));
        s.reset();
        assertFalse(s.confirmed(true, 131));
    }

    @Test
    public void aShortageNotAskedAboutForAWhileIsANewOne() {
        FuelShortage s = new FuelShortage();
        assertFalse(s.confirmed(true, 100));
        // other things happened for a couple of seconds, the old timestamp is not evidence
        assertFalse(s.confirmed(true, 400));
        assertTrue(s.confirmed(true, 410));
    }

    @Test
    public void aSmokerWithABitOfFireLeftAndNoFuelInTheBagIsDry() {
        // 8 mutton in, 3 items of fire left, nothing in the fuel slot and nothing to burn in the bag: this is the "Waiting..." that
        // never ended, it has to go and get 5 more
        assertTrue(FuelShortage.dry(8, 3, 0, 0, 0));
        assertEquals(5, FuelShortage.missing(8, 3, 0, 0), 1e-9);
        // a coal in the bag covers it, so it fills the slot instead of leaving
        assertFalse(FuelShortage.dry(8, 3, 0, 0, 8));
        // a coal already in the slot covers it
        assertFalse(FuelShortage.dry(8, 0, 0, 8, 0));
        // the progress on the item in hand counts too, same sum as the outer check
        assertFalse(FuelShortage.dry(8, 7.5, 0.5, 0, 0));
        // nothing in the input is nothing to fuel
        assertFalse(FuelShortage.dry(0, 0, 0, 0, 0));
    }

    @Test
    public void aCoalIsEightItemsInAFurnaceAndInASmoker() {
        // the cook gate counts a coal as 8 smelts, whatever the station
        assertEquals(8, CookGate.fuelSmelts(new FakeFacts().give(Items.COAL, 1), new OverworldConfig(), 0));
        // a coal lit is 1600 ticks in a furnace, 800 in a smoker, and the lit reading has to come out as the same 8 items
        assertEquals(8.0, StorageHelper.litItems(1600, 200), 1e-9);
        assertEquals(8.0, StorageHelper.litItems(800, 100), 1e-9);
        // 8 meat need exactly one coal, lit or (at 8 in the slot) not lit yet
        assertTrue(AsyncSmelting.fuelCovers(ItemStack.EMPTY, StorageHelper.litItems(800, 100), 8));
        assertFalse(AsyncSmelting.fuelCovers(ItemStack.EMPTY, StorageHelper.litItems(800, 100), 9));
        // half of one is half of eight, which is what the old /200 read for a lit smoker
        assertFalse(AsyncSmelting.fuelCovers(ItemStack.EMPTY, 400 / 200.0, 8));
        assertTrue(AsyncSmelting.fuelCovers(ItemStack.EMPTY, StorageHelper.litItems(400, 100), 4));
    }
}
