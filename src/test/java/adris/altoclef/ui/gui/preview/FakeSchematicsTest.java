package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import adris.altoclef.ui.gui.facade.SchematicsAccess.ImportReport;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Kind;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Line;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Listing;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.facade.SchematicsAccess.State;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the fake is what every preview and tab test leans on, so it gets held to the contract in SchematicsAccess
public class FakeSchematicsTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private final FakeFacade facade = new FakeFacade();
    private final FakeSchematics fake = facade.schematics;

    private static Row row(Listing listing, String path) {
        return listing.rows().stream().filter(r -> r.relativePath().equals(path)).findFirst().orElseThrow();
    }

    @Test
    public void facadeHandsOutTheFake() {
        assertTrue(facade.schematics() == fake);
    }

    @Test
    public void listHasEveryKindOfRow() {
        Listing listing = fake.list();
        assertEquals(State.READY, listing.state());
        Row castle = row(listing, "Small Castle.litematic");
        assertTrue(castle.ok());
        assertEquals("24 x 18 x 24", castle.sizeText());
        assertEquals("Litematica v6", castle.formatText());
        assertNull(castle.unknownSummary());
        assertNotNull(row(listing, "tower.schem").unknownSummary());
        Row broken = row(listing, "broken.litematic");
        assertFalse(broken.ok());
        assertEquals("", broken.sizeText());
        assertEquals("MCEdit", row(listing, "villages/farm.schematic").formatText());
    }

    @Test
    public void refreshFlipsToLoadingForAsManyPollsAsAsked() {
        fake.loadingPolls = 2;
        fake.refresh();
        assertEquals(State.LOADING, fake.list().state());
        assertEquals(State.LOADING, fake.list().state());
        assertEquals(State.READY, fake.list().state());
        assertTrue(facade.actions.contains("schematics refresh"));
    }

    @Test
    public void loadingStillCarriesTheOldRows() {
        fake.loadingForever = true;
        Listing listing = fake.list();
        assertEquals(State.LOADING, listing.state());
        assertFalse(listing.rows().isEmpty());
        assertEquals(State.LOADING, fake.materials("Small Castle.litematic").state());
    }

    @Test
    public void listIsImmutable() {
        try {
            fake.list().rows().clear();
            throw new AssertionError("rows should be immutable");
        } catch (UnsupportedOperationException expected) {
            // good
        }
    }

    @Test
    public void haveFollowsTheInventoryOnEveryCall() {
        Materials before = fake.materials("Small Castle.litematic");
        Line logs = line(before, "minecraft:oak_log");
        assertEquals(40, logs.have());
        assertEquals(212 - 40, logs.missing());
        assertFalse(before.allCovered());

        fake.have.put("minecraft:oak_log", 500);
        Line after = line(fake.materials("Small Castle.litematic"), "minecraft:oak_log");
        assertEquals(500, after.have());
        assertEquals(0, after.missing());
        assertTrue(after.covered());
    }

    @Test
    public void materialsCarryTheOtherKindsToo() {
        Materials castle = fake.materials("Small Castle.litematic");
        assertEquals(State.READY, castle.state());
        assertNull(castle.error());
        assertEquals(1, castle.cantPlace().size());
        assertEquals(Kind.CANT_PLACE, castle.cantPlace().get(0).kind());
        assertEquals(Kind.NOT_COUNTED, castle.notCounted().get(0).kind());
        assertEquals("minecraft:water_bucket", castle.notCounted().get(0).iconItemId());
        assertFalse(castle.notes().isEmpty());
        assertFalse(line(castle, "minecraft:beacon").gettable());
        assertTrue(line(castle, "minecraft:glass").gettable());
        assertTrue(castle.totalNeeded() > castle.totalMissing());
    }

    @Test
    public void unknownAndBrokenPathsAnswerWithAnError() {
        Materials nope = fake.materials("nope.litematic");
        assertEquals(State.READY, nope.state());
        assertNotNull(nope.error());
        assertTrue(nope.items().isEmpty());
        assertNotNull(fake.materials("broken.litematic").error());
    }

    @Test
    public void buildRecordsAndFailsOnUnknownOrWhenToldTo() {
        Result ok = fake.build("tower.schem");
        assertTrue(ok.ok());
        assertEquals("build tower.schem", ok.command());
        assertTrue(facade.actions.contains("schematics build tower.schem"));
        // spaces can't go through the command line, so there's nothing honest to show under the toast
        assertNull(fake.build("Small Castle.litematic").command());

        assertFalse(fake.build("missing.schem").ok());
        fake.buildError = "Join a world first";
        assertEquals("Join a world first", fake.build("tower.schem").message());
    }

    @Test
    public void droppedFilesFollowTheCopyRules() {
        int before = fake.list().rows().size();
        ImportReport report = fake.importDropped(List.of(Path.of("downloads", "tower.schem"),
            Path.of("downloads", "tower.schem"), Path.of("downloads", "readme.txt"), Path.of("a", "new.litematic")));
        assertEquals(List.of("tower (2).schem", "tower (3).schem", "new.litematic"), report.imported());
        assertEquals(1, report.skipped().size());
        assertEquals(before + 3, fake.list().rows().size());
        // the new rows have a cost list too
        assertNull(fake.materials("new.litematic").error());
        Result toast = report.toResult();
        assertTrue(toast.ok());
        assertEquals("Imported 3 schematics, 1 file left out", toast.message());
    }

    @Test
    public void pickerDefaultsToImportingOneFile() {
        List<ImportReport> got = new ArrayList<>();
        fake.importFiles(got::add);
        assertEquals(1, got.size());
        assertEquals(List.of("picked.litematic"), got.get(0).imported());
        assertEquals("Imported picked.litematic", got.get(0).toResult().message());
    }

    @Test
    public void pickerCanBeScriptedToCancelOrFail() {
        List<ImportReport> got = new ArrayList<>();
        fake.nextPick = ImportReport.nothingChosen();
        fake.importFiles(got::add);
        assertTrue(got.get(0).cancelled());
        assertTrue(got.get(0).toResult().ok());

        fake.nextPick = ImportReport.failed("No file picker found");
        fake.importFiles(got::add);
        Result failed = got.get(1).toResult();
        assertFalse(failed.ok());
        assertEquals("No file picker found", failed.message());
    }

    @Test
    public void importReportToastWording() {
        assertFalse(new ImportReport(List.of(), List.of(), List.of(), false).toResult().ok());
        assertEquals("Imported a.schem", new ImportReport(List.of("a.schem"), List.of(), List.of(), false).toResult().message());
        assertEquals("notes.txt: not a schematic",
            new ImportReport(List.of(), List.of("notes.txt: not a schematic"), List.of(), false).toResult().message());
        assertEquals("Imported a.schem, 1 file left out",
            new ImportReport(List.of("a.schem"), List.of(), List.of("b: broke"), false).toResult().message());
    }

    @Test
    public void folderActionsRecord() {
        assertTrue(fake.openFolder().ok());
        assertTrue(facade.actions.contains("schematics folder"));
        assertFalse(fake.folderPath().isEmpty());
    }

    @Test
    public void theNoneFacadeIsHarmless() {
        SchematicsAccess none = SchematicsAccess.NONE;
        assertTrue(none.list().rows().isEmpty());
        assertNotNull(none.materials("x").error());
        assertFalse(none.build("x").ok());
        assertFalse(none.importDropped(List.of()).toResult().ok());
        List<ImportReport> got = new ArrayList<>();
        none.importFiles(got::add);
        assertEquals(1, got.size());
        none.refresh();
    }

    private static Line line(Materials materials, String itemId) {
        return materials.items().stream().filter(l -> l.itemId().equals(itemId)).findFirst().orElseThrow();
    }
}
