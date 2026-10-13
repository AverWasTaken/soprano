package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.NightSleep.BedSource;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Inputs;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Mob;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Next;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Prep;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Refusal;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Stage;
import adris.altoclef.tasks.speedrun.gamer.NightSleep.Why;
import adris.altoclef.util.helpers.NightSleepHook;
import java.util.List;
import org.junit.After;
import org.junit.Test;

// when the night trip starts, how high the bed has to be to be out of the game's monster box, what a night may cost, and when
// mob defense stands down. the walking and the clicking are NightSleepTask and are not in here
public class NightSleepTest {
    // a zombie standing at x z with its feet at y (0.6 wide, 1.95 tall)
    private static Mob zombie(double x, double y, double z) {
        return new Mob(x - 0.3, y, z - 0.3, x + 0.3, y + 1.95, z + 0.3);
    }

    private static Inputs inputs(boolean enabled, boolean overworld, boolean dark, boolean busy, boolean fighting, boolean spent,
                                 boolean cooling, BedSource bed, boolean leftover) {
        return new Inputs(enabled, overworld, dark, busy, fighting, false, spent, cooling, bed, leftover);
    }

    private static Inputs hurt(BedSource bed, boolean leftover) {
        return new Inputs(true, true, true, false, false, true, false, false, bed, leftover);
    }

    private static Inputs night(BedSource bed) {
        return inputs(true, true, true, false, false, false, false, bed, false);
    }

    @After
    public void clearHook() {
        NightSleepHook.clear();
    }

    // ---- when it applies

    @Test
    public void aBedAtNightIsAGo() {
        assertEquals(Why.GO, NightSleep.decide(night(BedSource.BAG)));
        assertEquals(Why.GO, NightSleep.decide(night(BedSource.CRAFT)));
        assertEquals(Why.GO, NightSleep.decide(night(BedSource.FOUND)));
    }

    @Test
    public void noBedMeansNothingChanges() {
        assertEquals(Why.NO_BED, NightSleep.decide(night(BedSource.NONE)));
    }

    @Test
    public void daytimeIsNotTheTime() {
        assertEquals(Why.NOT_NIGHT, NightSleep.decide(inputs(true, true, false, false, false, false, false, BedSource.BAG, false)));
    }

    @Test
    public void theSettingAndTheDimensionComeFirst() {
        assertEquals(Why.OFF, NightSleep.decide(inputs(false, true, true, false, false, false, false, BedSource.BAG, false)));
        assertEquals(Why.WRONG_PLACE, NightSleep.decide(inputs(true, false, true, false, false, false, false, BedSource.BAG, false)));
    }

    @Test
    public void busyStatesAndFightsKeepItOut() {
        assertEquals(Why.BUSY, NightSleep.decide(inputs(true, true, true, true, false, false, false, BedSource.BAG, false)));
        assertEquals(Why.FIGHTING, NightSleep.decide(inputs(true, true, true, false, true, false, false, BedSource.BAG, false)));
    }

    @Test
    public void aSpentNightAndACooldownAreQuiet() {
        assertEquals(Why.QUIET, NightSleep.decide(inputs(true, true, true, false, false, true, false, BedSource.BAG, false)));
        assertEquals(Why.QUIET, NightSleep.decide(inputs(true, true, true, false, false, false, true, BedSource.BAG, false)));
    }

    @Test
    public void lowHealthKeepsEveryTripOutNotJustTheNightOnes() {
        // a trip would give up on its first tick and start again on the next, with the phase never getting a turn
        assertEquals(Why.LOW_HEALTH, NightSleep.decide(hurt(BedSource.BAG, false)));
        assertEquals(Why.LOW_HEALTH, NightSleep.decide(hurt(BedSource.NONE, true)));
    }

    @Test
    public void aBedOfOursLeftStandingIsTakenBackEvenInTheDay() {
        assertEquals(Why.LEFTOVER, NightSleep.decide(inputs(true, true, false, false, false, false, false, BedSource.NONE, true)));
        // but not in the middle of a fight, in a busy moment, or in a tight loop after a failure
        assertEquals(Why.FIGHTING, NightSleep.decide(inputs(true, true, false, false, true, false, false, BedSource.NONE, true)));
        assertEquals(Why.BUSY, NightSleep.decide(inputs(true, true, false, true, false, false, false, BedSource.NONE, true)));
        assertEquals(Why.QUIET, NightSleep.decide(inputs(true, true, false, false, false, false, true, BedSource.NONE, true)));
        // a spent night does not stop the pick up, only a cooldown does
        assertEquals(Why.LEFTOVER, NightSleep.decide(inputs(true, true, true, false, false, true, false, BedSource.NONE, true)));
    }

