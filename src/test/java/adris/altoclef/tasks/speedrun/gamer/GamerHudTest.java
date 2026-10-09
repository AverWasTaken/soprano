package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherTripRules;
import adris.altoclef.util.helpers.FoodHelper;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the cards GamerHud builds without the engine's tick: the one for a recovery that has the wheel
public class GamerHudTest {
    private final GamerConfig cfg = new GamerConfig();
    private final RunState state = new RunState();
    private final FakeFacts facts = new FakeFacts();
    private final GamerHud hud = new GamerHud();

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void aRecoveryCardSaysWhatTheRecoveryIsDoingAndShowsNoKit() {
        state.phase = GamerPhase.IRON;
        GamerHudState card = hud.recovering(state, facts, cfg, 312.5, 2, HudRules.recoveryWords(47));
        assertEquals("Getting our stuff back, 47 blocks", card.action());
        assertTrue(card.rows().isEmpty());
        // the coal detour is a side job of the phase, the phase is not running
        assertNull(card.coal());
        // and the rest of the card is the phase's: its name, its clock held where the recovery stopped it, the attempt
        assertEquals(GamerPhase.IRON, card.phase());
        assertEquals(312.5, card.secondsInPhase(), 1e-9);
        assertEquals(cfg.budgets.minutes(GamerPhase.IRON), card.budgetMinutes(), 1e-9);
        assertEquals(2, card.attempt());
    }

    @Test
    public void theNetherTripReadsItsStageOnTheSameCard() {
        state.phase = GamerPhase.NETHER;
        GamerHudState walk = hud.recovering(state, facts, cfg, 10, 1, HudRules.tripWords(NetherTripRules.Stage.WALK, 212));
        assertEquals("Walking to our stuff, 212 blocks", walk.action());
        assertTrue(walk.rows().isEmpty());
        GamerHudState portal = hud.recovering(state, facts, cfg, 10, 1, HudRules.tripWords(NetherTripRules.Stage.PORTAL, -1));
        assertEquals("Heading back to the nether", portal.action());
    }

    @Test
    public void furnacesStillCookingStayOnTheCardThroughARecovery() {
        // a batch in a furnace does not care that we died, its clock runs on and the card keeps counting it
        state.phase = GamerPhase.IRON;
        facts.cooking("iron_ingot", 12, 60);
        GamerHudState card = hud.recovering(state, facts, cfg, 100, 1, HudRules.recoveryWords(10));
        assertEquals(1, card.furnaces().size());
        assertEquals("Smelting 12 iron", card.furnaces().get(0).words());
        assertEquals(Items.FURNACE, card.furnaces().get(0).icon());
    }

    @Test
    public void theFooterFoodIsWhatTheGatesActOnInThatPhase() {
        // three raw porkchop, a smoker and coal: the kit's phases cook them (24), later ones eat them raw (9)
        facts.give(Items.PORKCHOP, 3).give(Items.COAL, 2);
        facts.smokerPlaced = true;
        facts.foodUnits = 3 * FoodHelper.plannedNutrition(Items.PORKCHOP);
        state.phase = GamerPhase.IRON;
        GamerHudState iron = hud.recovering(state, facts, cfg, 0, 1, HudRules.recoveryWords(5));
        assertEquals(24, iron.foodUnits());
        assertEquals(cfg.overworld.minFoodUnits, iron.foodTarget());
        state.phase = GamerPhase.LOCATE;
        assertEquals(9, hud.recovering(state, facts, cfg, 0, 1, HudRules.recoveryWords(5)).foodUnits());
    }
}
