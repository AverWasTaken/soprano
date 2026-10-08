package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.WeaponPick.Candidate;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class WeaponPickTest {

    @BeforeClass
    public static void bootstrap() {
        // damage and swing speed come off the item's attribute components, which need the registries
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Candidate fresh(Item item) {
        return new Candidate(item, 0, item.getDefaultInstance().getMaxDamage());
    }

    private static Candidate worn(Item item) {
        int max = item.getDefaultInstance().getMaxDamage();
        return new Candidate(item, (int) Math.ceil(max * 0.9), max);
    }

    @Test
    public void anAxeBeatsAHigherTierSwordWhenItHitsHarder() {
        assertSame(Items.WOODEN_AXE, WeaponPick.best(List.of(fresh(Items.STONE_SWORD), fresh(Items.WOODEN_AXE))));
        assertSame(Items.WOODEN_AXE, WeaponPick.best(List.of(fresh(Items.WOODEN_AXE), fresh(Items.STONE_SWORD))));
    }

    @Test
    public void anAxeBeatsASwordOfTheSameTier() {
        assertSame(Items.IRON_AXE, WeaponPick.best(List.of(fresh(Items.IRON_SWORD), fresh(Items.IRON_AXE))));
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(fresh(Items.DIAMOND_AXE), fresh(Items.DIAMOND_SWORD))));
        assertSame(Items.NETHERITE_AXE, WeaponPick.best(List.of(fresh(Items.NETHERITE_SWORD), fresh(Items.NETHERITE_AXE))));
    }

    @Test
    public void aSwordStillWinsWhenItReallyHitsHarder() {
        // netherite sword 8 against a wooden axe 7
        assertSame(Items.NETHERITE_SWORD, WeaponPick.best(List.of(fresh(Items.WOODEN_AXE), fresh(Items.NETHERITE_SWORD))));
    }

    @Test
    public void nothingButSwordsAndAxesIsAWeapon() {
        assertNull(WeaponPick.best(List.of()));
        assertNull(WeaponPick.best(List.of(fresh(Items.DIAMOND_PICKAXE), fresh(Items.DIAMOND_SHOVEL), fresh(Items.STICK))));
        assertSame(Items.WOODEN_SWORD, WeaponPick.best(List.of(fresh(Items.DIAMOND_PICKAXE), fresh(Items.WOODEN_SWORD))));
    }

    @Test
    public void aWornToolLosesToAnyHealthyWeapon() {
        // a diamond axe about to break is worse than a stone sword with all its uses left
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(worn(Items.DIAMOND_AXE), fresh(Items.STONE_SWORD))));
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(fresh(Items.STONE_SWORD), worn(Items.DIAMOND_AXE))));
    }

    @Test
    public void aWornToolIsStillBetterThanFists() {
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(worn(Items.DIAMOND_AXE))));
        // and between two worn ones the harder hit still wins
        assertSame(Items.IRON_AXE, WeaponPick.best(List.of(worn(Items.IRON_SWORD), worn(Items.IRON_AXE))));
    }

    @Test
    public void wearUsesTheSameEightyFivePercentAsTheKitPlanner() {
        assertFalse(WeaponPick.wornOut(111, 131));
        assertTrue(WeaponPick.wornOut(112, 131));
        assertFalse(WeaponPick.wornOut(0, 0));
        assertFalse(WeaponPick.wornOut(5, 0));
    }

    @Test
    public void theFasterWeaponWinsATieOnDamage() {
        // stone and diamond axes both hit for 9, the diamond one swings faster
        assertEquals(ItemHelper.getAttackDamage(Items.STONE_AXE), ItemHelper.getAttackDamage(Items.DIAMOND_AXE), 1e-6);
        assertTrue(ItemHelper.getAttackSpeed(Items.DIAMOND_AXE) > ItemHelper.getAttackSpeed(Items.STONE_AXE));
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(fresh(Items.STONE_AXE), fresh(Items.DIAMOND_AXE))));
        assertSame(Items.DIAMOND_AXE, WeaponPick.best(List.of(fresh(Items.DIAMOND_AXE), fresh(Items.STONE_AXE))));
    }

    @Test
    public void aCompleteTieKeepsTheFirstOneListed() {
        // the kill tasks list the hand first so equal weapons never get swapped back and forth
        assertSame(Items.WOODEN_SWORD, WeaponPick.best(List.of(fresh(Items.WOODEN_SWORD), fresh(Items.GOLDEN_SWORD))));
        assertSame(Items.GOLDEN_SWORD, WeaponPick.best(List.of(fresh(Items.GOLDEN_SWORD), fresh(Items.WOODEN_SWORD))));
    }

    @Test
    public void swingSpeedComesFromTheAttributeComponent() {
        // 4 base, a sword takes 2.4 off and an axe takes 3.2 (wooden) off
        assertEquals(4f, ItemHelper.getAttackSpeed(Items.STICK), 1e-6);
        assertEquals(1.6f, ItemHelper.getAttackSpeed(Items.IRON_SWORD), 1e-4);
        assertEquals(0.8f, ItemHelper.getAttackSpeed(Items.WOODEN_AXE), 1e-4);
    }

    @Test
    public void damageWasNotDisturbedByTheSpeedRefactor() {
        assertEquals(1f, ItemHelper.getAttackDamage(Items.STICK), 1e-6);
        assertEquals(4f, ItemHelper.getAttackDamage(Items.WOODEN_SWORD), 1e-6);
        assertEquals(7f, ItemHelper.getAttackDamage(Items.WOODEN_AXE), 1e-6);
        assertEquals(10f, ItemHelper.getAttackDamage(Items.NETHERITE_AXE), 1e-6);
    }
}
