package adris.altoclef.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.resources.BranchOres;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

// the "<ore> [count] [y]" grammar and the ore table behind it
public class BranchMineArgsTest {

    @Test
    public void justTheOreMinesUntilStoppedAtItsUsualY() throws Exception {
        BranchMineArgs.Request r = BranchMineArgs.parse("diamond");
        assertEquals("diamond", r.ore().name());
        assertEquals(0, r.count());
        assertEquals(-59, r.y());
    }

    @Test
    public void countAndYAreOptionalAndInThatOrder() throws Exception {
        assertEquals(8, BranchMineArgs.parse("diamond 8").count());
        BranchMineArgs.Request r = BranchMineArgs.parse("diamond 8 -30");
        assertEquals(8, r.count());
        assertEquals(-30, r.y());
    }

    @Test
    public void aCountOfZeroIsHowYouGiveAYWithoutACount() throws Exception {
        BranchMineArgs.Request r = BranchMineArgs.parse("emerald 0 100");
        assertEquals(0, r.count());
        assertEquals(100, r.y());
    }

    @Test
    public void aliasesAndSpellingsWork() throws Exception {
        assertEquals("raw_iron", BranchMineArgs.parse("iron").ore().name());
        assertEquals("raw_gold", BranchMineArgs.parse("GOLD").ore().name());
        assertEquals("raw_copper", BranchMineArgs.parse("minecraft:copper").ore().name());
        assertEquals("lapis_lazuli", BranchMineArgs.parse("lapis").ore().name());
    }

    @Test
    public void emeraldHasToSayWhere() {
        ItemArgs.ParseException e = assertThrows(ItemArgs.ParseException.class, () -> BranchMineArgs.parse("emerald"));
        assertTrue(e.getMessage().contains("no usual y"));
        assertThrows(ItemArgs.ParseException.class, () -> BranchMineArgs.parse("emerald 8"));
    }

    @Test
    public void aYBelowBedrockIsPulledUp() throws Exception {
        assertEquals(-59, BranchMineArgs.parse("diamond 1 -100").y());
        assertEquals(BranchOres.HIGHEST_Y, BranchMineArgs.parse("diamond 1 9000").y());
    }

    @Test
    public void garbageIsRefused() {
        for (String bad : List.of("", "dirt", "diamond x", "diamond 1 x", "diamond 1 2 3", "diamond -59", "diamond 99999999999")) {
            assertThrows(bad, ItemArgs.ParseException.class, () -> BranchMineArgs.parse(bad));
        }
    }

    @Test
    public void theTableMatchesThePlan() {
        assertEquals(Integer.valueOf(-59), BranchOres.find("diamond").defaultY());
        assertEquals(Integer.valueOf(-59), BranchOres.find("redstone").defaultY());
        assertEquals(Integer.valueOf(-16), BranchOres.find("gold").defaultY());
        assertEquals(Integer.valueOf(0), BranchOres.find("lapis").defaultY());
        assertEquals(Integer.valueOf(16), BranchOres.find("iron").defaultY());
        assertEquals(Integer.valueOf(48), BranchOres.find("copper").defaultY());
        assertEquals(Integer.valueOf(44), BranchOres.find("coal").defaultY());
        assertNull(BranchOres.find("emerald").defaultY());
    }

    @Test
    public void tiersMatchTheCatalogue() {
        assertEquals(BranchOres.Tier.IRON, BranchOres.find("diamond").tier());
        assertEquals(BranchOres.Tier.IRON, BranchOres.find("raw_gold").tier());
        assertEquals(BranchOres.Tier.IRON, BranchOres.find("redstone").tier());
        assertEquals(BranchOres.Tier.IRON, BranchOres.find("emerald").tier());
        assertEquals(BranchOres.Tier.STONE, BranchOres.find("raw_iron").tier());
        assertEquals(BranchOres.Tier.STONE, BranchOres.find("raw_copper").tier());
        assertEquals(BranchOres.Tier.STONE, BranchOres.find("lapis_lazuli").tier());
        assertEquals(BranchOres.Tier.WOOD, BranchOres.find("coal").tier());
    }

    @Test
    public void everyOreHasBothStoneAndDeepslateBlocks() {
        for (BranchOres.Ore ore : BranchOres.all()) {
            assertEquals(2, ore.blocks().size());
            assertTrue(ore.blocks().get(0).endsWith("_ore"));
            assertTrue(ore.blocks().get(1).startsWith("deepslate_"));
            assertNotNull(ore.drop());
        }
        assertEquals(List.of("lapis_ore", "deepslate_lapis_ore"), BranchOres.find("lapis").blocks());
        assertEquals(List.of("iron_ore", "deepslate_iron_ore"), BranchOres.find("iron").blocks());
    }

    @Test
    public void noSpellingAnswersForTwoOres() {
        List<String> names = BranchOres.names();
        assertEquals(names.size(), new HashSet<>(names).size());
        assertFalse(names.contains("dirt"));
    }
}
