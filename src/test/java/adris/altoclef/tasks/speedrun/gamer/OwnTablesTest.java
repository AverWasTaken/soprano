package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// the bot took a crafting table out of a village because the pickup was "nearest table". now only what we placed is a candidate
public class OwnTablesTest {
    private static RunState.Pos pos(int x, int y, int z) {
        return new RunState.Pos(x, y, z);
    }

    @Test
    public void aVillageTableIsNeverACandidateBecauseItWasNeverRecorded() {
        List<RunState.Pos> own = new ArrayList<>();
        // the village table sits right next to us, we have placed nothing
        assertNull(OwnTables.nearest(own, p -> true, 247.5, 63, 39.5, 10));
        // and when we did place one further away, that one is the answer even though the village one is closer
        OwnTables.record(own, pos(260, 63, 40));
        assertEquals(pos(260, 63, 40), OwnTables.nearest(own, p -> true, 247.5, 63, 39.5, 20));
    }

    @Test
    public void nearestOwnTableWins() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(10, 64, 0), pos(3, 64, 0), pos(-6, 64, 0)));
        assertEquals(pos(3, 64, 0), OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 10));
    }

    @Test
    public void usableFilterAndRadiusApply() {
        List<RunState.Pos> own = new ArrayList<>(List.of(pos(3, 64, 0), pos(8, 64, 0)));
        // the close one is written off (already tried), the far one is next
        assertEquals(pos(8, 64, 0), OwnTables.nearest(own, p -> p.x != 3, 0.5, 64, 0.5, 10));
        // nothing within the radius
        assertNull(OwnTables.nearest(own, p -> true, 0.5, 64, 0.5, 2));
        // everything filtered
        assertNull(OwnTables.nearest(own, p -> false, 0.5, 64, 0.5, 10));
    }

    @Test
    public void placementsOutsideReachAreNotOurs() {
        assertTrue(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(2, 64, 1)));
        assertTrue(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(7, 65, 0)));
        // a table that appears across the street (another player, a chunk update) is not something we placed
        assertFalse(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(20, 64, 0)));
        assertFalse(OwnTables.placedByUs(0.5, 65.6, 0.5, pos(0, 64, -30)));
    }

    // the loop from the log: place table, pickup breaks it, craft needs it, place again, ~every 6 seconds
    @Test
    public void aTableThatWasJustPlacedOrOpenedIsNotTakenBack() {
        long never = OwnTables.NEVER;
        assertTrue(OwnTables.mayStartRecovery(1000, never, never, false, 30, 120));
        // placed 5 seconds ago: the craft is about to happen
        assertFalse(OwnTables.mayStartRecovery(1000, 900, never, false, 30, 120));
        // 30 seconds after the last use it is spare again
        assertFalse(OwnTables.mayStartRecovery(1000, 401, never, false, 30, 120));
        assertTrue(OwnTables.mayStartRecovery(1000, 400, never, false, 30, 120));
        // open right now, however long ago anything else happened
        assertFalse(OwnTables.mayStartRecovery(1000, never, never, true, 30, 120));
        assertFalse(OwnTables.mayStartRecovery(100000, 0, never, true, 30, 120));
    }

    @Test
    public void oneSuccessfulPickupBuysTwoMinutesOfPeace() {
        // use cooldown long over, but we took one back 60 seconds ago
        assertFalse(OwnTables.mayStartRecovery(5000, 0, 3800, false, 30, 120));
        assertFalse(OwnTables.mayStartRecovery(5000, 0, 2601, false, 30, 120));
        assertTrue(OwnTables.mayStartRecovery(5000, 0, 2600, false, 30, 120));
        // a use at tick 0 is a real use, not "never"
        assertFalse(OwnTables.mayStartRecovery(100, 0, OwnTables.NEVER, false, 30, 120));
    }

    @Test
    public void recordDedupesAndForgetsTheOldest() {
        List<RunState.Pos> own = new ArrayList<>();
        assertTrue(OwnTables.record(own, pos(1, 1, 1)));
        assertFalse(OwnTables.record(own, pos(1, 1, 1)));
        assertEquals(1, own.size());
        for (int i = 0; i < OwnTables.CAP + 5; i++) {
            OwnTables.record(own, pos(100 + i, 64, 0));
        }
        assertEquals(OwnTables.CAP, own.size());
        assertFalse(own.contains(pos(1, 1, 1)));
        assertTrue(own.contains(pos(100 + OwnTables.CAP + 4, 64, 0)));
    }

    // the bug: craft, walk off to iron, the 30 s cooldown ran out out of range and the table stayed. now the pickup is
    // about WHICH need is running, not how long ago we touched it
    @Test
    public void tableComesBackWhenTheRunMovesToAGatheringNeed() {
        // food placed it (smoker, bread), food is still running: keep
        assertFalse(OwnTables.wantsTableBack("food", "food"));
        // food is done, iron is next: that is the boundary
        assertTrue(OwnTables.wantsTableBack("food", "iron_ingot"));
        assertTrue(OwnTables.wantsTableBack("furnace", "food"));
        assertTrue(OwnTables.wantsTableBack("iron_pickaxe", "wool"));
        // the plan ran dry: nothing will use it again
        assertTrue(OwnTables.wantsTableBack("shears", null));
        // placed outside a prep phase or before a relog, no idea who used it: still fair game at the next gathering need
        assertTrue(OwnTables.wantsTableBack(null, "iron_ingot"));
    }

    @Test
    public void aCraftNeedKeepsTheTable() {
        // wooden pickaxe placed it, stone pickaxe is a craft at that same table
        assertFalse(OwnTables.wantsTableBack("wooden_pickaxe", "stone_pickaxe"));
        assertFalse(OwnTables.wantsTableBack("iron_ingot", "iron_pickaxe"));
        assertFalse(OwnTables.wantsTableBack(null, "bucket"));
        // armor going on and the special needs are not crafts
        assertTrue(OwnTables.wantsTableBack("iron_chestplate", KitNeed.EQUIP_ARMOR));
        assertTrue(OwnTables.wantsTableBack("iron_chestplate", KitNeed.BUILD_BLOCKS));
    }

    @Test
    public void furnaceFollowsTheSameBoundaryButCraftsDoNotKeepIt() {
        // cooked food, now crafts: the furnace is dead weight, crafts use the table
        assertTrue(OwnTables.wantsFurnaceBack("food", "iron_pickaxe", false));
        assertTrue(OwnTables.wantsFurnaceBack("iron_ingot", null, false));
        // the need that smelted in it is still running
        assertFalse(OwnTables.wantsFurnaceBack("iron_ingot", "iron_ingot", false));
        assertFalse(OwnTables.wantsFurnaceBack("food", "food", false));
    }

    @Test
    public void aFurnaceAboutToSmeltIsKept() {
        assertTrue(OwnTables.smeltsSoon("iron_ingot", 3));
        // iron need but nothing raw yet: we are going mining, carry it
        assertFalse(OwnTables.smeltsSoon("iron_ingot", 0));
        assertFalse(OwnTables.smeltsSoon("food", 3));
        assertFalse(OwnTables.smeltsSoon(null, 3));
        assertFalse(OwnTables.wantsFurnaceBack("food", "iron_ingot", OwnTables.smeltsSoon("iron_ingot", 5)));
        assertTrue(OwnTables.wantsFurnaceBack("food", "iron_ingot", OwnTables.smeltsSoon("iron_ingot", 0)));
    }

    // the debounce is a few seconds now, not the 30 that let the bot wander out of range
    @Test
    public void aFewSecondsOfDebounceAfterUse() {
        long never = OwnTables.NEVER;
        assertFalse(OwnTables.mayStartRecovery(1000, 960, never, false, 3, 120));
        assertTrue(OwnTables.mayStartRecovery(1000, 940, never, false, 3, 120));
        // but the loop backstop is untouched
        assertFalse(OwnTables.mayStartRecovery(5000, 0, 2601, false, 3, 120));
        assertTrue(OwnTables.mayStartRecovery(5000, 0, 2600, false, 3, 120));
    }

    @Test
    public void craftAndGatheringNamesAddUp() {
        assertTrue(KitNeed.isCraftName("furnace"));
        assertTrue(KitNeed.isCraftName("shield"));
        assertFalse(KitNeed.isCraftName("iron_ingot"));
        assertFalse(KitNeed.isCraftName("wool"));
        assertFalse(KitNeed.isCraftName(KitNeed.FOOD));
        assertFalse(KitNeed.isCraftName(KitNeed.EQUIP_ARMOR));
        assertFalse(KitNeed.isCraftName(null));
        assertTrue(new KitNeed("bucket", 2).isCraft());
    }
}
