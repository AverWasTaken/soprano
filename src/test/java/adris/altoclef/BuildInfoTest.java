package adris.altoclef;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BuildInfoTest {

    @Test
    public void aMissingResourceIsUnknownAndNeverThrows() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.read("/adris/altoclef/does-not-exist.properties", "buildId"));
    }

    @Test
    public void aMissingKeyIsUnknown() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.read("/adris/altoclef/build-id.properties", "nope"));
    }

    @Test
    public void anUnexpandedPlaceholderIsUnknown() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.clean("${buildId}"));
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.clean("  "));
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.clean(null));
        assertEquals("abc123-dirty", BuildInfo.clean(" abc123-dirty "));
    }

    @Test
    public void theGradleExpandedFileIsReadBack() {
        // processResources runs before the tests, so this is the real build's id and never the raw placeholder
        assertNotNull(BuildInfo.id());
        assertFalse(BuildInfo.id().contains("${"));
        assertFalse(BuildInfo.built().contains("${"));
        assertFalse(BuildInfo.id().isEmpty());
        assertTrue(BuildInfo.line().startsWith("build " + BuildInfo.id() + " built "));
    }
}
