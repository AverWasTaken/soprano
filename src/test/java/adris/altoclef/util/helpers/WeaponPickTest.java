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
    public void wearUsesToolWearsLineLikeTheKitPlanner() {
        // the line itself is pinned in ToolWearTest, this is the ranking following it: 111 of 131 is healthy, 112 is worn
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_SWORD, 111, 131),
                new WeaponPick.Candidate(Items.STONE_AXE, 112, 131))));
        assertSame(Items.STONE_SWORD, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_AXE, 112, 131),
                new WeaponPick.Candidate(Items.STONE_SWORD, 111, 131))));
        // an item that does not wear (max 0) is never worn, however the damage reads
        assertSame(Items.STONE_AXE, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_AXE, 5, 0))));
        assertSame(Items.STONE_AXE, WeaponPick.best(List.of(new WeaponPick.Candidate(Items.STONE_SWORD, 112, 131),
                new WeaponPick.Candidate(Items.STONE_AXE, 5, 0))));
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

    // ---- crowds

    private static Item crowdPick(Candidate... candidates) {
        return WeaponPick.best(List.of(candidates), true, false);
    }

    @Test
    public void inACrowdDamagePerSecondDecides() {
        // wooden axe 7 x 0.8 = 5.6, stone sword 5 x 1.6 = 8
        assertSame(Items.STONE_SWORD, crowdPick(fresh(Items.WOODEN_AXE), fresh(Items.STONE_SWORD)));
        assertSame(Items.STONE_SWORD, crowdPick(fresh(Items.STONE_SWORD), fresh(Items.WOODEN_AXE)));
        // and the same two alone are the axe, per swing
        assertSame(Items.WOODEN_AXE, WeaponPick.best(List.of(fresh(Items.WOODEN_AXE), fresh(Items.STONE_SWORD))));
        // iron axe 9 x 0.9 = 8.1 against iron sword 6 x 1.6 = 9.6
        assertSame(Items.IRON_SWORD, crowdPick(fresh(Items.IRON_AXE), fresh(Items.IRON_SWORD)));
        assertSame(Items.IRON_AXE, WeaponPick.best(List.of(fresh(Items.IRON_SWORD), fresh(Items.IRON_AXE))));
    }

    @Test
    public void inACrowdTheOnlyWeaponIsStillTheOnlyWeapon() {
        // the kit makes axes now, a bot with no sword on it just swings the axe
        assertSame(Items.IRON_AXE, crowdPick(fresh(Items.IRON_AXE)));
        assertNull(WeaponPick.best(List.of(fresh(Items.STICK)), true, false));
    }

    @Test
    public void aWornSwordStillLosesInACrowd() {
        assertSame(Items.WOODEN_AXE, crowdPick(worn(Items.DIAMOND_SWORD), fresh(Items.WOODEN_AXE)));
    }

    @Test
    public void aTieOnDpsFallsBackToTheHarderHitAndThenTheHand() {
        // the same weapon twice: first listed stays
        assertSame(Items.WOODEN_SWORD, crowdPick(fresh(Items.WOODEN_SWORD), fresh(Items.GOLDEN_SWORD)));
    }

    @Test
    public void aRaisedShieldWantsTheAxeEvenInACrowd() {
        assertSame(Items.IRON_AXE, WeaponPick.best(List.of(fresh(Items.IRON_SWORD), fresh(Items.IRON_AXE)), true, true));
        assertSame(Items.WOODEN_AXE, WeaponPick.best(List.of(fresh(Items.DIAMOND_SWORD), fresh(Items.WOODEN_AXE)), false, true));
        // no axe, no problem
        assertSame(Items.IRON_SWORD, WeaponPick.best(List.of(fresh(Items.IRON_SWORD)), false, true));
    }

    @Test
    public void swingSpeedComesFromTheAttributeComponent() {
        // 4 base, a sword takes 2.4 off and an axe takes 3.2 (wooden) off
        assertEquals(4f, ItemHelper.getAttackSpeed(Items.STICK), 1e-6);
        assertEquals(1.6f, ItemHelper.getAttackSpeed(Items.IRON_SWORD), 1e-4);
        assertEquals(0.8f, ItemHelper.getAttackSpeed(Items.WOODEN_AXE), 1e-4);
    }

    // ---- armed means anything better than fists

    @Test
    public void aPickaxeOnlyBagIsNotAWeaponButItIsAToolThatBeatsFists() {
        assertNull(WeaponPick.best(List.of(fresh(Items.IRON_PICKAXE))));
        assertSame(Items.IRON_PICKAXE, WeaponPick.bestTool(List.of(fresh(Items.IRON_PICKAXE))));
        assertSame(Items.IRON_PICKAXE, WeaponPick.bestOrTool(List.of(fresh(Items.IRON_PICKAXE)), false, false));
        assertTrue(ItemHelper.hitsHarderThanFists(Items.WOODEN_PICKAXE));
    }

    @Test
    public void shovelsCountToo() {
        assertSame(Items.WOODEN_SHOVEL, WeaponPick.bestOrTool(List.of(fresh(Items.WOODEN_SHOVEL)), false, false));
    }

    @Test
    public void aBagWithNothingBetterThanFistsIsUnarmed() {
        assertNull(WeaponPick.bestOrTool(List.of(), false, false));
        assertNull(WeaponPick.bestOrTool(List.of(fresh(Items.STICK), fresh(Items.BREAD), fresh(Items.DIRT), fresh(Items.COBBLESTONE)), false, false));
        assertFalse(ItemHelper.hitsHarderThanFists(Items.STICK));
        assertFalse(ItemHelper.hitsHarderThanFists(Items.AIR));
    }

    @Test
    public void anyWeaponBeatsAnyToolAndTheHarderToolBeatsTheSofterOne() {
        assertSame(Items.WOODEN_SWORD, WeaponPick.bestOrTool(List.of(fresh(Items.DIAMOND_PICKAXE), fresh(Items.WOODEN_SWORD)), false, false));
        assertSame(Items.IRON_PICKAXE, WeaponPick.bestTool(List.of(fresh(Items.WOODEN_PICKAXE), fresh(Items.IRON_PICKAXE))));
    }

    @Test
    public void aWornToolIsNotOnTheListAndTheHandWinsTies() {
        assertSame(Items.WOODEN_SHOVEL, WeaponPick.bestTool(List.of(worn(Items.DIAMOND_PICKAXE), fresh(Items.WOODEN_SHOVEL))));
        assertSame(Items.STONE_PICKAXE, WeaponPick.bestTool(List.of(fresh(Items.STONE_PICKAXE), fresh(Items.STONE_PICKAXE))));
        // and a worn one is not worth a fight when it is all there is: the last uses of the only pick are worth more
        assertNull(WeaponPick.bestTool(List.of(worn(Items.DIAMOND_PICKAXE))));
        assertNull(WeaponPick.bestOrTool(List.of(worn(Items.IRON_PICKAXE)), false, false));
    }

    // the pure rules end to end: what the bag says about being armed is what the machine fights or runs on
    private static CombatCommit.Event meetAZombie(boolean armed) {
        CombatCommit.Foe zombie = new CombatCommit.Foe(1, 1.5, false, false, 2);
        return new CombatCommit().step(new CombatCommit.Tick(100, 20, armed, 0, 0, 3, List.of(zombie), null, false, false));
    }

    @Test
    public void aBotWithOnlyAPickaxeFightsALoneZombieThatHitsIt() {
        boolean armed = WeaponPick.bestOrTool(List.of(fresh(Items.IRON_PICKAXE)), false, false) != null;
        assertTrue(armed);
        assertEquals(CombatCommit.Event.FIGHT_START, meetAZombie(armed));
    }

    @Test
    public void aBotWithNothingInTheBagRunsFromIt() {
        boolean armed = WeaponPick.bestOrTool(List.of(fresh(Items.STICK)), false, false) != null;
        assertFalse(armed);
        assertEquals(CombatCommit.Event.RUN_START, meetAZombie(armed));
    }

    @Test
    public void damageWasNotDisturbedByTheSpeedRefactor() {
        assertEquals(1f, ItemHelper.getAttackDamage(Items.STICK), 1e-6);
        assertEquals(4f, ItemHelper.getAttackDamage(Items.WOODEN_SWORD), 1e-6);
        assertEquals(7f, ItemHelper.getAttackDamage(Items.WOODEN_AXE), 1e-6);
        assertEquals(10f, ItemHelper.getAttackDamage(Items.NETHERITE_AXE), 1e-6);
    }
}
