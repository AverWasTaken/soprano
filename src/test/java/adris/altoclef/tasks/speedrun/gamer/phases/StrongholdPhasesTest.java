package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import baritone.api.utils.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// handler isDone / regressTo are pure over fake facts. Items need the registry, hence the bootstrap
public class StrongholdPhasesTest {
    private final GamerConfig cfg = new GamerConfig();
    private final LocatePhase locate = new LocatePhase();
    private final RoomPhase room = new RoomPhase();
    private final OpenPhase open = new OpenPhase();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static class Facts implements GamerFacts {
        final Map<Item, Integer> items = new HashMap<>();
        long time = 100_000;

        Facts with(Item item, int n) {
            items.put(item, n);
            return this;
        }

        @Override
        public Dimension dimension() {
            return Dimension.OVERWORLD;
        }

        @Override
        public int count(Item item) {
            return items.getOrDefault(item, 0);
        }

        @Override
        public boolean armorEquipped(Item item) {
            return false;
        }

        @Override
        public int armorPoints() {
            return 0;
        }

        @Override
        public int foodUnits() {
            return 0;
        }

        @Override
        public int buildBlocks() {
            return 0;
        }

        @Override
        public int x() {
            return 0;
        }

        @Override
        public int y() {
            return 64;
        }

        @Override
        public int z() {
            return 0;
        }

        @Override
        public long gameTime() {
            return time;
        }

        @Override
        public boolean creditsShown() {
            return false;
        }
    }

    // ---- LOCATE

    @Test
    public void locateIsDoneOnceThereIsAStartOrAPortal() {
        RunState s = new RunState();
        assertFalse(locate.isDone(new Facts(), s, cfg));
        s.strongholdStart = new RunState.Pos(1700, 0, -1204);
        assertTrue(locate.isDone(new Facts(), s, cfg));
        RunState p = new RunState();
        p.endPortalCenter = new RunState.Pos(1, 30, 2);
        assertTrue(locate.isDone(new Facts(), p, cfg));
    }

