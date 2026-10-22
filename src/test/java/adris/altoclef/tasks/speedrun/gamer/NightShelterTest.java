package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.NightShelter.Column;
import adris.altoclef.tasks.speedrun.gamer.NightShelter.Trigger;
import adris.altoclef.util.helpers.NightSleepHook;
import java.util.List;
import org.junit.Test;

// the night hole after a death: when it applies, which column, when it ends
public class NightShelterTest {

    // night, overworld, armor 0, no bed, on the surface, no recovery, no fight, free, unused, died two minutes ago
    private static Trigger base() {
        return new Trigger(true, true, 0, true, 0, false, false, false, false, 2400);
    }

    private static Trigger with(Trigger t, java.util.function.UnaryOperator<TriggerBuilder> f) {
        return f.apply(new TriggerBuilder(t)).build();
    }

    // records have no withers, a tiny mutable copy keeps the cases short
    private static final class TriggerBuilder {
        boolean window, overworld, noBed, recovery, fighting, busy, used;
        int armor, depth;
        long since;

        TriggerBuilder(Trigger t) {
            window = t.windowOpen();
            overworld = t.overworld();
            armor = t.armorPoints();
            noBed = t.noBed();
            depth = t.depthBelowSky();
            recovery = t.recoveryActive();
            fighting = t.fighting();
            busy = t.busy();
            used = t.usedThisNight();
            since = t.ticksSinceDeath();
        }

        Trigger build() {
            return new Trigger(window, overworld, armor, noBed, depth, recovery, fighting, busy, used, since);
        }
    }

    @Test
    public void aDeathTonightWithNoArmorAndNoBedOnTheSurfaceHides() {
        assertTrue(NightShelter.wanted(base()));
    }

