package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Cause;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Limits;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Stage;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules.Step;
import baritone.api.utils.Dimension;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetherTripRulesTest {
    // 6 minutes, 24 blocks, a minute of looking for them
    private static final Limits LIMITS = Limits.of(360, 24, 60);

    // the usual stage: overworld, nothing held, nothing done yet. tests change what they care about
    private static Inputs in(Stage stage, Dimension dim, long tripTicks, long stageTicks, int blocks) {
        return new Inputs(stage, dim, tripTicks, stageTicks, blocks, false, 500, false, 0, true, LIMITS);
    }

    private static Inputs home(Stage stage, boolean gone) {
        return new Inputs(stage, Dimension.OVERWORLD, 100, 100, 24, gone, 500, false, 0, true, LIMITS);
    }

    private static Inputs pile(Stage stage, Dimension dim, double distance, boolean finished, int gained, boolean kitShort) {
        return new Inputs(stage, dim, 2000, 100, 24, false, distance, finished, gained, kitShort, LIMITS);
    }

    // ---- eligibility

    @Test
    public void onlyANetherDeathThatRespawnedInTheOverworldApplies() {
        assertTrue(NetherTripRules.applies(Dimension.NETHER, Dimension.OVERWORLD));
        // an anchor respawn is the plain same dimension recovery
        assertFalse(NetherTripRules.applies(Dimension.NETHER, Dimension.NETHER));
        assertFalse(NetherTripRules.applies(Dimension.OVERWORLD, Dimension.OVERWORLD));
        assertFalse(NetherTripRules.applies(Dimension.END, Dimension.OVERWORLD));
    }

    @Test
    public void aPlainDeathWithAHomePortalIsWorthTheTrip() {
        assertNull(NetherTripRules.refuse(Cause.OTHER, true, true, false, false, false));
    }

    @Test
    public void lavaAndTheVoidAreNotWorthIt() {
        assertNotNull(NetherTripRules.refuse(Cause.LAVA, true, true, false, false, false));
        assertNotNull(NetherTripRules.refuse(Cause.VOID, true, true, false, false, false));
    }

    @Test
    public void noPortalHomeMeansNoWayBack() {
        assertEquals("no portal to go back through", NetherTripRules.refuse(Cause.OTHER, false, true, false, false, false));
    }

    @Test
    public void theConfigCanSwitchTheTripOff() {
        assertNotNull(NetherTripRules.refuse(Cause.OTHER, true, false, false, false, false));
    }

    @Test
    public void aSecondDeathOnTheWayAndAPileWeAlreadyWentForAreRefused() {
        assertNotNull(NetherTripRules.refuse(Cause.OTHER, true, true, true, false, false));
        assertNotNull(NetherTripRules.refuse(Cause.OTHER, true, true, false, true, false));
    }

    @Test
    public void aPileIsTriedWhenTheLastTripWentToTheSameSpot() {
        RunState.Pos last = new RunState.Pos(100, 40, -200);
        assertFalse(NetherTripRules.tried(null, 100, -200));
        assertTrue(NetherTripRules.tried(last, 100, -200));
        assertTrue(NetherTripRules.tried(last, 105, -195));
        // a different pile further on is a new one
        assertFalse(NetherTripRules.tried(last, 120, -200));
    }

    @Test
    public void theCauseComesFromWhatWeStoodIn() {
        assertEquals(Cause.LAVA, NetherTripRules.cause(true, false));
        assertEquals(Cause.LAVA, NetherTripRules.cause(true, true));
        assertEquals(Cause.VOID, NetherTripRules.cause(false, true));
        assertEquals(Cause.OTHER, NetherTripRules.cause(false, false));
    }

    // ---- blocks

    @Test
    public void blocksMoveOnOnceWeHoldEnough() {
        assertEquals(Stage.BLOCKS, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 100, 100, 23)).stage());
        Step go = NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 100, 100, 24));
        assertEquals(Stage.PORTAL, go.stage());
        assertNull(go.giveUp());
    }

    @Test
    public void aMinuteWithoutEnoughBlocksGoesWithWhatWeHave() {
        assertEquals(Stage.BLOCKS, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 1300, 1200, 3)).stage());
        assertEquals(Stage.PORTAL, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 1301, 1201, 3)).stage());
        // even with none at all, the blocks only make the walk safer
        assertEquals(Stage.PORTAL, NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 1301, 1201, 0)).stage());
    }

    // ---- portal

    @Test
    public void theWalkToThePortalStaysUntilWeAreThrough() {
        assertEquals(Stage.PORTAL, NetherTripRules.next(home(Stage.PORTAL, false)).stage());
        Step through = NetherTripRules.next(in(Stage.PORTAL, Dimension.NETHER, 500, 100, 24));
        assertEquals(Stage.WALK, through.stage());
        assertNull(through.giveUp());
    }

    @Test
    public void aGoneHomePortalEndsTheTripInTheOverworld() {
        Step step = NetherTripRules.next(home(Stage.PORTAL, true));
        assertNull(step.stage());
        assertEquals("the portal is gone", step.giveUp());
        assertFalse(step.recovered());
    }

    // ---- walk and pile

    @Test
    public void theWalkHandsOverTwentyBlocksOut() {
        assertEquals(Stage.WALK, NetherTripRules.next(pile(Stage.WALK, Dimension.NETHER, 20.5, false, 0, true)).stage());
        assertEquals(Stage.RECOVER, NetherTripRules.next(pile(Stage.WALK, Dimension.NETHER, 20, false, 0, true)).stage());
    }

    @Test
    public void thePileTaskKeepsTheWheelUntilItIsFinished() {
        assertEquals(Stage.RECOVER, NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, false, 5, true)).stage());
    }

    @Test
    public void aPileThatGaveSomethingBackIsTheEndOfTheTrip() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 14, false));
        assertTrue(step.recovered());
        assertNull(step.stage());
        assertNull(step.giveUp());
    }

    @Test
    public void aPileWithoutTheKitInItIsTreatedLikeAnEmptyOne() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 14, true));
        assertEquals("the pile did not have the kit", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
        assertFalse(step.recovered());
    }

    @Test
    public void aKitThatSurvivedTheDeathNeedsNoTrip() {
        assertEquals("the kit is still on us", NetherTripRules.refuse(Cause.OTHER, true, true, false, false, true));
    }
    @Test
    public void anEmptyPileGivesUpAndWalksHomeWhenTheKitIsStillShort() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 0, true));
        assertEquals("nothing left at the pile", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
        assertFalse(step.recovered());
    }

    @Test
    public void anEmptyPileWithAKitInTheBagJustHandsBack() {
        Step step = NetherTripRules.next(pile(Stage.RECOVER, Dimension.NETHER, 3, true, 0, false));
        assertEquals("nothing left at the pile", step.giveUp());
        assertNull(step.stage());
    }

    @Test
    public void leavingTheNetherMidTripGivesUp() {
        assertEquals("left the nether", NetherTripRules.next(pile(Stage.WALK, Dimension.OVERWORLD, 300, false, 0, true)).giveUp());
        assertEquals("left the nether", NetherTripRules.next(pile(Stage.RECOVER, Dimension.OVERWORLD, 3, false, 0, true)).giveUp());
    }

    // ---- the budget

    @Test
    public void sixMinutesIsTheWholeTripAndAnythingPastItIsOutOfTime() {
        assertEquals(Stage.WALK, NetherTripRules.next(pile(Stage.WALK, Dimension.NETHER, 300, false, 0, true)).stage());
        Step late = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, 7201, 100, 24, false, 300, false, 0, true, LIMITS));
        assertEquals("out of time", late.giveUp());
        Step exact = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, 7200, 100, 24, false, 300, false, 0, true, LIMITS));
        assertNull(exact.giveUp());
    }

    @Test
    public void outOfTimeInTheOverworldJustEndsTheTripSoTheRebuildCanStart() {
        Step step = NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, 7201, 7201, 2));
        assertEquals("out of time", step.giveUp());
        assertNull(step.stage());
    }

    @Test
    public void outOfTimeStandingInTheNetherWithoutAKitWalksHome() {
        Step step = NetherTripRules.next(new Inputs(Stage.WALK, Dimension.NETHER, 9000, 100, 24, false, 300, false, 0, true, LIMITS));
        assertEquals("out of time", step.giveUp());
        assertEquals(Stage.HOME, step.stage());
    }

    @Test
    public void aClockThatWentBackwardsIsAnotherWorld() {
        assertEquals("out of time", NetherTripRules.next(in(Stage.BLOCKS, Dimension.OVERWORLD, -5, -5, 0)).giveUp());
    }

    // ---- home

    @Test
    public void walkingHomeEndsInTheOverworldOrAfterTwoMinutes() {
        assertEquals(Stage.HOME, NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 99999, 2400, 0, false, 300,
                false, 0, true, LIMITS)).stage());
        Step back = NetherTripRules.next(new Inputs(Stage.HOME, Dimension.OVERWORLD, 99999, 100, 0, false, 300, false, 0, true, LIMITS));
        assertNull(back.stage());
        assertNull(back.giveUp());
        Step tired = NetherTripRules.next(new Inputs(Stage.HOME, Dimension.NETHER, 99999, 2401, 0, false, 300, false, 0, true, LIMITS));
        assertNull(tired.stage());
    }

    // ---- a whole trip, stage by stage

    @Test
    public void aGoodTripWalksEveryStageInOrder() {
        Stage stage = Stage.BLOCKS;
        stage = NetherTripRules.next(in(stage, Dimension.OVERWORLD, 100, 100, 24)).stage();
        assertEquals(Stage.PORTAL, stage);
        stage = NetherTripRules.next(in(stage, Dimension.NETHER, 400, 300, 24)).stage();
        assertEquals(Stage.WALK, stage);
        stage = NetherTripRules.next(pile(stage, Dimension.NETHER, 12, false, 0, true)).stage();
        assertEquals(Stage.RECOVER, stage);
        assertTrue(NetherTripRules.next(pile(stage, Dimension.NETHER, 2, true, 9, false)).recovered());
    }
}
