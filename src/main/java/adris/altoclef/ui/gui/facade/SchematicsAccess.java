package adris.altoclef.ui.gui.facade;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

// the schematics tab's whole world: the <gameDir>/schematics folder, what's in each file, what building it costs and
// the buttons around that. nothing here parses on the render thread. list() and materials() hand back the latest
// snapshot straight away (LOADING until the background thread has an answer), so a tab can call them every frame
public interface SchematicsAccess {

    enum State {
        // a load is running. rows / lines are whatever the last finished load had (empty the first time)
        LOADING,
        READY
    }

    // one file in the library. when the file didn't parse, error is set and the numbers are zero. unknownSummary is
    // null unless the file named blocks this version doesn't know ("12 blocks unrecognized: minecraft:foo (10), ...")
    record Row(String name, String relativePath, int sizeX, int sizeY, int sizeZ, long blockCount, String format,
               int version, long fileSize, String error, String unknownSummary) {

        public boolean ok() {
            return error == null;
        }

        // "12 x 8 x 20" (width, height, length), "" when the file didn't parse
        public String sizeText() {
            return ok() ? sizeX + " x " + sizeY + " x " + sizeZ : "";
        }

        // "Litematica v6", or just "MCEdit" for formats with no version
        public String formatText() {
            return version > 0 ? format + " v" + version : format;
        }
    }

    // rows are sorted by relative path, never null, immutable
    record Listing(State state, List<Row> rows) {
    }

    enum Kind {
        // an item the player has to hold
        ITEM,
        // a block that has no item at all (fire, frosted ice), so it can't be placed
        CANT_PLACE,
        // counted for information only (fluid sources)
        NOT_COUNTED
    }

    // one row of a material list. itemId is an item id for ITEM rows ("minecraft:oak_stairs") and a block id for the
    // other two kinds. iconItemId is what to draw with Canvas.itemIcon, "" when no item stands for it. have is what the
    // player carries right now (0 for the non-ITEM kinds). gettable: AltoClef's catalogue knows how to obtain this item
    // (true/false only for now). note is "" when there's nothing to say
    record Line(String itemId, String iconItemId, String displayName, int needed, int have, Kind kind, String note,
                boolean gettable) {

        public int missing() {
            return Math.max(0, needed - have);
        }

        public boolean covered() {
            return have >= needed;
        }
    }

    // what building a schematic costs. items is the shopping list (biggest first), cantPlace the blocks no survival
    // build can place, notCounted the fluid sources, notes plain sentences for the footer ("entities are not counted").
    // error is set (state READY, no lines) when the file couldn't be read
    record Materials(State state, String relativePath, List<Line> items, List<Line> cantPlace, List<Line> notCounted,
                     List<String> notes, String error) {

        public static Materials loading(String relativePath) {
            return new Materials(State.LOADING, relativePath, List.of(), List.of(), List.of(), List.of(), null);
        }

        public long totalNeeded() {
            return items.stream().mapToLong(Line::needed).sum();
        }

        public long totalMissing() {
            return items.stream().mapToLong(Line::missing).sum();
        }

        // the player holds everything on the list (an empty list counts)
        public boolean allCovered() {
            return items.stream().allMatch(Line::covered);
        }
    }

    // what an import did. imported are library paths ("castle (2).litematic"), skipped are files left alone and why,
    // errors are files that couldn't be copied. cancelled: the picker was closed without choosing anything
    record ImportReport(List<String> imported, List<String> skipped, List<String> errors, boolean cancelled) {

        public static ImportReport nothingChosen() {
            return new ImportReport(List.of(), List.of(), List.of(), true);
        }

        public static ImportReport failed(String error) {
            return new ImportReport(List.of(), List.of(), List.of(error), false);
        }

        // the toast: ok when anything came in, the first problem when nothing did
        public Result toResult() {
            if (cancelled) {
                return Result.ok("Nothing chosen");
            }
            if (imported.isEmpty()) {
                if (!errors.isEmpty()) {
                    return Result.fail(errors.get(0));
                }
                return skipped.isEmpty() ? Result.fail("No schematics to import") : Result.fail(skipped.get(0));
            }
            String head = imported.size() == 1 ? "Imported " + imported.get(0) : "Imported " + imported.size() + " schematics";
            int left = skipped.size() + errors.size();
            return Result.ok(left == 0 ? head : head + ", " + left + (left == 1 ? " file" : " files") + " left out");
        }
    }

    // the library as of now, starting the first load when nobody has asked yet. never blocks
    Listing list();

    // rescans the folder in the background. state flips to LOADING until it's done. cheap to call twice
    void refresh();

    // the cost of building one file (relativePath as in Row.relativePath), counted in the background and kept, so a
    // second call is free. have is re-read from the player's inventory on every call, so the numbers follow what the
    // player picks up. an unknown path comes back READY with an error
    Materials materials(String relativePath);

    // builds the file with its corner at the player's feet, the same call as the build command. the file is parsed on
    // the calling thread, like the command does. slice 2 puts a placement step in front of this
    Result build(String relativePath);

    // opens the native multi select picker (.litematic / .schem / .schematic) on a helper thread so the game keeps
    // drawing, copies the choice into the library and then calls done on the game thread. the library refreshes itself.
    // a picker that can't open gets an ImportReport with an error saying to use drag and drop or Open folder instead
    void importFiles(Consumer<ImportReport> done);

    // files dropped on the window (the gui screen forwards Screen#onFilesDrop here). copies synchronously, schematics are small
    ImportReport importDropped(List<Path> files);

    // opens <gameDir>/schematics in the file manager, creating it first when it doesn't exist
    Result openFolder();

    // the folder for display ("Drop files here or put them in .../schematics"), "" when there is none
    String folderPath();

    SchematicsAccess NONE = new SchematicsAccess() {
        private final Result nope = Result.fail("Not wired yet");

        @Override
        public Listing list() {
            return new Listing(State.READY, List.of());
        }

        @Override
        public void refresh() {
        }

        @Override
        public Materials materials(String relativePath) {
            return new Materials(State.READY, relativePath, List.of(), List.of(), List.of(), List.of(), "Not wired yet");
        }

        @Override
        public Result build(String relativePath) {
            return nope;
        }

        @Override
        public void importFiles(Consumer<ImportReport> done) {
            done.accept(ImportReport.failed("Not wired yet"));
        }

        @Override
        public ImportReport importDropped(List<Path> files) {
            return ImportReport.failed("Not wired yet");
        }

        @Override
        public Result openFolder() {
            return nope;
        }

        @Override
        public String folderPath() {
            return "";
        }
    };
}