    @Test
    public void noRecentDeathNoShelter() {
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.since = -1;
            return b;
        })));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.since = NightShelter.RECENT_DEATH_TICKS + 1;
            return b;
        })));
        assertTrue(NightShelter.wanted(with(base(), b -> {
            b.since = NightShelter.RECENT_DEATH_TICKS;
            return b;
        })));
    }

    @Test
    public void armorAtTheLineOrAboveGoesOnAsNormal() {
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.armor = NightShelter.ARMOR_LINE;
            return b;
        })));
        assertTrue(NightShelter.wanted(with(base(), b -> {
            b.armor = NightShelter.ARMOR_LINE - 1;
            return b;
        })));
    }

    @Test
    public void underGroundADayOrAnotherDimensionOrABedIsNoCase() {
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.depth = 6;
            return b;
        })));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.window = false;
            return b;
        })));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.overworld = false;
            return b;
        })));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.noBed = false;
            return b;
        })));
    }

    @Test
    public void aFightAndTheRecoveryWalkComeFirst() {
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.fighting = true;
            return b;
        })));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.recovery = true;
            return b;
        })));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.busy = true;
            return b;
        })));
    }

    // low health and a spent bed trip are not inputs at all: the answer for them is the base answer
    @Test
    public void lowHealthAndASpentBedTripDoNotBlockIt() {
        assertTrue(NightShelter.wanted(base()));
        assertEquals(10, Trigger.class.getRecordComponents().length);
        for (var c : Trigger.class.getRecordComponents()) {
            assertFalse(c.getName().toLowerCase().contains("health"));
            assertFalse(c.getName().toLowerCase().contains("spent"));
        }
    }

    @Test
    public void onceAPerNightAndTheNextNightIsFresh() {
        NightShelter.Once once = new NightShelter.Once();
        assertFalse(once.used(3));
        once.mark(3);
        assertTrue(once.used(3));
        assertFalse(once.used(4));
        assertFalse(NightShelter.wanted(with(base(), b -> {
            b.used = true;
            return b;
        })));
    }

    @Test
    public void theBedTripsBudgetIsNotTouched() {
        NightSleep.Budget budget = new NightSleep.Budget();
        budget.roll(5);
        boolean spentBefore = budget.spent(1, 1.0);
        NightShelter.Once once = new NightShelter.Once();
        once.mark(5);
        NightShelter.wanted(base());
        assertEquals(spentBefore, budget.spent(1, 1.0));
        assertFalse(budget.spent(1, 1.0));
        assertEquals(0, budget.attempts());
    }

    private static Column good() {
        return new Column(true, true, true, true, false, false, true, true, 3);
    }

    private static Column col(Column c, int what) {
        return switch (what) {
            case 0 -> new Column(false, c.midDiggable(), c.lowDiggable(), c.floorSolid(), c.floorFluid(), c.fluidNear(), c.wallsSolid(), c.openAbove(), c.capSupply());
            case 1 -> new Column(c.topDiggable(), c.midDiggable(), false, c.floorSolid(), c.floorFluid(), c.fluidNear(), c.wallsSolid(), c.openAbove(), c.capSupply());
            case 2 -> new Column(c.topDiggable(), c.midDiggable(), c.lowDiggable(), false, c.floorFluid(), c.fluidNear(), c.wallsSolid(), c.openAbove(), c.capSupply());
            case 3 -> new Column(c.topDiggable(), c.midDiggable(), c.lowDiggable(), c.floorSolid(), true, c.fluidNear(), c.wallsSolid(), c.openAbove(), c.capSupply());
            case 4 -> new Column(c.topDiggable(), c.midDiggable(), c.lowDiggable(), c.floorSolid(), c.floorFluid(), true, c.wallsSolid(), c.openAbove(), c.capSupply());
            case 5 -> new Column(c.topDiggable(), c.midDiggable(), c.lowDiggable(), c.floorSolid(), c.floorFluid(), c.fluidNear(), false, c.openAbove(), c.capSupply());
            case 6 -> new Column(c.topDiggable(), c.midDiggable(), c.lowDiggable(), c.floorSolid(), c.floorFluid(), c.fluidNear(), c.wallsSolid(), false, c.capSupply());
            default -> new Column(c.topDiggable(), c.midDiggable(), c.lowDiggable(), c.floorSolid(), c.floorFluid(), c.fluidNear(), c.wallsSolid(), c.openAbove(), NightShelter.BLOCKS_NEEDED - 1);
        };
    }

    @Test
    public void aColumnNeedsEveryPartRight() {
        assertTrue(NightShelter.columnOk(good()));
        for (int i = 0; i < 8; i++) {
            assertFalse("case " + i, NightShelter.columnOk(col(good(), i)));
        }
    }

    @Test
    public void theFirstGoodColumnWinsAndNoneIsMinusOne() {
        assertEquals(-1, NightShelter.pickColumn(List.of()));
        assertEquals(-1, NightShelter.pickColumn(List.of(col(good(), 5), col(good(), 3))));
        assertEquals(1, NightShelter.pickColumn(List.of(col(good(), 5), good(), good())));
        assertEquals(0, NightShelter.pickColumn(List.of(good(), good())));
    }

    @Test
    public void dirtIsDiggableByHandStoneNeedsAPick() {
        assertTrue(NightShelter.diggable(true, false, 0.5f, false));
        assertFalse(NightShelter.diggable(false, true, 1.5f, false));
        assertTrue(NightShelter.diggable(false, true, 1.5f, true));
        assertFalse(NightShelter.diggable(false, true, 50f, true));
        assertFalse(NightShelter.diggable(true, false, -1f, true));
        assertFalse(NightShelter.diggable(false, false, 0.5f, true));
    }

    @Test
    public void aFallingBlockIsNeverTheCap() {
        // gravel and sand first in the bag, dirt after
        assertEquals(2, NightShelter.pickCap(new boolean[]{true, true, false}, new boolean[]{true, true, true}));
        // only falling ones in the bag
        assertEquals(-1, NightShelter.pickCap(new boolean[]{true, false}, new boolean[]{true, false}));
        assertEquals(-1, NightShelter.pickCap(new boolean[]{}, new boolean[]{}));
    }

    @Test
    public void theMorningIsTheNextDayCountNotTheClockOfTheWall() {
        assertFalse(NightShelter.morning(3, 3 * 24000L + 23000));
        assertTrue(NightShelter.morning(3, 4 * 24000L));
        assertTrue(NightShelter.morning(3, 4 * 24000L + 500));
    }

    @Test
    public void theWaitCapIsWhatIsLeftOfTheNightPlusSlack() {
        assertEquals(24000 - 13000 + NightShelter.SLACK_TICKS, NightShelter.waitCap(13000));
        assertEquals(NightShelter.SLACK_TICKS + 1000, NightShelter.waitCap(23000));
        long cap = NightShelter.waitCap(14000);
        assertFalse(NightShelter.waitOver(3, 3 * 24000L + 14000, 100, cap));
        assertTrue(NightShelter.waitOver(3, 3 * 24000L + 14000, cap + 1, cap));
        assertTrue(NightShelter.waitOver(3, 4 * 24000L + 5, 100, cap));
    }

    @Test
    public void beingHitWhileSealedLiftsTheStandDown() {
        assertTrue(NightShelter.hurtWhileSealed(20, 18));
        assertFalse(NightShelter.hurtWhileSealed(18, 18));
        assertFalse(NightShelter.hurtWhileSealed(18, 20));
    }

    @Test
    public void sealedStandsDownAtAnyHealthAndGoesStale() {
        NightSleepHook.clear();
        NightSleepHook.clearSheltered();
        assertFalse(NightSleepHook.standsDown(2, 100));
        NightSleepHook.setSheltered(100);
        assertTrue(NightSleepHook.standsDown(2, 100));
        assertTrue(NightSleepHook.standsDown(20, 100 + NightSleepHook.STALE_TICKS));
        assertFalse(NightSleepHook.standsDown(20, 101 + NightSleepHook.STALE_TICKS));
        NightSleepHook.setSheltered(100);
        NightSleepHook.clear();
        assertTrue(NightSleepHook.standsDown(2, 100));
        NightSleepHook.clearSheltered();
        assertFalse(NightSleepHook.standsDown(2, 100));
    }

    // ---- dark: the shared night rule

    @Test
    public void darkNeedsTheWindowAndTheClock() {
        long w = NightSleep.EARLIEST_WINDOW;
        assertTrue(NightShelter.dark(true, w, 0));
        assertFalse(NightShelter.dark(false, w, 0));
        assertFalse(NightShelter.dark(true, w - 1, 0));
        // a daytime storm opens the sky window but the clock says day
        assertFalse(NightShelter.dark(true, 6000, 0));
        // days wrap
        assertTrue(NightShelter.dark(true, NightSleep.DAY_LENGTH * 3 + w + 5, 0));
    }

    @Test
    public void theLeadCountsTheTicksBeforeTheWindowOnlyByTheClock() {
        long w = NightSleep.EARLIEST_WINDOW;
        long lead = NightShelter.PILE_LEAD_TICKS;
        assertTrue(NightShelter.dark(false, w - lead, lead));
        assertTrue(NightShelter.dark(false, w - 1, lead));
        assertFalse(NightShelter.dark(false, w - lead - 1, lead));
        assertFalse(NightShelter.dark(false, w - 1, 0));
    }

    @Test
    public void theLeadDoesNotReachIntoTheMorning() {
        long lead = NightShelter.PILE_LEAD_TICKS;
        assertFalse(NightShelter.dark(false, 0, lead));
        assertFalse(NightShelter.dark(false, 100, lead));
        // night past the window with a bright sky is the morning
        assertFalse(NightShelter.dark(false, NightSleep.DAY_LENGTH - 10, lead));
    }
}
