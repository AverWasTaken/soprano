/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.preview.*;
import adris.altoclef.ui.gui.kit.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StoragePanelTest {
    private static List<Widget> flatten(Widget root) {
        List<Widget> result=new ArrayList<>(); result.add(root);
        for(Widget child:root.children()) result.addAll(flatten(child));
        return result;
    }
    private static Button button(Widget root,String text) {
        return flatten(root).stream().filter(w->w instanceof Button b && b.text().equals(text)).map(w->(Button)w).findFirst().orElseThrow();
    }
    private static void click(Preview p,Widget w) { p.ctx.focus(w); p.tick(); p.click(w.x()+w.w()/2f,w.y()+w.h()/2f); p.tick(); }
    private Preview create(int width,int height,EffortlessTab[] tab) throws Exception {
        FakeFacade f=new FakeFacade();
        Preview p=new Preview(width,height,f,pv->{
            tab[0]=new EffortlessTab(pv.theme,pv.facade,pv.ctx); return List.of(tab[0]);
        });
        p.tick();
        Segmented pages=(Segmented)tab[0].content().children().get(0);
        p.click(pages.x()+pages.w()*.8f,pages.y()+pages.h()/2f); p.tick(); p.settle(); p.advance(400);
        return p;
    }
    private StoragePanel panel(EffortlessTab tab) { return (StoragePanel)tab.content().children().get(1); }

    @Test public void storageListAndEditorRenderAtDesktopAndCompactSizes() throws Exception {
        for(int[] size:new int[][]{{1920,1080},{1280,720}}) {
            EffortlessTab[] tab=new EffortlessTab[1]; Preview p=create(size[0],size[1],tab);
            p.savePanel("effortless_storage_list_"+size[0]);
            StoragePanel panel=panel(tab[0]); panel.edit(p.facade.effortless.storages().get(0)); p.tick(); p.settle();
            p.savePanel("effortless_storage_editor_"+size[0]);
            Widget form=panel.children().get(0).children().get(2);
            assertTrue(form.h()>0); assertTrue(form.y()+form.h()<=panel.y()+panel.h());
            p.shell.setDarkMode(false); p.settle(); p.savePanel("effortless_storage_editor_light_"+size[0]);
        }
    }
    @Test public void savingUsesTheEditedEntryAndRemoveRequiresConfirmation() throws Exception {
        EffortlessTab[] tab=new EffortlessTab[1]; Preview p=create(1600,900,tab); StoragePanel panel=panel(tab[0]);
        panel.edit(p.facade.effortless.storages().get(0)); p.tick();
        TextField name=(TextField)flatten(panel).stream().filter(w->w instanceof TextField).findFirst().orElseThrow();
        name.setText("Building materials"); click(p,button(panel,"Save"));
        assertEquals("Building materials",p.facade.effortless.storages().get(0).name());
        panel.edit(p.facade.effortless.storages().get(0)); p.tick();
        click(p,button(panel,"Lower priority"));
        assertEquals("stone",p.facade.effortless.storages().get(1).id());
        panel.edit(p.facade.effortless.storages().get(1)); p.tick();
        click(p,button(panel,"Remove storage")); assertEquals(3,p.facade.effortless.storages().size());
        click(p,button(panel,"Confirm remove")); assertEquals(2,p.facade.effortless.storages().size());
        assertFalse(p.facade.effortless.storages().stream().anyMatch(s->s.id().equals("stone")));
    }
}
