package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RunStateStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FP = "New World|";

    // every field set to something that is not its default
    private static RunState full() {
        RunState s = new RunState();
        s.fingerprint = FP;
        s.startedEpochMs = 1_700_000_000_123L;
        s.phase = GamerPhase.ROOM;
        s.phaseAttempts.put("GATHER", 2);
        s.phaseAttempts.put("NETHER", 1);
        s.phaseEnteredGameTime = 123456;
        s.netherRevisits = 1;
        s.stuckReason = "no lava";
        s.stuck = true;
        s.finished = false;
        s.runTicks = 987654321L;
        s.deathsThisPhase = 2;
        s.regressCounts.put("LOCATE>NETHER", 2);
        s.regressCounts.put("DRAGON>END_PREP", 1);

        RunState.Death d = new RunState.Death();
        d.dimension = "NETHER";
        d.x = -12;
        d.y = 34;
        d.z = 56;
        d.gameTime = 777;
        d.phase = "NETHER";
        s.deaths.add(d);
        s.deaths.add(d);

        s.overworldPortal = new RunState.Pos(1, 2, 3);
        s.netherPortal = new RunState.Pos(-4, 70, 5);
        s.portalMethod = "OBSIDIAN";
        s.visitedCells.add("0,0");
        s.visitedCells.add("-1,2");
        s.bastionCells.add("3,3");
        s.fortressCells.add("2,2");
        s.fortress.add(new RunState.Pos(10, 64, 10));
        s.fortress.add(new RunState.Pos(11, 65, 12));
        s.bastion.add(new RunState.Pos(-100, 70, 100));
        s.spawner = new RunState.Pos(9, 8, 7);
        s.warpedForest = new RunState.Pos(500, 40, -500);

        RunState.Ray r = new RunState.Ray();
        r.ox = 100.5;
        r.oz = -200.25;
        r.dx = 0.6;
        r.dz = 0.8;
        r.dived = true;
        s.strongholdRays.add(r);
        s.strongholdRays.add(new RunState.Ray());
        s.strongholdEstimate = new RunState.Pos(1200, 0, -900);
        s.strongholdRadius = 31.5;
        s.strongholdStart = new RunState.Pos(1204, 0, -892);
        s.roomChunksVisited.add("75,-56");
        s.roomChunksVisited.add("76,-56");
        s.eyeThrows = 5;

        s.endPortalCenter = new RunState.Pos(1210, 30, -890);
        s.framesFilled = 11;
        s.endPortalOpened = true;
        s.spawnBed = new RunState.Pos(1, 1, 1);
        s.spawnBedSet = true;
        s.endDrops.put("minecraft:diamond_sword", 1);
        s.endDrops.put("minecraft:white_bed", 7);
        s.placedTables.add(new RunState.Pos(245, 63, 40));
        s.placedTables.add(new RunState.Pos(-3, 70, 9));
        s.placedFurnaces.add(new RunState.Pos(12, 64, -4));
        s.placedSmokers.add(new RunState.Pos(13, 64, -4));
        s.villageChestsTried.add(new RunState.Pos(12, 64, -7));
        s.villageLootTicks = 1500;
        s.placedJobBlocks.add(new RunState.Pos(5, 64, 5));
        s.villageBedsTried.add(new RunState.Pos(8, 65, -2));
        s.villageBedTicks = 700;
        s.furnaceJobs.add(new RunState.FurnaceJob(new RunState.Pos(8, 63, -2), "OVERWORLD", "blast_furnace", "raw_iron", 12,
                "iron_ingot", 5000, 6200));
        s.dragonDead = true;
        return s;
    }

    private Path file() {
        return tmp.getRoot().toPath().resolve("world").resolve("altoclef").resolve("gamer.json");
    }

    private void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    @Test
    public void everyFieldSurvivesARoundTrip() {
        RunState in = full();
        assertTrue(RunStateStore.save(file(), in));
        RunStateStore.Loaded loaded = RunStateStore.load(file(), FP);
        assertTrue(loaded.resumed());
        RunState out = loaded.state();
        // the json is the contract, so comparing it covers every field the class has now and any one added later
        assertEquals(RunStateStore.toJson(in), RunStateStore.toJson(out));
        // and a few by value, so a symmetric serializer bug cannot hide
        assertEquals(GamerPhase.ROOM, out.phase);
        assertEquals(Integer.valueOf(2), out.phaseAttempts.get("GATHER"));
        assertEquals(new RunState.Pos(-4, 70, 5), out.netherPortal);
        assertEquals(List.of("0,0", "-1,2"), List.copyOf(out.visitedCells));
        assertEquals(2, out.fortress.size());
        assertEquals(new RunState.Pos(11, 65, 12), out.fortress.get(1));
        assertEquals(100.5, out.strongholdRays.get(0).ox, 0);
        assertTrue(out.strongholdRays.get(0).dived);
        assertEquals(Integer.valueOf(7), out.endDrops.get("minecraft:white_bed"));
        assertEquals(List.of(new RunState.Pos(245, 63, 40), new RunState.Pos(-3, 70, 9)), out.placedTables);
        assertEquals(List.of(new RunState.Pos(12, 64, -4)), out.placedFurnaces);
        assertEquals(List.of(new RunState.Pos(12, 64, -7)), out.villageChestsTried);
        assertEquals(1500L, out.villageLootTicks);
        assertEquals(List.of(new RunState.Pos(5, 64, 5)), out.placedJobBlocks);
        assertEquals(List.of(new RunState.Pos(8, 65, -2)), out.villageBedsTried);
        assertEquals(700L, out.villageBedTicks);
        assertEquals(1, out.furnaceJobs.size());
        RunState.FurnaceJob job = out.furnaceJobs.get(0);
        assertEquals(new RunState.Pos(8, 63, -2), job.pos);
        assertEquals("blast_furnace", job.kind);
        assertEquals(12, job.count);
        assertEquals("iron_ingot", job.output);
        assertEquals(6200L, job.doneTick);
        assertEquals("NETHER", out.deaths.get(0).dimension);
        assertEquals(987654321L, out.runTicks);
        assertEquals(Integer.valueOf(2), out.regressCounts.get("LOCATE>NETHER"));
        assertEquals(2, out.deathsThisPhase);
        assertEquals(1_700_000_000_123L, out.startedEpochMs);
        assertTrue(out.stuck);
        assertTrue(out.dragonDead);
    }

    @Test
    public void thePhaseIsWrittenAsItsNameAndSetsAsArrays() {
        String json = RunStateStore.toJson(full());
        assertTrue(json, json.contains("\"phase\": \"ROOM\""));
        assertTrue(json, json.contains("\"visitedCells\": ["));
    }

    @Test
    public void saveCreatesTheFolderAndLeavesNoTmpFileBehind() throws IOException {
        assertFalse(Files.exists(file().getParent()));
        assertTrue(RunStateStore.save(file(), new RunState()));
        assertTrue(Files.isRegularFile(file()));
        assertFalse(Files.exists(file().resolveSibling("gamer.json.tmp")));
        // a second save replaces the first
        RunState s = new RunState();
        s.phase = GamerPhase.EYES;
        assertTrue(RunStateStore.save(file(), s));
        assertEquals(GamerPhase.EYES, RunStateStore.load(file(), "").state().phase);
    }

    @Test
    public void noFileMeansAFreshStateForThisWorld() {
        RunStateStore.Loaded loaded = RunStateStore.load(file(), FP);
        assertFalse(loaded.resumed());
        assertEquals(GamerPhase.GATHER, loaded.state().phase);
        assertEquals(FP, loaded.state().fingerprint);
    }

    @Test
    public void unknownFieldsAreIgnoredAndMissingOnesKeepTheirDefaults() throws IOException {
        write(file(), "{\"schema\":1,\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\",\"fromTheFuture\":{\"a\":[1,2,3]},\"eyeThrows\":3}");
        RunStateStore.Loaded loaded = RunStateStore.load(file(), FP);
        assertTrue(loaded.resumed());
        assertEquals(GamerPhase.IRON, loaded.state().phase);
        assertEquals(3, loaded.state().eyeThrows);
        assertEquals("CAST", loaded.state().portalMethod);
        assertNotNull(loaded.state().visitedCells);
        assertTrue(loaded.state().deaths.isEmpty());
    }

    @Test
    public void nullCollectionsInAHandEditedFileBecomeEmptyOnes() throws IOException {
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\",\"visitedCells\":null,\"deaths\":null,\"endDrops\":null,\"strongholdRays\":null,\"phaseAttempts\":null,\"regressCounts\":null}");
        RunState s = RunStateStore.load(file(), FP).state();
        assertTrue(s.visitedCells.isEmpty());
        assertTrue(s.deaths.isEmpty());
        assertTrue(s.endDrops.isEmpty());
        assertTrue(s.strongholdRays.isEmpty());
        assertTrue(s.regressCounts.isEmpty());
        assertEquals(0, s.attemptsOf(GamerPhase.IRON));
    }

    @Test
    public void aFileFromBeforeTheTableListHasNoTablesAndANullListIsEmpty() throws IOException {
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\"}");
        assertTrue(RunStateStore.load(file(), FP).state().placedTables.isEmpty());
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\",\"placedTables\":null,\"placedFurnaces\":null}");
        assertTrue(RunStateStore.load(file(), FP).state().placedTables.isEmpty());
        assertTrue(RunStateStore.load(file(), FP).state().placedFurnaces.isEmpty());
    }

    @Test
    public void aFileFromBeforeSmokersLoadsWithNoSmokersAndOldJobsAreNotFood() throws IOException {
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\",\"placedFurnaces\":[{\"x\":1,\"y\":64,\"z\":2}],"
                + "\"furnaceJobs\":[{\"pos\":{\"x\":1,\"y\":64,\"z\":2},\"dimension\":\"OVERWORLD\",\"kind\":\"furnace\","
                + "\"input\":\"raw_iron\",\"count\":9,\"output\":\"iron_ingot\",\"startTick\":10,\"doneTick\":1810}]}");
        RunState s = RunStateStore.load(file(), FP).state();
        assertTrue(s.placedSmokers.isEmpty());
        assertEquals(1, s.placedFurnaces.size());
        assertEquals(1, s.furnaceJobs.size());
        assertEquals(0, s.furnaceJobs.get(0).unitsEach);
        assertEquals(0, FurnaceJobs.pendingUnits(s.furnaceJobs));
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\",\"placedSmokers\":null}");
        assertTrue(RunStateStore.load(file(), FP).state().placedSmokers.isEmpty());
    }

    @Test
    public void smokersAndFoodJobsSurviveASaveAndLoad() throws IOException {
        RunState s = new RunState();
        s.fingerprint = FP;
        s.phase = GamerPhase.GATHER;
        s.placedSmokers.add(new RunState.Pos(3, 70, -8));
        RunState.FurnaceJob job = new RunState.FurnaceJob(new RunState.Pos(3, 70, -8), "OVERWORLD", "smoker", "mutton", 7,
                "cooked_mutton", 100, 800);
        job.unitsEach = 6;
        s.furnaceJobs.add(job);
        RunStateStore.save(file(), s);
        RunState out = RunStateStore.load(file(), FP).state();
        assertEquals(List.of(new RunState.Pos(3, 70, -8)), out.placedSmokers);
        assertEquals(6, out.furnaceJobs.get(0).unitsEach);
        assertEquals(42, FurnaceJobs.pendingUnits(out.furnaceJobs));
    }

    @Test
    public void aFileFromBeforeTheVillageLootHasNothingVisitedAndNullListsAreEmpty() throws IOException {
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\"}");
        RunState s = RunStateStore.load(file(), FP).state();
        assertTrue(s.villageChestsTried.isEmpty());
        assertTrue(s.placedJobBlocks.isEmpty());
        assertTrue(s.villageBedsTried.isEmpty());
        assertEquals(0L, s.villageLootTicks);
        assertEquals(0L, s.villageBedTicks);
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"IRON\",\"villageChestsTried\":null,\"placedJobBlocks\":null,\"villageBedsTried\":null}");
        s = RunStateStore.load(file(), FP).state();
        assertTrue(s.villageChestsTried.isEmpty());
        assertTrue(s.placedJobBlocks.isEmpty());
        assertTrue(s.villageBedsTried.isEmpty());
    }

    @Test
    public void onlyTheSharedFileIsTheFallback() {
        assertTrue(RunStateStore.isSharedFallback(tmp.getRoot().toPath().resolve("gamer-nocache.json")));
        assertFalse(RunStateStore.isSharedFallback(file()));
    }

    @Test
    public void aCorruptFileIsMovedAsideAndTheRunStartsFresh() throws IOException {
        write(file(), "{ this is not json");
        RunStateStore.Loaded loaded = RunStateStore.load(file(), FP);
        assertFalse(loaded.resumed());
        assertEquals(GamerPhase.GATHER, loaded.state().phase);
        assertFalse(Files.exists(file()));
        assertTrue(Files.exists(file().resolveSibling("gamer.json.bad")));
        assertEquals("{ this is not json", Files.readString(file().resolveSibling("gamer.json.bad")));
    }

    @Test
    public void anEmptyFileOrAnUnknownPhaseIsCorruptToo() throws IOException {
        write(file(), "");
        assertFalse(RunStateStore.load(file(), FP).resumed());
        assertTrue(Files.exists(file().resolveSibling("gamer.json.bad")));
        write(file(), "{\"fingerprint\":\"" + FP + "\",\"phase\":\"BREAKFAST\"}");
        assertFalse(RunStateStore.load(file(), FP).resumed());
        assertFalse(Files.exists(file()));
    }

    @Test
    public void aNewerSchemaIsNotTrustedAndIsKept() throws IOException {
        write(file(), "{\"schema\":" + (RunState.SCHEMA + 1) + ",\"fingerprint\":\"" + FP + "\",\"phase\":\"NETHER\"}");
        RunStateStore.Loaded loaded = RunStateStore.load(file(), FP);
        assertFalse(loaded.resumed());
        assertEquals(GamerPhase.GATHER, loaded.state().phase);
        // moved aside so the newer version can still read it after a downgrade and upgrade
        assertTrue(Files.exists(file().resolveSibling("gamer.json.bad")));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void anotherWorldsFileIsIgnoredAndLeftAlone() throws IOException {
        RunState other = full();
        other.fingerprint = "Other World|";
        RunStateStore.save(file(), other);
        RunStateStore.Loaded loaded = RunStateStore.load(file(), FP);
        assertFalse(loaded.resumed());
        assertEquals(FP, loaded.state().fingerprint);
        assertEquals(GamerPhase.GATHER, loaded.state().phase);
        // not renamed, a world that was deleted and recreated under the same folder name is overwritten by the next save
        assertTrue(Files.exists(file()));
        assertFalse(Files.exists(file().resolveSibling("gamer.json.bad")));
    }

    @Test
    public void deleteForgetsTheRun() {
        RunStateStore.save(file(), full());
        assertTrue(RunStateStore.delete(file()));
        assertFalse(Files.exists(file()));
        assertFalse(RunStateStore.delete(file()));
        assertNull(RunStateStore.parse("not json at all"));
    }
}
