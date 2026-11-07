package adris.altoclef.chains;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UserTaskShortDurationTest {

    @Test
    public void chatGetsTheShortForm() {
        assertEquals("0s", UserTaskChain.shortDuration(0.2));
        assertEquals("12s", UserTaskChain.shortDuration(12.3));
        assertEquals("1m 12s", UserTaskChain.shortDuration(72));
        assertEquals("2h 5m", UserTaskChain.shortDuration(2 * 3600 + 5 * 60 + 9));
    }
}
