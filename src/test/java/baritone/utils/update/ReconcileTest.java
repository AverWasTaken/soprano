package baritone.utils.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReconcileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ModVersion OURS = ModVersion.parse("1.1.6");

    private Path mods() {
        return tmp.getRoot().toPath();
    }

    private Path self() throws Exception {
        return TestJars.jar(mods(), "soprano-api-fabric-1.1.6.jar", TestJars.soprano("fabric", "1.1.6", "26.3"));
    }

    private Path staged(String version, String mc) throws Exception {
        return TestJars.jar(mods(), "soprano-api-fabric-" + version + ".jar.new", TestJars.soprano("fabric", version, mc));
    }

    @Test
    public void leftoversAreCleanedButNotOtherModsFiles() throws Exception {
        Path self = self();
        Path part = Files.writeString(mods().resolve("soprano-api-fabric-1.1.7.jar.part"), "x");
        Path old = Files.writeString(mods().resolve("soprano-api-fabric-1.1.5.jar.old"), "x");
        Path orphan = Files.writeString(mods().resolve("soprano-api-fabric-1.1.7.jar.new.tries"), "1");
        Path theirs = Files.writeString(mods().resolve("sodium-0.6.jar.old"), "x");
        Reconcile.Result r = Reconcile.run(self, OURS, "26.3");
        assertNull(r.staged());
        assertFalse(Files.exists(part));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(theirs));
        assertTrue(Files.exists(self));
    }

    @Test
    public void aRenamedSelfStillGetsItsLeftoversCleaned() throws Exception {
        Path self = TestJars.jar(mods(), "pathing.jar", TestJars.soprano("fabric", "1.1.6", "26.3"));
        Path old = Files.writeString(mods().resolve("pathing.jar.old"), "x");
        Reconcile.run(self, OURS, "26.3");
        assertFalse(Files.exists(old));
    }

    @Test
    public void triesCountUpThenStuck() throws Exception {
        Path self = self();
        Path staged = staged("1.1.7", "26.3");
        Path tries = Reconcile.triesFor(staged);
        // the download wrote 1
        Reconcile.writeTries(tries, 1);

        Reconcile.Result first = Reconcile.run(self, OURS, "26.3");
        assertEquals(staged, first.staged());
        assertEquals(mods().resolve("soprano-api-fabric-1.1.7.jar"), first.finalJar());
        assertFalse(first.stuck());
        assertEquals("2", Files.readString(tries));

        Reconcile.Result second = Reconcile.run(self, OURS, "26.3");
        assertEquals(staged, second.staged());
        assertTrue(second.stuck());
        assertTrue(Files.exists(staged));
    }

    @Test
    public void theNewestStagedJarWinsAndTheRestGo() throws Exception {
        Path self = self();
        Path older = staged("1.1.7", "26.3");
        Path newer = staged("1.1.10", "26.3");
        Path olderTries = Files.writeString(Reconcile.triesFor(older), "1");
        Reconcile.Result r = Reconcile.run(self, OURS, "26.3");
        assertEquals(newer, r.staged());
        assertEquals("1.1.10", r.version());
        assertFalse(Files.exists(older));
        assertFalse(Files.exists(olderTries));
        assertTrue(Files.exists(newer));
    }

    @Test
    public void aMissingTriesFileIsTheFirstMiss() throws Exception {
        Path self = self();
        Path staged = staged("1.1.7", "26.3");
        assertFalse(Reconcile.run(self, OURS, "26.3").stuck());
        assertEquals("2", Files.readString(Reconcile.triesFor(staged)));
    }

    @Test
    public void aStagedJarThatIsNotNewerGoesAway() throws Exception {
        Path self = self();
        Path same = staged("1.1.6", "26.3");
        Path older = staged("1.1.5", "26.3");
        Path otherMc = staged("1.2.0", "1.21.11");
        Path otherChannel = staged("1.1.7-anyversion", "26.3");
        Path tries = Files.writeString(Reconcile.triesFor(same), "1");
        assertNull(Reconcile.run(self, OURS, "26.3").staged());
        assertFalse(Files.exists(same));
        assertFalse(Files.exists(older));
        assertFalse(Files.exists(otherMc));
        assertFalse(Files.exists(otherChannel));
        assertFalse(Files.exists(tries));
    }

    @Test
    public void aBrokenStagedJarGoesAway() throws Exception {
        Path self = self();
        Path junk = Files.writeString(mods().resolve("soprano-api-fabric-1.1.7.jar.new"), "not a zip");
        assertNull(Reconcile.run(self, OURS, "26.3").staged());
        assertFalse(Files.exists(junk));
    }
}
