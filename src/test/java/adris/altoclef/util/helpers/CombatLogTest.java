package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Why;
import baritone.api.utils.Dimension;
import org.junit.Test;

// one `combat:` line per transition, and the dimension is in it. the rest of the line reads the same in all three
public class CombatLogTest {

    private static String line(Dimension d, Event event, Why why) {
        return CombatLog.line(d, event, why, false, "zombie", "zombie", "2 zombies, 1 skeleton", 14, 52);
    }

    @Test
    public void everyTransitionSaysWhichDimensionItWasIn() {
        for (Dimension d : Dimension.values()) {
            for (Event event : Event.values()) {
                if (event == Event.NONE) continue;
                String text = line(d, event, Why.HIT);
                assertTrue(event + " in " + d + ": " + text, text.startsWith("combat: [" + d.name().toLowerCase() + "] "));
            }
        }
    }

    @Test
    public void noTransitionNoLine() {
        for (Dimension d : Dimension.values()) {
            assertNull(line(d, Event.NONE, Why.NONE));
        }
    }

    @Test
    public void everyLineIsOneLine() {
        for (Dimension d : Dimension.values()) {
            for (Event event : Event.values()) {
                if (event == Event.NONE) continue;
                for (Why why : Why.values()) {
                    String text = line(d, event, why);
                    assertFalse(text, text.contains("\n"));
                    assertFalse(text, text.contains("null"));
                }
            }
        }
    }

    @Test
    public void theFightLinesNameTheTargetAndTheHp() {
        assertEquals("combat: [nether] FIGHT zombie (hit us, hp 14)", line(Dimension.NETHER, Event.FIGHT_START, Why.HIT));
        assertEquals("combat: [end] target dead, next zombie (hit us, hp 14)", line(Dimension.END, Event.FIGHT_NEXT, Why.HIT));
        assertEquals("combat: [end] cornered, next zombie",
                CombatLog.line(Dimension.END, Event.FIGHT_NEXT, Why.CORNERED, true, "zombie", "zombie", "", 14, 0));
        assertEquals("combat: [nether] cornered, fighting zombie", line(Dimension.NETHER, Event.RUN_TO_FIGHT, Why.CORNERED));
    }

    @Test
    public void theRunLinesNameTheCrowdAndTheReason() {
        assertEquals("combat: [nether] RUN from 2 zombies, 1 skeleton (hp 14)", line(Dimension.NETHER, Event.RUN_START, Why.LOW_HP));
        assertEquals("combat: [nether] RUN from 2 zombies, 1 skeleton (something nasty close, hp 14)", line(Dimension.NETHER, Event.RUN_START, Why.HEAVY));
        assertEquals("combat: [overworld] FIGHT -> RUN from 2 zombies, 1 skeleton (hp 14)", line(Dimension.OVERWORLD, Event.FIGHT_TO_RUN, Why.LOW_HP));
    }

    @Test
    public void theEndingLinesSayHowItEnded() {
        assertEquals("combat: [overworld] fight over, zombie dead", line(Dimension.OVERWORLD, Event.FIGHT_DEAD, Why.NONE));
        assertEquals("combat: [overworld] fight over, lost track of zombie", line(Dimension.OVERWORLD, Event.FIGHT_LOST, Why.NONE));
        assertEquals("combat: [overworld] fight over, can't get to zombie, ignoring it until it hits us again",
                line(Dimension.OVERWORLD, Event.FIGHT_STALLED, Why.NONE));
        assertEquals("combat: [nether] run over, 52 blocks, clear", line(Dimension.NETHER, Event.RUN_CLEAR, Why.NONE));
        assertEquals("combat: [nether] run over, 25 s cap, 52 blocks", line(Dimension.NETHER, Event.RUN_CAP, Why.NONE));
        assertEquals("combat: [end] run over, stuck 52 blocks from where it started", line(Dimension.END, Event.RUN_STUCK, Why.NONE));
    }
}
