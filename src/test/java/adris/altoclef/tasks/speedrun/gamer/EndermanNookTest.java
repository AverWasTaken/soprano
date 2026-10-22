package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.EndermanNook.Spot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EndermanNookTest {

    // a clean tunnel with the lid already on
    private static Spot good() {
        return new Spot(true, true, true, true, true, 0, false, false, 0);
    }

    @Test
    public void aCleanTunnelIsOk() {
        assertTrue(EndermanNook.spotOk(good()));
    }

    @Test
    public void everyMissingWallKillsTheSpot() {
        assertFalse(EndermanNook.spotOk(new Spot(false, true, true, true, true, 0, false, false, 0)));
        assertFalse(EndermanNook.spotOk(new Spot(true, false, true, true, true, 0, false, false, 0)));
        assertFalse(EndermanNook.spotOk(new Spot(true, true, false, true, true, 0, false, false, 0)));
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, false, true, 0, false, false, 0)));
        // an enderman cannot stand in the mouth, so it cannot come to the door
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, false, 0, false, false, 0)));
    }

    @Test
    public void lavaNearAnyCellRejectsIt() {
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, true, 0, false, true, 9)));
    }

    @Test
    public void aMissingLidNeedsBlocksToPlace() {
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, true, 2, false, false, 1)));
        assertTrue(EndermanNook.spotOk(new Spot(true, true, true, true, true, 2, false, false, 2)));
        assertTrue(EndermanNook.spotOk(new Spot(true, true, true, true, true, 1, false, false, 5)));
    }

    @Test
    public void aLidThatCannotBeFixedRejectsIt() {
        // sand over the cell, or a lid with nothing to click: it never comes right
        assertFalse(EndermanNook.spotOk(new Spot(true, true, true, true, true, 0, true, false, 9)));
    }

    @Test
    public void cheaperSpotsWin() {
        assertTrue(EndermanNook.spotCost(3, 0, 0) < EndermanNook.spotCost(3, 4, 0));
        assertTrue(EndermanNook.spotCost(3, 0, 0) < EndermanNook.spotCost(3, 0, 2));
        assertTrue(EndermanNook.spotCost(2, 4, 2) > EndermanNook.spotCost(2, 0, 0));
    }

    @Test
    public void standRuleIsTwoPointOneThree() {
        assertFalse(EndermanNook.standDeepEnough(2.0));
        assertFalse(EndermanNook.standDeepEnough(2.129));
        assertTrue(EndermanNook.standDeepEnough(2.13));
        assertTrue(EndermanNook.standDeepEnough(2.2));
    }

    @Test
    public void creepStopsAtTheLineAndTheWall() {
        // the middle of the back cell is 2.0: a bit further in, but never past what the hitbox has before the wall
        assertEquals(0.18, EndermanNook.creepBy(2.0), 1e-9);
        assertEquals(0, EndermanNook.creepBy(2.13), 1e-9);
        assertEquals(0, EndermanNook.creepBy(2.2), 1e-9);
        assertTrue(2.0 + EndermanNook.creepBy(2.0) <= EndermanNook.BACK_WALL_IN);
    }

    @Test
    public void noAggroBurningHurtOrNextToLava() {
        assertTrue(EndermanNook.mayAggro(false, 20, 99, 0, 20));
        assertFalse(EndermanNook.mayAggro(true, 20, 99, 0, 20));
        assertFalse(EndermanNook.mayAggro(false, 17.5f, 99, 0, 20));
        assertTrue(EndermanNook.mayAggro(false, 18, 99, 0, 20));
        assertFalse(EndermanNook.mayAggro(false, 20, 2, 0, 20));
        assertFalse(EndermanNook.mayAggro(false, 20, 1, 0, 20));
        assertTrue(EndermanNook.mayAggro(false, 20, 3, 0, 20));
    }

    @Test
    public void neverAggroASecondOneOrStareFromPointBlank() {
        assertFalse(EndermanNook.mayAggro(false, 20, 99, 1, 20));
        assertFalse(EndermanNook.mayAggro(false, 20, 99, 0, 3));
        assertTrue(EndermanNook.mayAggro(false, 20, 99, 0, 6));
    }

    @Test
    public void nearestAngryWinsInsideTheCap() {
        assertEquals(1, EndermanNook.nearestAngry(new double[]{20, 9, 14}));
        assertEquals(0, EndermanNook.nearestAngry(new double[]{3, 9, 14}));
    }

    @Test
    public void nearestAngryIgnoresTheFarAndTheDead() {
        assertEquals(-1, EndermanNook.nearestAngry(new double[]{33, 130}));
        assertEquals(-1, EndermanNook.nearestAngry(new double[]{Double.NaN}));
        assertEquals(2, EndermanNook.nearestAngry(new double[]{Double.NaN, 40, 32}));
        assertEquals(-1, EndermanNook.nearestAngry(new double[]{}));
    }

    @Test
    public void latchHasADistance() {
        assertTrue(EndermanNook.latchSees(40));
        assertFalse(EndermanNook.latchSees(40.1));
        assertFalse(EndermanNook.latchSees(130));
        assertFalse(EndermanNook.latchSees(Double.POSITIVE_INFINITY));
    }

    @Test
    public void brainStandsDownOnlyForEndermenAloneDuringTheHunt() {
        assertTrue(EndermanNook.brainStandsDown(true, 1, 1));
        assertTrue(EndermanNook.brainStandsDown(true, 2, 2));
        // a zombie in the mix keeps the usual rules, the hunt not running does too, and so does nobody being there
        assertFalse(EndermanNook.brainStandsDown(true, 2, 1));
        assertFalse(EndermanNook.brainStandsDown(false, 1, 1));
        assertFalse(EndermanNook.brainStandsDown(true, 0, 0));
    }
}
