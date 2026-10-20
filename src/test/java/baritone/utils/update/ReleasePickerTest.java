package baritone.utils.update;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// against a trimmed copy of the real releases api answer: 1.2.0 on 1.21.11, 1.1.6 on 26.3, 1.0.3 on 1.21.4 and
// 1.1.6-anyversion on base 26.3
public class ReleasePickerTest {

    private static final Flavor API_FABRIC = new Flavor("api", "fabric");

    private static String fixture() throws IOException {
        try (InputStream in = ReleasePickerTest.class.getResourceAsStream("/baritone/utils/update/releases.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ReleasePicker.Pick pick(String json, String running, String mc, Flavor f) {
        return ReleasePicker.pick(json, ModVersion.parse(running), mc, f);
    }

    @Test
    public void baseOn263PicksOnly263Base() throws IOException {
        // 1.2.0 is a bigger number but it's the 1.21.11 line, and the anyversion one is a different channel
        ReleasePicker.Pick p = pick(fixture(), "1.1.5", "26.3", API_FABRIC);
        assertNotNull(p);
        assertEquals("soprano-v1.1.6", p.tag());
        assertEquals("1.1.6", p.version().raw());
        assertNotNull(p.asset());
        assertEquals("soprano-api-fabric-1.1.6.jar", p.asset().name());
        assertEquals(64, p.asset().sha256().length());
        assertTrue(p.asset().size() > 0);
        assertTrue(p.asset().url().startsWith("https://github.com/"));
        assertNull(p.notInstallable());
    }

    @Test
    public void anyversionPicksOnlyAnyversion() throws IOException {
        ReleasePicker.Pick p = pick(fixture(), "1.1.5-anyversion", "26.3", API_FABRIC);
        assertNotNull(p);
        assertEquals("soprano-v1.1.6-anyversion", p.tag());
        assertEquals("soprano-api-fabric-1.1.6-anyversion.jar", p.asset().name());
    }

    @Test
    public void the12011LineIsNeverPickedFor263() throws IOException {
        // on 1.1.6 already, the only bigger number around is 1.2.0 for 1.21.11
        assertNull(pick(fixture(), "1.1.6", "26.3", API_FABRIC));
        assertNull(pick(fixture(), "1.1.6-anyversion", "26.3", API_FABRIC));
    }

    @Test
    public void otherLinesFindTheirOwn() throws IOException {
        assertEquals("soprano-v1.2.0", pick(fixture(), "1.1.9", "1.21.11", API_FABRIC).tag());
        assertEquals("soprano-v1.0.3", pick(fixture(), "1.0.2", "1.21.4", API_FABRIC).tag());
        assertNull(pick(fixture(), "1.0.3", "1.21.4", API_FABRIC));
    }

    @Test
    public void theDotInTheMcVersionIsLiteral() throws IOException {
        String json = fixture().replace("(26.3)", "(26x3)").replace("(base 26.3)", "(base 26x3)");
        assertNull(pick(json, "1.1.5", "26.3", API_FABRIC));
        assertNull(pick(json, "1.1.5-anyversion", "26.3", API_FABRIC));
    }

    @Test
    public void draftsAreSkipped() throws IOException {
        String json = fixture().replaceFirst("(\"tag_name\": \"soprano-v1\\.1\\.6\",\\s*\"name\": \"[^\"]*\",\\s*\"draft\": )false", "$1true");
        assertNull(pick(json, "1.1.5", "26.3", API_FABRIC));
    }

    @Test
    public void aMissingDigestMeansNotInstallable() throws IOException {
        String json = fixture().replaceAll("\"digest\": \"sha256:[0-9a-f]+\"", "\"digest\": null");
        ReleasePicker.Pick p = pick(json, "1.1.5", "26.3", API_FABRIC);
        assertNotNull(p);
        assertNull(p.asset());
        assertNotNull(p.notInstallable());
    }

    @Test
    public void eachFlavorGetsItsOwnAsset() throws IOException {
        String json = fixture();
        assertEquals("soprano-api-forge-1.1.6.jar", pick(json, "1.1.5", "26.3", new Flavor("api", "forge")).asset().name());
        assertEquals("soprano-api-neoforge-1.1.6.jar", pick(json, "1.1.5", "26.3", new Flavor("api", "neoforge")).asset().name());
        assertEquals("soprano-unoptimized-fabric-1.1.6.jar", pick(json, "1.1.5", "26.3", new Flavor("unoptimized", "fabric")).asset().name());
        // anyversion is fabric only, a forge player there gets the page link and no install
        ReleasePicker.Pick p = pick(json, "1.1.5-anyversion", "26.3", new Flavor("api", "forge"));
        assertNotNull(p);
        assertNull(p.asset());
        assertNotNull(p.notInstallable());
        // no flavor (dev run) still shows the release, just without an install
        ReleasePicker.Pick dev = pick(json, "1.1.5", "26.3", null);
        assertNotNull(dev);
        assertNull(dev.asset());
    }

    @Test
    public void anAssetOffTheGithubHostsIsNotInstallable() throws IOException {
        String json = fixture().replace("https://github.com/AverWasTaken", "https://evil.example/AverWasTaken");
        ReleasePicker.Pick p = pick(json, "1.1.5", "26.3", API_FABRIC);
        assertNotNull(p);
        assertNull(p.asset());
    }

    @Test(expected = IllegalStateException.class)
    public void notAListIsAnError() {
        ReleasePicker.pick("{\"message\": \"API rate limit exceeded\"}", ModVersion.parse("1.1.5"), "26.3", API_FABRIC);
    }

    @Test
    public void digestParsing() {
        assertNull(ReleasePicker.sha256(null));
        assertNull(ReleasePicker.sha256("md5:abc"));
        assertNull(ReleasePicker.sha256("sha256:abc"));
        String hex = "29ce088e334eceae5f7d2e51b189e9175659878b8b2a44e2f6add9c29b58d706";
        assertEquals(hex, ReleasePicker.sha256("sha256:" + hex.toUpperCase()));
    }
}
