package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.SuggestList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;

// the type-ahead open under a list editor box and under an inspector box, a row lit, both appearances. judged by eye
public class CommandSuggestPreviewTest {

    private FakeFacade facade;
    private Preview p;
    private CommandsTab tab;

    @Before
    public void setUp() throws Exception {
        facade = new FakeFacade();
        facade.routines.seed();
        facade.routines.seedGraphs();
        CommandsTab[] holder = new CommandsTab[1];
        p = new Preview(1920, 1200, 1f, facade, pv -> {
            holder[0] = new CommandsTab(pv.theme, pv.facade, pv.ctx);
            return List.of(holder[0]);
        });
        tab = holder[0];
        tab.onShown();
        settle();
    }

    private void settle() {
        p.tick();
        p.settle();
        p.advance(400);
    }

    private SuggestList type(StepField f, String s, int downs) {
        p.shell.focus(f);
        settle();
        f.setText("");
        p.type(s);
        for (int i = 0; i < downs; i++) {
            p.key(Keys.DOWN);
        }
        settle();
        return f.suggest();
    }

    private void both(String name) throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            p.shell.setDarkMode(dark);
            p.advance(500);
            Path out = p.savePanel("suggest_" + name + (dark ? "_dark" : "_light"));
            assertTrue(Files.size(out) > 0);
        }
    }

    private static StepField firstBox(Widget w) {
        if (w instanceof StepField f && f.suggest() != null) {
            return f;
        }
        for (Widget k : w.children()) {
            StepField got = firstBox(k);
            if (got != null) {
                return got;
            }
        }
        return null;
    }

    @Test
    public void underTheEquipBox() throws Exception {
        assertTrue(type(tab.editor().rows().get(1).itemField(), "iron", 2).isOpen());
        both("list_equip");
    }

    @Test
    public void underGetsAddBox() throws Exception {
        assertTrue(type(tab.editor().rows().get(0).addField(), "diamond", 1).isOpen());
        both("list_add");
    }

    @Test
    public void underTheInspectorsBlockBox() throws Exception {
        tab.select(tab.list().items().stream().filter(r -> r.id().equals("Wood_loop")).findFirst().orElseThrow());
        settle();
        tab.nodeEditor().select("m");
        settle();
        assertTrue(type(firstBox(tab.nodeEditor().inspector()), "log", 1).isOpen());
        both("inspector_block");
    }
}
