package baritone.utils.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModVersionTest {

    @Test
    public void parsesBothChannels() {
        ModVersion base = ModVersion.parse("1.1.6");
        ModVersion any = ModVersion.parse("1.1.6-anyversion");
        assertNotNull(base);
        assertNotNull(any);
        assertEquals("", base.channel());
        assertEquals("anyversion", any.channel());
        assertEquals("1.1.6-anyversion", any.raw());
        assertFalse(base.sameChannel(any));
    }

    @Test
    public void unknownSuffixesAreNotTheBaseChannel() {
        // a beta must never be offered the base channel's update because its numbers parse
        assertNull(ModVersion.parse("1.1.6-beta"));
        assertNull(ModVersion.parse("1.1.6-12-gabc123"));
        assertNull(ModVersion.parse("1.1.6-dirty"));
        assertNull(ModVersion.parse("1.1.6-anyversion-beta"));
        assertNull(ModVersion.parse("v1.1.6"));
        assertNull(ModVersion.parse("unknown"));
        assertNull(ModVersion.parse(""));
        assertNull(ModVersion.parse(null));
        assertNull(ModVersion.parse("99999999999.0"));
    }

    @Test
    public void comparesNumbersNotStrings() {
        assertTrue(ModVersion.parse("1.1.10").newerThan(ModVersion.parse("1.1.9")));
        assertTrue(ModVersion.parse("1.2.0").newerThan(ModVersion.parse("1.1.6")));
        assertFalse(ModVersion.parse("1.1.6").newerThan(ModVersion.parse("1.1.6")));
        assertEquals(0, ModVersion.parse("1.1").compareTo(ModVersion.parse("1.1.0")));
        assertEquals(ModVersion.parse("1.1"), ModVersion.parse("1.1.0"));
        assertEquals(ModVersion.parse("1.1").hashCode(), ModVersion.parse("1.1.0").hashCode());
        assertTrue(ModVersion.parse("1.1.6.1").newerThan(ModVersion.parse("1.1.6")));
    }
}