    @Test
    public void locateRegressesWhenWeCannotGetToTwelveEyes() {
        RunState s = new RunState();
        // 4 eyes, nothing to craft with: way short
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts().with(Items.ENDER_EYE, 4), s, cfg));
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts(), s, cfg));
    }

    @Test
    public void locateKeepsGoingWithTwelveOrWithTheOneInFlight() {
        RunState s = new RunState();
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 12), s, cfg));
        // 12 eyes, one just thrown: 11 in the bag must not send us back to the nether
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 11), s, cfg));
        assertEquals(Optional.of(GamerPhase.NETHER), locate.regressTo(new Facts().with(Items.ENDER_EYE, 10), s, cfg));
    }

    @Test
    public void locateCountsFramesAlreadyFilledAndCraftableEyes() {
        RunState s = new RunState();
        s.framesFilled = 2;
        assertEquals(Optional.empty(), locate.regressTo(new Facts().with(Items.ENDER_EYE, 9), s, cfg));
        // 6 eyes + 3 pearls with 2 rods (4 powder) = 9 eyes + 1 in flight: 10 + 2 filled = 12
        Facts f = new Facts().with(Items.ENDER_EYE, 6).with(Items.ENDER_PEARL, 3).with(Items.BLAZE_ROD, 2);
        assertEquals(Optional.empty(), locate.regressTo(f, s, cfg));
    }

    @Test
    public void craftableEyesAreLimitedByThePearlsAndThePowder() {
        assertEquals(3, StrongholdRules.craftableEyes(new Facts().with(Items.ENDER_PEARL, 3).with(Items.BLAZE_POWDER, 9)));
        assertEquals(2, StrongholdRules.craftableEyes(new Facts().with(Items.ENDER_PEARL, 9).with(Items.BLAZE_POWDER, 2)));
        // a rod is two powder
        assertEquals(4, StrongholdRules.craftableEyes(new Facts().with(Items.ENDER_PEARL, 9).with(Items.BLAZE_ROD, 2)));
        assertEquals(0, StrongholdRules.craftableEyes(new Facts().with(Items.BLAZE_ROD, 5)));
    }

    @Test
    public void onlyOneNetherRevisit() {
        RunState s = new RunState();
        s.netherRevisits = 1;
        assertEquals(Optional.empty(), locate.regressTo(new Facts(), s, cfg));
        assertEquals(Optional.empty(), room.regressTo(new Facts(), withStart(s), cfg));
    }

    // ---- ROOM

    private static RunState withStart(RunState s) {
        s.strongholdStart = new RunState.Pos(10, 0, 10);
        return s;
    }

    @Test
    public void roomIsDoneWithTheRingKnownAndAllFramesAccountedFor() {
        RunState s = withStart(new RunState());
        assertFalse(room.isDone(new Facts(), s, cfg));
        s.endPortalCenter = new RunState.Pos(10, 30, 20);
        s.framesSeen = 11;
        assertFalse(room.isDone(new Facts(), s, cfg));
        s.framesSeen = 12;
        assertTrue(room.isDone(new Facts(), s, cfg));
    }

    @Test
    public void roomWithNothingToSearchFromGoesBackToLocate() {
        RunState s = new RunState();
        assertEquals(Optional.of(GamerPhase.LOCATE), room.regressTo(new Facts().with(Items.ENDER_EYE, 12), s, cfg));
        // with a start (or a portal) there is no reason to
        assertEquals(Optional.empty(), room.regressTo(new Facts().with(Items.ENDER_EYE, 12), withStart(s), cfg));
        RunState p = new RunState();
        p.endPortalCenter = new RunState.Pos(1, 2, 3);
        assertEquals(Optional.empty(), room.regressTo(new Facts().with(Items.ENDER_EYE, 12), p, cfg));
    }

    @Test
    public void roomRegressesToNetherWhenEyesAreGone() {
        RunState s = withStart(new RunState());
        assertEquals(Optional.of(GamerPhase.NETHER), room.regressTo(new Facts().with(Items.ENDER_EYE, 3), s, cfg));
    }

    // ---- OPEN

    @Test
    public void openIsDoneWhenThePortalIsOpen() {
        RunState s = new RunState();
        assertFalse(open.isDone(new Facts(), s, cfg));
        s.endPortalOpened = true;
        assertTrue(open.isDone(new Facts(), s, cfg));
    }

    @Test
    public void openWaitsOutTheLagBeforeCallingItAShortage() {
        RunState s = new RunState();
        s.endPortalCenter = new RunState.Pos(1, 2, 3);
        s.framesFilled = 10;
        Facts f = new Facts();
        // two frames empty, no eyes, nothing to craft: starved
        assertTrue(StrongholdRules.starved(f, s));
        // but the handler has not noticed yet (openNoEyesSince 0): no regress
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        s.openNoEyesSince = f.time;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        f.time += 99;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        f.time += 1;
        assertEquals(Optional.of(GamerPhase.NETHER), open.regressTo(f, s, cfg));
    }

    @Test
    public void openDoesNotRegressWhenTheLastEyeIsJustGoingIn() {
        RunState s = new RunState();
        s.endPortalCenter = new RunState.Pos(1, 2, 3);
        // 11 filled and the last eye in hand: fine
        s.framesFilled = 11;
        Facts f = new Facts().with(Items.ENDER_EYE, 1);
        assertFalse(StrongholdRules.starved(f, s));
        s.openNoEyesSince = 1;
        f.time = 1_000_000;
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
        // portal opened: never starved
        s.framesFilled = 5;
        s.endPortalOpened = true;
        assertFalse(StrongholdRules.starved(new Facts(), s));
    }

    @Test
    public void openCanCraftItsWayOutOfAShortage() {
        RunState s = new RunState();
        s.framesFilled = 10;
        Facts f = new Facts().with(Items.ENDER_PEARL, 2).with(Items.BLAZE_POWDER, 2);
        assertFalse(StrongholdRules.starved(f, s));
    }

    @Test
    public void openGivesUpForGoodAfterTheNetherRevisit() {
        RunState s = new RunState();
        s.framesFilled = 10;
        s.netherRevisits = 1;
        Facts f = new Facts();
        s.openNoEyesSince = f.time;
        assertFalse(StrongholdRules.starvedForGood(f, s));
        f.time += 100;
        assertTrue(StrongholdRules.starvedForGood(f, s));
        assertEquals(Optional.empty(), open.regressTo(f, s, cfg));
    }

    @Test
    public void handlersNameThemselves() {
        assertEquals(GamerPhase.LOCATE, locate.phase());
        assertEquals(GamerPhase.ROOM, room.phase());
        assertEquals(GamerPhase.OPEN, open.phase());
        assertEquals(GamerPhase.LOCATE.hud(), locate.hud());
        assertEquals(GamerPhase.ROOM.hud(), room.hud());
        assertEquals(GamerPhase.OPEN.hud(), open.hud());
    }
}
