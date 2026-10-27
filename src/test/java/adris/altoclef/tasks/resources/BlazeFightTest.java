package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// the run crowd is blazes plus whoever else is after us, so a bot with no blaze on it still has something to run from
public class BlazeFightTest {

    @Test
    public void chasersAloneMakeACrowd() {
        List<String> crowd = BlazeFight.merge(List.of(), List.of("wither skeleton"));
        assertEquals(List.of("wither skeleton"), crowd);
    }

    @Test
    public void blazesComeFirstAndNobodyIsDropped() {
        assertEquals(List.of("blaze", "wither skeleton", "hoglin"),
                BlazeFight.merge(List.of("blaze"), List.of("wither skeleton", "hoglin")));
    }

    @Test
    public void nobodyMeansSittingTight() {
        assertTrue(BlazeFight.merge(List.of(), List.of()).isEmpty());
    }
}
