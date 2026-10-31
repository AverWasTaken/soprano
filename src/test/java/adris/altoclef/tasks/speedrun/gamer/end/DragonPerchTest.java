package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.end.DragonPerch.Box;
import adris.altoclef.tasks.speedrun.gamer.end.DragonPerch.Parts;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DragonPerchTest {
    // the pillar top surface and the floor feet around it, like the real fountain
    private static final double P = 64;
    private static final double FLOOR = 61;

    @Test
    public void yawZeroPointsTheHeadNorth() {
        Parts p = DragonPerch.sitting(0.5, P, 0.5, 0);
        assertEquals(0.5, p.head().centreX(), 1e-9);
        assertEquals(-6, p.head().centreZ(), 1e-9);
        assertEquals(P - 1, p.head().minY(), 1e-9);
        assertEquals(P, p.head().maxY(), 1e-9);
        assertEquals(-5, p.neck().centreZ(), 1e-9);
        assertEquals(3, p.neck().maxX() - p.neck().minX(), 1e-9);
        assertEquals(0, p.body().centreZ(), 1e-9);
        assertEquals(5, p.body().maxX() - p.body().minX(), 1e-9);
        // wings out to the sides
        assertEquals(5, Math.max(p.wing1().centreX(), p.wing2().centreX()), 1e-9);
        assertEquals(-4, Math.min(p.wing1().centreX(), p.wing2().centreX()), 1e-9);
        assertEquals(P + 2, p.wing1().minY(), 1e-9);
    }

    @Test
    public void yawNinetyPointsTheHeadEast() {
        Parts p = DragonPerch.sitting(0.5, P, 0.5, 90);
        assertEquals(7, p.head().centreX(), 1e-9);
        assertEquals(0.5, p.head().centreZ(), 1e-9);
    }

    @Test
    public void theWingPushIsGrownAndDropped() {
        Box push = DragonPerch.wingPush(Box.centred(0, 66, 0, 4, 2));
        assertEquals(-6, push.minX(), 1e-9);
        assertEquals(6, push.maxZ(), 1e-9);
        assertEquals(62, push.minY(), 1e-9);
        assertEquals(68, push.maxY(), 1e-9);
    }

    // the old 4 to 6 ring stood inside a wing push on the floor
    @Test
    public void theOldRingWasInsideTheWings() {
        Parts p = DragonPerch.sitting(0.5, P, 0.5, 0);
        assertFalse(DragonPerch.standsClear(DragonPerch.player(-4, FLOOR, 1), p.pushes(), p.biters(), 0));
    }

    @Test
    public void theRingIsOutsideTheWingsForEveryYaw() {
        for (int yaw = 0; yaw < 360; yaw++) {
            Parts p = DragonPerch.sitting(0.5, P, 0.5, yaw);
            for (int a = 0; a < 360; a++) {
                for (double r : new double[]{DragonWaitRules.BED_MIN, DragonWaitRules.BED_AIM, DragonWaitRules.BED_MAX}) {
                    double x = 0.5 + r * Math.cos(Math.toRadians(a));
                    double z = 0.5 + r * Math.sin(Math.toRadians(a));
                    assertTrue("yaw " + yaw + " angle " + a + " r " + r,
                            DragonPerch.standsClear(DragonPerch.player(x, FLOOR, z), p.pushes(), p.biters(), 0));
                }
            }
        }
    }

    // the dragon settles up to a block off the pillar middle, the aim still clears the wings then
    @Test
    public void theRingAimClearsADragonABlockOff() {
        for (int yaw = 0; yaw < 360; yaw += 5) {
            for (int off = 0; off < 360; off += 45) {
                double dx = Math.cos(Math.toRadians(off));
                double dz = Math.sin(Math.toRadians(off));
                Parts p = DragonPerch.sitting(0.5 + dx, P, 0.5 + dz, yaw);
                for (int a = 0; a < 360; a += 2) {
                    double x = 0.5 + DragonWaitRules.BED_AIM * Math.cos(Math.toRadians(a));
                    double z = 0.5 + DragonWaitRules.BED_AIM * Math.sin(Math.toRadians(a));
                    assertTrue(DragonPerch.standsClear(DragonPerch.player(x, FLOOR, z), p.pushes(), p.biters(), 0));
                }
            }
        }
    }

    @Test
    public void bedDamageMatchesTheExplosionFormula() {
        assertEquals(71, DragonPerch.bedDamage(0), 1e-9);
        // i = 0.5: (0.25 + 0.5) * 35 + 1
        assertEquals(27.25, DragonPerch.bedDamage(5), 1e-9);
        assertEquals(0, DragonPerch.bedDamage(10), 1e-9);
        assertEquals(0, DragonPerch.bedDamage(12), 1e-9);
    }

    @Test
    public void theCloudSitsAheadOfTheHead() {
        double[] c = DragonPerch.cloudCentre(0.5, 0.5, 0.5, -6);
        assertEquals(0.5, c[0], 1e-9);
        assertEquals(-8.5, c[1], 1e-9);
        double[] east = DragonPerch.cloudCentre(0, 0, 3, 4);
        assertEquals(3 + 1.5, east[0], 1e-9);
        assertEquals(4 + 2, east[1], 1e-9);
    }

    @Test
    public void crystalsInsideTheBoxHealThePerch() {
        assertTrue(DragonPerch.healsPerch(30, 90, 30, 0.5, P, 0.5));
        assertFalse(DragonPerch.healsPerch(42, 90, 0, 0.5, P, 0.5));
        assertFalse(DragonPerch.healsPerch(0, P + 41, 0, 0.5, P, 0.5));
        assertTrue(DragonPerch.healsPerch(0, P + 39, -39, 0.5, P, 0.5));
    }

    @Test
    public void standsClearHonoursTheMargin() {
        List<Box> bite = new ArrayList<>(List.of(new Box(1, 60, -1, 2, 64, 1)));
        // the player box edge sits 0.1 from the bite box
        Box player = DragonPerch.player(0.6, 61, 0);
        assertTrue(DragonPerch.standsClear(player, List.of(), bite, 0));
        assertFalse(DragonPerch.standsClear(player, List.of(), bite, 0.2));
    }
}
