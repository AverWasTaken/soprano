package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;

import adris.altoclef.util.helpers.BlazeFightRules;
import java.util.List;
import org.junit.Test;

public class DeathZonesTest {

    private static RunState.Death death(String dimension, String cause, long at, int x, int z) {
        RunState.Death d = new RunState.Death();
        d.dimension = dimension;
        d.cause = cause;
        d.gameTime = at;
        d.x = x;
        d.z = z;
        return d;
    }

    @Test
    public void onlyRecentNetherDeathsThatWereNotLavaOrVoidCount() {
        long now = 100_000;
        List<RunState.Death> deaths = List.of(
                death("NETHER", "other", now - 100, 1, 2),
                death("OVERWORLD", "other", now - 100, 3, 4),
                death("NETHER", "lava", now - 100, 5, 6),
                death("NETHER", "void", now - 100, 7, 8),
                death("NETHER", null, now - 100, 9, 10),
                death("NETHER", "other", now - DeathZones.MAX_AGE_TICKS, 11, 12),
                death("NETHER", "other", now - DeathZones.MAX_AGE_TICKS + 1, 13, 14),
                death("NETHER", "other", now + 5, 15, 16));
        List<double[]> spots = DeathZones.spots(deaths, now);
        assertEquals(3, spots.size());
        assertArrayEquals(new double[]{1, 2}, spots.get(0), 0);
        assertArrayEquals(new double[]{9, 10}, spots.get(1), 0);
        assertArrayEquals(new double[]{13, 14}, spots.get(2), 0);
    }

    private static RunState.Death killedBy(String killer, String dimension, String cause, long at, int x, int z) {
        RunState.Death d = death(dimension, cause, at, x, z);
        d.killer = killer;
        return d;
    }

    @Test
    public void bruteAndWitherDeathsGetAFatZoneThatOutlivesTheShortOne() {
        long now = 10_000_000;
        // 30 minutes old: long gone from spots(), still a zone for the pearl walk
        long halfHour = now - 30 * 60 * 20L;
        List<RunState.Death> deaths = List.of(
                killedBy("piglin_brute", "NETHER", "other", halfHour, 98, 528),
                killedBy("wither_skeleton", "NETHER", "other", now - 100, 21, 39),
                killedBy("zombified_piglin", "NETHER", "other", now - 100, 1, 1),
                killedBy(null, "NETHER", "other", now - 100, 2, 2),
                killedBy("piglin_brute", "OVERWORLD", "other", now - 100, 3, 3),
                killedBy("piglin_brute", "NETHER", "lava", now - 100, 4, 4),
                killedBy("piglin_brute", "NETHER", "other", now - DeathZones.KILLER_MAX_AGE_TICKS, 5, 5),
                killedBy("piglin_brute", "NETHER", "other", now - DeathZones.KILLER_MAX_AGE_TICKS + 1, 6, 6),
                killedBy("piglin_brute", "NETHER", "other", now + 5, 7, 7));
        List<double[]> zones = DeathZones.killerZones(deaths, now);
        assertEquals(3, zones.size());
        assertArrayEquals(new double[]{98, 528, DeathZones.KILLER_RADIUS}, zones.get(0), 0);
        assertArrayEquals(new double[]{21, 39, DeathZones.KILLER_RADIUS}, zones.get(1), 0);
        assertArrayEquals(new double[]{6, 6, DeathZones.KILLER_RADIUS}, zones.get(2), 0);
        // the blaze walk's spots are unchanged: the half hour old brute death is out of them, the three fresh nether ones are in
        List<double[]> spots = DeathZones.spots(deaths, now);
        assertEquals(3, spots.size());
        assertEquals(12, DeathZones.RADIUS, 0);
    }

    @Test
    public void onlyBrutesAndWitherSkeletonsAreLastingKillers() {
        assertTrue(DeathZones.lastingKiller("piglin_brute"));
        assertTrue(DeathZones.lastingKiller("wither_skeleton"));
        assertFalse(DeathZones.lastingKiller("blaze"));
        assertFalse(DeathZones.lastingKiller("piglin"));
        assertFalse(DeathZones.lastingKiller(null));
    }

    @Test
    public void packCenterIsTheMeanOfAPackAndNothingForLess() {
        assertNull(BlazeFightRules.packCenter(List.of(new double[]{0, 0}, new double[]{10, 10})));
        double[] c = BlazeFightRules.packCenter(List.of(new double[]{0, 0}, new double[]{6, 0}, new double[]{0, 9}));
        assertArrayEquals(new double[]{2, 3}, c, 1e-9);
    }
}