    @Test
    public void onlySomePhasesMayBeInterrupted() {
        for (GamerPhase ok : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.EYES, GamerPhase.RETURN,
                GamerPhase.LOCATE, GamerPhase.ROOM, GamerPhase.OPEN}) {
            assertTrue(ok.name(), NightSleep.phaseAllowed(ok));
        }
        // lava being cast, the nether, the spawn bed by the portal, the dragon, and the ends of a run
        for (GamerPhase no : new GamerPhase[]{GamerPhase.PORTAL, GamerPhase.NETHER, GamerPhase.END_PREP, GamerPhase.DRAGON,
                GamerPhase.DONE, GamerPhase.STUCK}) {
            assertFalse(no.name(), NightSleep.phaseAllowed(no));
        }
    }

    @Test
    public void whereTheBedComesFrom() {
        // a standing bed first, then one in the bag, then a craft from three wool and three planks, never a hunt
        assertEquals(BedSource.FOUND, NightSleep.bedSource(true, 1, 9, 9));
        assertEquals(BedSource.BAG, NightSleep.bedSource(false, 1, 0, 0));
        assertEquals(BedSource.CRAFT, NightSleep.bedSource(false, 0, 3, 3));
        assertEquals(BedSource.NONE, NightSleep.bedSource(false, 0, 2, 12));
        assertEquals(BedSource.NONE, NightSleep.bedSource(false, 0, 12, 2));
        assertEquals(BedSource.NONE, NightSleep.bedSource(false, 0, 0, 0));
    }

    @Test
    public void aNightIsOneDayCount() {
        assertEquals(0, NightSleep.nightOf(12542));
        assertEquals(0, NightSleep.nightOf(23459));
        assertEquals(1, NightSleep.nightOf(24000));
        assertEquals(1, NightSleep.nightOf(24000 + 13000));
    }

    // ---- the game's box

    @Test
    public void theBoxIsEightFlatAndFiveUpAndDown() {
        // head block at 0, 64, 0: centre of the bottom face is 0.5, 64, 0.5
        assertTrue(NightSleep.blocked(0, 64, 0, List.of(zombie(0.5, 64, 7.5))));
        // one whose near side is past 8 from the centre is out of it (9.4 - 0.3 is 8.6 away)
        assertFalse(NightSleep.blocked(0, 64, 0, List.of(zombie(0.5, 64, 9.4))));
        // five up: its feet at 69 are on the edge and out, 68.9 is in
        assertFalse(NightSleep.blocked(0, 64, 0, List.of(zombie(0.5, 69, 0.5))));
        assertTrue(NightSleep.blocked(0, 64, 0, List.of(zombie(0.5, 68.9, 0.5))));
        // five down: its head (feet + 1.95) has to clear 59
        assertTrue(NightSleep.blocked(0, 64, 0, List.of(zombie(0.5, 57.1, 0.5))));
        assertFalse(NightSleep.blocked(0, 64, 0, List.of(zombie(0.5, 57.0, 0.5))));
        assertFalse(NightSleep.blocked(0, 64, 0, List.of()));
    }

    @Test
    public void theSlackWidensTheFlatPartOnly() {
        Mob far = zombie(0.5, 64, 9.5);
        assertFalse(NightSleep.blocked(0, 64, 0, List.of(far)));
        assertTrue(NightSleep.blocked(0, 64, 0, List.of(far), 2));
        Mob high = zombie(0.5, 70, 9.5);
        assertFalse(NightSleep.blocked(0, 64, 0, List.of(high), 2));
    }

    // ---- how high

    @Test
    public void aClearSpotNeedsNoPillar() {
        assertEquals(0, NightSleep.blocksToRaise(0, 64, 0, List.of(), 1, 16));
        // a zombie well outside the box, even with the slack
        assertEquals(0, NightSleep.blocksToRaise(0, 64, 0, List.of(zombie(0.5, 64, 14.5)), 1, 16));
    }

    @Test
    public void aZombieBelowTheBedPutsTheBottomOfTheBoxAboveItsHead() {
        // feet 64, head 65.95: the box bottom (bed - 5) has to be past 65.95 + 1 margin, so the bed goes to 72 (8 up)
        int n = NightSleep.blocksToRaise(0, 64, 0, List.of(zombie(3.5, 64, 3.5)), 1, 16);
        assertEquals(8, n);
        // and that really is clear
        assertFalse(NightSleep.blocked(0, 64 + n, 0, List.of(zombie(3.5, 64, 3.5)), NightSleep.HEAD_REACH));
    }

    @Test
    public void theHighestMonsterDecidesAndTheMarginMoves() {
        List<Mob> mobs = List.of(zombie(2.5, 64, 2.5), zombie(5.5, 66, 5.5));
        assertEquals(10, NightSleep.blocksToRaise(0, 64, 0, mobs, 1, 16));
        assertEquals(9, NightSleep.blocksToRaise(0, 64, 0, mobs, 0, 16));
        assertEquals(12, NightSleep.blocksToRaise(0, 64, 0, mobs, 3, 16));
    }

    @Test
    public void tooHighForTheCapIsImpossible() {
        // a zombie on a ledge four up wants 12 blocks, the cap is 10
        assertEquals(-1, NightSleep.blocksToRaise(0, 64, 0, List.of(zombie(3.5, 68, 3.5)), 1, 10));
        assertEquals(12, NightSleep.blocksToRaise(0, 64, 0, List.of(zombie(3.5, 68, 3.5)), 1, 12));
    }

    @Test
    public void aMonsterAboveTheBoxIsNotClimbedOverButIsNotWalkedIntoEither() {
        // the skeleton on the ledge is outside the box now (feet 75, box top 69)...
        Mob skeleton = zombie(3.5, 75, 3.5);
        Mob ground = zombie(2.5, 64, 2.5);
        assertEquals(0, NightSleep.blocksToRaise(0, 64, 0, List.of(skeleton), 1, 16));
        // ...but going up 8 puts it in the box (feet 75 is under 72 + 5), so the pillar would not help
        assertEquals(-1, NightSleep.blocksToRaise(0, 64, 0, List.of(ground, skeleton), 1, 16));
    }

    @Test
    public void theColumnNeedsRoomAndBlocks() {
        // the pillar's own cells, ours, and one to jump into
        assertFalse(NightSleep.roofAt(8, 10));
        assertTrue(NightSleep.roofAt(8, 9));
        // the pillar, a block under the foot and one under the head, and a spare
        assertEquals(11, NightSleep.blocksNeeded(8));
        assertEquals(3, NightSleep.blocksNeeded(0));
    }

    // ---- what a night may cost

    @Test
    public void aNightHasTriesAndSeconds() {
        NightSleep.Budget b = new NightSleep.Budget();
        b.roll(3);
        assertFalse(b.spent(3, 150));
        b.began();
        b.began();
        assertFalse(b.spent(3, 150));
        b.began();
        assertTrue(b.spent(3, 150));
        NightSleep.Budget t = new NightSleep.Budget();
        t.roll(3);
        t.spend(149.9);
        assertFalse(t.spent(3, 150));
        t.spend(0.2);
        assertTrue(t.spent(3, 150));
    }

    @Test
    public void aFailureCoolsAndTheNextNightStartsClean() {
        NightSleep.Budget b = new NightSleep.Budget();
        b.roll(3);
        b.began();
        b.failed(100, 20);
        assertTrue(b.cooling(110));
        assertTrue(b.quiet(110, 3, 150));
        assertFalse(b.cooling(120.1));
        assertFalse(b.quiet(120.1, 3, 150));
        // the same night keeps what it spent...
        b.roll(3);
        assertEquals(1, b.attempts());
        // ...the next one does not
        b.began();
        b.began();
        b.spend(100);
        b.roll(4);
        assertEquals(0, b.attempts());
        assertEquals(0, b.seconds(), 0);
        assertFalse(b.quiet(110, 3, 150));
    }

    @Test
    public void aNightSleptThroughIsDoneForTheNight() {
        NightSleep.Budget b = new NightSleep.Budget();
        b.roll(7);
        b.began();
        b.sleptOk();
        assertTrue(b.spent(3, 150));
    }

    @Test
    public void everyStageHasAClockExceptSleeping() {
        assertFalse(NightSleep.timedOut(Stage.SLEEP, 0, 100000));
        assertFalse(NightSleep.timedOut(Stage.LIFT, 8, 40));
        assertTrue(NightSleep.timedOut(Stage.LIFT, 8, 40.1));
        // a taller pillar gets longer, a one block one still gets the base
        assertTrue(NightSleep.stageSeconds(Stage.LIFT, 12) > NightSleep.stageSeconds(Stage.LIFT, 2));
        assertTrue(NightSleep.timedOut(Stage.PICKUP, 0, 26));
        assertTrue(NightSleep.timedOut(Stage.DESCEND, 0, 11));
    }

    // ---- mob defense

    @Test
    public void mobDefenseStandsDownFromThePillarToTheBedBackInTheBag() {
        for (Stage s : new Stage[]{Stage.LIFT, Stage.BUILD, Stage.SLEEP, Stage.PICKUP}) {
            assertTrue(s.name(), NightSleep.standsDown(s, 20));
        }
        // the walk to a bed (also the walk back to an old one), the craft and the way down are ordinary travel
        for (Stage s : new Stage[]{Stage.CRAFT, Stage.WALK, Stage.RETURN, Stage.DESCEND}) {
            assertFalse(s.name(), NightSleep.standsDown(s, 20));
        }
    }

    @Test
    public void lowHealthHandsTheWheelBack() {
        assertTrue(NightSleep.standsDown(Stage.LIFT, 8.5f));
        assertFalse(NightSleep.standsDown(Stage.LIFT, 8f));
        assertFalse(NightSleep.standsDown(Stage.SLEEP, 3f));
        assertTrue(NightSleep.shouldAbandon(8f));
        assertFalse(NightSleep.shouldAbandon(9f));
    }

    @Test
    public void theHookCarriesTheStageAndForgetsIt() {
        assertFalse(NightSleepHook.standsDown(20));
        NightSleepHook.set(Stage.BUILD);
        assertTrue(NightSleepHook.standsDown(20));
        assertFalse(NightSleepHook.standsDown(5));
        NightSleepHook.set(Stage.DESCEND);
        assertFalse(NightSleepHook.standsDown(20));
        NightSleepHook.set(Stage.SLEEP);
        NightSleepHook.clear();
        assertNull(NightSleepHook.stage());
        assertFalse(NightSleepHook.standsDown(20));
    }

    // ---- refusals and waking

    @Test
    public void theGamesOwnRefusalsAreRecognised() {
        assertEquals(Refusal.MONSTERS, NightSleep.refusal("You may not rest now; there are monsters nearby"));
        assertEquals(Refusal.NOT_NOW, NightSleep.refusal("You can sleep only at night or during thunderstorms"));
        assertEquals(Refusal.TOO_FAR, NightSleep.refusal("You may not rest now; the bed is too far away"));
        assertEquals(Refusal.OBSTRUCTED, NightSleep.refusal("This bed is obstructed"));
        assertEquals(Refusal.OCCUPIED, NightSleep.refusal("This bed is occupied"));
    }

    @Test
    public void anUnknownBedLineIsAFailureAndAnUnrelatedOneIsNothing() {
        assertEquals(Refusal.OTHER, NightSleep.refusal("You may not rest now; something new"));
        assertEquals(Refusal.NONE, NightSleep.refusal("Respawn point set"));
        // the server's own sleep status lines are not the bed saying no
        assertEquals(Refusal.NONE, NightSleep.refusal("1/2 players sleeping"));
        assertEquals(Refusal.NONE, NightSleep.refusal("Sleeping through this night"));
        assertEquals(Refusal.NONE, NightSleep.refusal("Now playing: C418 - 13"));
        assertEquals(Refusal.NONE, NightSleep.refusal(null));
    }

    @Test
    public void monstersGetOneHigherTryAndNothingElseGetsASecondClick() {
        assertEquals(Next.HIGHER, NightSleep.afterRefusal(Refusal.MONSTERS, 0, 0));
        assertEquals(Next.GIVE_UP, NightSleep.afterRefusal(Refusal.MONSTERS, 1, 0));
        assertEquals(Next.CLOSER, NightSleep.afterRefusal(Refusal.TOO_FAR, 0, 0));
        assertEquals(Next.GIVE_UP, NightSleep.afterRefusal(Refusal.TOO_FAR, 0, 1));
        for (Refusal r : new Refusal[]{Refusal.NOT_NOW, Refusal.OBSTRUCTED, Refusal.OCCUPIED, Refusal.OTHER}) {
            assertEquals(r.name(), Next.GIVE_UP, NightSleep.afterRefusal(r, 0, 0));
        }
    }

    // ---- a bed ready before dusk

    private static final long CRAFT_LEAD = 2400;
    private static final long WOOL_LEAD = 4800;

    private static NightSleep.PrepInputs prepAt(long timeOfDay, int wool, int wood, boolean sheep) {
        return new NightSleep.PrepInputs(true, true, true, false, false, false, false, false, timeOfDay, false, wool, wood, 3, sheep, false, false);
    }

    private static NightSleep.PrepInputs withBed(NightSleep.PrepInputs in) {
        return new NightSleep.PrepInputs(in.enabled(), in.overworld(), in.phaseOk(), in.busy(), in.fighting(), in.lowHealth(), in.nightSpent(),
                in.windowOpen(), in.timeOfDay(), true, in.bestWoolStack(), in.wood(), in.woodNeeded(), in.sheepNear(), in.woolGaveUp(), in.craftGaveUp());
    }

    private static NightSleep.PrepInputs with(NightSleep.PrepInputs in, boolean enabled, boolean overworld, boolean phaseOk, boolean busy,
                                              boolean fighting, boolean lowHealth, boolean spent, boolean windowOpen) {
        return new NightSleep.PrepInputs(enabled, overworld, phaseOk, busy, fighting, lowHealth, spent, windowOpen, in.timeOfDay(), in.bedReady(),
                in.bestWoolStack(), in.wood(), in.woodNeeded(), in.sheepNear(), in.woolGaveUp(), in.craftGaveUp());
    }

    private static NightSleep.Prep prep(NightSleep.PrepInputs in) {
        return NightSleep.prep(in, CRAFT_LEAD, WOOL_LEAD);
    }

    @Test
    public void withTheMaterialsTheBedIsCraftedInTheLeadBeforeDusk() {
        // the earliest window is 12010: a craft lead of 2400 opens at 9610
        assertEquals(Prep.TOO_EARLY, prep(prepAt(9609, 3, 6, false)));
        assertEquals(Prep.CRAFT, prep(prepAt(9610, 3, 6, false)));
        assertEquals(Prep.CRAFT, prep(prepAt(12400, 5, 3, false)));
        // the prep is over at 13000 whatever the sky did
        assertEquals(Prep.TOO_LATE, prep(prepAt(13000, 3, 6, false)));
    }

    @Test
    public void withoutWoolItGoesForSheepFromTheEarlierLeadOnlyWhenSomeAreKnown() {
        assertEquals(Prep.TOO_EARLY, prep(prepAt(7209, 0, 6, true)));
        assertEquals(Prep.WOOL, prep(prepAt(7210, 0, 6, true)));
        assertEquals(Prep.WOOL, prep(prepAt(7210, 2, 6, true)));
        assertEquals(Prep.NO_SHEEP, prep(prepAt(7210, 0, 6, false)));
        // wool already in the bag needs no sheep, and nothing is early about it once the craft lead is there
        assertEquals(Prep.TOO_EARLY, prep(prepAt(7210, 3, 6, false)));
    }

    @Test
    public void aBedAlreadyThereOrNoWoodIsNothingToDo() {
        assertEquals(Prep.BED_READY, prep(withBed(prepAt(11000, 3, 6, true))));
        assertEquals(Prep.BED_READY, prep(withBed(prepAt(8000, 0, 0, false))));
        // a bed needs three planks (or a log's four) and a table's four more when there is none
        assertEquals(Prep.NO_WOOD, prep(prepAt(11000, 3, 2, true)));
        assertEquals(Prep.NO_WOOD, prep(new NightSleep.PrepInputs(true, true, true, false, false, false, false, false, 11000, false, 3, 6, 7, true, false, false)));
        assertEquals(Prep.CRAFT, prep(new NightSleep.PrepInputs(true, true, true, false, false, false, false, false, 11000, false, 3, 7, 7, true, false, false)));
    }

    @Test
    public void thePrepGatesAreTheTripsGates() {
        NightSleep.PrepInputs base = prepAt(11000, 3, 6, true);
        assertEquals(Prep.OFF, prep(with(base, false, true, true, false, false, false, false, false)));
        assertEquals(Prep.WRONG_PLACE, prep(with(base, true, false, true, false, false, false, false, false)));
        assertEquals(Prep.WRONG_PHASE, prep(with(base, true, true, false, false, false, false, false, false)));
        assertEquals(Prep.BUSY, prep(with(base, true, true, true, true, false, false, false, false)));
        assertEquals(Prep.FIGHTING, prep(with(base, true, true, true, false, true, false, false, false)));
        assertEquals(Prep.LOW_HEALTH, prep(with(base, true, true, true, false, false, true, false, false)));
        assertEquals(Prep.SPENT, prep(with(base, true, true, true, false, false, false, true, false)));
        // once the window is open the trip has it
        assertEquals(Prep.TOO_LATE, prep(with(base, true, true, true, false, false, false, false, true)));
    }

    @Test
    public void aWholeEveningGaveUpStaysQuiet() {
        NightSleep.PrepInputs woolOut = new NightSleep.PrepInputs(true, true, true, false, false, false, false, false, 10000, false, 0, 6, 3, true, true, false);
        assertEquals(Prep.GAVE_UP, prep(woolOut));
        NightSleep.PrepInputs craftOut = new NightSleep.PrepInputs(true, true, true, false, false, false, false, false, 11000, false, 3, 6, 3, true, false, true);
        assertEquals(Prep.GAVE_UP, prep(craftOut));
    }

    @Test
    public void theWoolColourIsTheOneWeHoldMostOfThatTheSheepCanFinish() {
        int[] held = new int[16];
        int[] near = new int[16];
        // nothing held, four white sheep and two black: white
        near[0] = 4;
        near[1] = 2;
        assertEquals(0, NightSleep.woolColour(held, near));
        // two black wool in the bag and a black sheep makes black the shorter trip
        held[1] = 2;
        near[1] = 1;
        assertEquals(1, NightSleep.woolColour(held, near));
        // not enough of anything: two sheep and nothing held
        assertEquals(-1, NightSleep.woolColour(new int[16], one(0, 2)));
        // a stack of three needs no sheep at all
        assertEquals(5, NightSleep.woolColour(one(5, 3), new int[16]));
    }

    private static int[] one(int slot, int n) {
        int[] a = new int[16];
        a[slot] = n;
        return a;
    }

    @Test
    public void aDayClockRunsOutAndStartsOverTheNextDay() {
        NightSleep.DayClock c = new NightSleep.DayClock();
        c.roll(2);
        assertFalse(c.over(150));
        c.spend(149.9);
        assertFalse(c.over(150));
        c.spend(0.2);
        assertTrue(c.over(150));
        c.roll(2);
        assertTrue(c.over(150));
        c.roll(3);
        assertFalse(c.over(150));
        c.giveUp();
        assertTrue(c.over(150));
    }

    @Test
    public void withABedInTheBagTheTripStartsOnTheFirstTickTheWindowOpens() {
        // the tick before the window the answer is not night, the tick it opens (with nothing in the way) it is a go
        assertEquals(Why.NOT_NIGHT, NightSleep.decide(inputs(true, true, false, false, false, false, false, BedSource.BAG, false)));
        assertEquals(Why.GO, NightSleep.decide(inputs(true, true, true, false, false, false, false, BedSource.BAG, false)));
    }

    @Test
    public void onlyWhatIsReallyHappeningHoldsTheTripBack() {
        assertEquals(NightSleep.Busy.NONE, NightSleep.busy(true, false, false, false, false));
        assertEquals(NightSleep.Busy.PHASE, NightSleep.busy(false, false, false, false, false));
        assertEquals(NightSleep.Busy.PICKUP, NightSleep.busy(true, true, false, false, false));
        assertEquals(NightSleep.Busy.LOAD, NightSleep.busy(true, false, true, false, false));
        assertEquals(NightSleep.Busy.EARLY_LOAD, NightSleep.busy(true, false, false, true, false));
        assertEquals(NightSleep.Busy.SCREEN, NightSleep.busy(true, false, false, false, true));
        // the phase comes first, and a batch that merely exists (not loading) is not in the list at all
        assertEquals(NightSleep.Busy.PHASE, NightSleep.busy(false, true, true, true, true));
    }

    @Test
    public void aScreenThatStaysUpForGoodStopsHoldingTheNight() {
        assertTrue(NightSleep.screenHolds(0));
        assertTrue(NightSleep.screenHolds(NightSleep.SCREEN_PATIENCE_TICKS - 1));
        assertFalse(NightSleep.screenHolds(NightSleep.SCREEN_PATIENCE_TICKS));
    }

    @Test
    public void theReasonIsSaidOnceAndAgainWhenItChanges() {
        NightSleep.Said said = new NightSleep.Said();
        assertTrue(said.worthSaying("a", 100, 20));
        // the same answer every tick is not news
        assertFalse(said.worthSaying("a", 101, 20));
        assertFalse(said.worthSaying("a", 5000, 20));
        // a change is, but a flapping gate does not print every other tick: the held back one comes out when the gap is up
        assertFalse(said.worthSaying("b", 110, 20));
        assertTrue(said.worthSaying("b", 120, 20));
        assertFalse(said.worthSaying("b", 121, 20));
        assertTrue(said.worthSaying("a", 140, 20));
    }

    // ---- the wake grace

    @Test
    public void aNightTheServerSkippedIsSleptThroughEvenIfTheClientStillReadsNight() {
        // lay down at 13000, the flag clears the tick the server sets 24000 but our copy of the time is a packet behind...
        assertEquals(NightSleep.Wake.WAIT, NightSleep.wake(110, false, 13000, 13110, 0, 30));
        assertEquals(NightSleep.Wake.WAIT, NightSleep.wake(110, false, 13000, 13110, 29, 30));
        // ...and then it catches up, by the day flag or by the day count rolling over
        assertEquals(NightSleep.Wake.SLEPT, NightSleep.wake(110, true, 13000, 13110, 5, 30));
        assertEquals(NightSleep.Wake.SLEPT, NightSleep.wake(110, false, 13000, 24000, 5, 30));
        // the very first tick can already be the morning
        assertEquals(NightSleep.Wake.SLEPT, NightSleep.wake(110, false, 13000, 24000, 0, 30));
    }

    @Test
    public void aHitThatWokeUsStillCountsAsAFailedTryOnceTheGraceIsUp() {
        assertEquals(NightSleep.Wake.WOKEN, NightSleep.wake(110, false, 13000, 13150, 30, 30));
        assertEquals(NightSleep.Wake.WOKEN, NightSleep.wake(900, false, 13000, 13900, 400, 30));
        // never lay down at all
        assertEquals(NightSleep.Wake.WOKEN, NightSleep.wake(0, true, 13000, 24000, 50, 30));
    }

    @Test
    public void aRainyNightThatEndsAtDawnRollsTheDayCountToo() {
        // laid down at 12100 in the rain, the skip lands on the next multiple of 24000
        assertEquals(NightSleep.Wake.SLEPT, NightSleep.wake(40, false, 12100, 24000, 0, 30));
        // woken at 23999 by a zombie is still the same night
        assertEquals(NightSleep.Wake.WAIT, NightSleep.wake(40, false, 12100, 23999, 0, 30));
        assertEquals(NightSleep.Wake.WOKEN, NightSleep.wake(40, false, 12100, 23999, 31, 30));
    }

    @Test
    public void sleptThroughMeansWokenByTheMorning() {
        assertTrue(NightSleep.sleptThrough(3, true));
        // woken by a zombie after a few seconds in the dark, or after a long time with other players awake
        assertFalse(NightSleep.sleptThrough(60, false));
        assertFalse(NightSleep.sleptThrough(900, false));
        // never lay down at all
        assertFalse(NightSleep.sleptThrough(0, true));
    }
}
