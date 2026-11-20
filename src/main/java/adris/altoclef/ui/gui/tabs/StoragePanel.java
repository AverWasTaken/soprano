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

import adris.altoclef.ui.gui.facade.EffortlessAccess;
import adris.altoclef.ui.gui.facade.EffortlessAccess.Storage;
import adris.altoclef.ui.gui.facade.EffortlessAccess.Region;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.*;
import java.util.ArrayList;
import java.util.List;

// list first, one spacious editor when asked: no wall of controls across every storage row
final class StoragePanel extends Widget {
    private final EffortlessAccess access;
    private final ShellContext ctx;
    private final VirtualList<Storage> list;
    private Editor editor;
    private int ticks;

    StoragePanel(Theme theme, EffortlessAccess access, ShellContext ctx) {
        super(theme); this.access = access; this.ctx = ctx;
        list = new VirtualList<>(theme,theme.px(86),this::drawRow);
        list.setSelectable(true);
        list.setKeyFunction(Storage::id);
        list.setEmptyText("No storage marked. With an empty hand, select two corners in Effortless mode");
        list.onClick((row,i) -> edit(row));
        refresh();
    }

    void refresh() { list.setItems(access.storages()); }
    void edit(Storage row) { editor = new Editor(row); layout(); ctx.focus(editor.name); }
    private void back() { editor = null; ctx.blur(); refresh(); layout(); }
    @Override public List<Widget> children() { return editor == null ? List.of(list) : List.of(editor); }
    @Override protected void layout() {
        int pad=theme.px(Theme.PAD);
        list.setBounds(x+pad,y+theme.px(48),w-2*pad,Math.max(0,h-theme.px(64)));
        if (editor != null) editor.setBounds(x,y,w,h);
    }
    @Override public void tick() { super.tick(); if (++ticks%20==0 && editor==null) refresh(); }
    @Override public void draw(Canvas c, Frame f) {
        if (editor != null) { editor.draw(c,f); return; }
        float pad=theme.pxf(Theme.PAD);
        c.text(access.storages().size()+" storages · highest priority first",x+pad,y+pad+c.ascent(theme.label),theme.label,theme.color(Theme.TEXT));
        ListCard.card(theme,c,list,f.nowNanos(),list.items().size()*list.rowHeight());
        list.draw(c,f);
    }
    private void drawRow(Canvas c, Frame f, Storage row, int index, int x, int y, int w, int h, boolean hover) {
        ListCard.separator(theme,c,x,y,w,index);
        if (hover) c.fillRoundRect(x,y,w,h,theme.pxf(8),theme.color(Theme.HOVER));
        float pad=theme.pxf(12), line=y+pad+c.ascent(theme.label);
        c.text(c.ellipsize((index+1)+".  "+row.name(),theme.label,w-2*pad),x+pad,line,theme.label,theme.color(Theme.TEXT));
        line+=theme.pxf(22);
        String role=List.of("Input & output","Output only","Input only").get(row.role());
        String rules=row.match()==0 ? "All items" : (row.match()==1 ? "Only " : "Except ")+row.items();
        c.text(c.ellipsize(role+" · "+rules,theme.small,w-2*pad),x+pad,line,theme.small,theme.color(Theme.TEXT_2));
        line+=theme.pxf(20);
        String areas=row.regions().size()>1 ? row.regions().size()+" separate areas" : row.regions().get(0).a()+" → "+row.regions().get(0).b();
        c.text(c.ellipsize(areas+" · select to edit",theme.small,w-2*pad),x+pad,line,theme.small,theme.color(Theme.TEXT_3));
    }

    final class Editor extends Widget {
        private final Storage original;
        private final TextField name, items, first, second;
        private final Segmented role, match;
        private final Button save, cancel, up, down, merge, remove, nextArea, dropArea;
        private final Form form;
        private final List<Region> regions;
        private int region;
        private boolean confirming;
        private boolean error;
        private String message="Changes apply to the next storage trip";

        Editor(Storage row) {
            super(StoragePanel.this.theme); original=row; regions=new ArrayList<>(row.regions());
            name=new TextField(theme,"Storage name",s->save()); name.setText(row.name());
            items=new TextField(theme,"stone, oak_log, #minecraft:planks",s->save()); items.setText(row.items());
            first=new TextField(theme,"X Y Z",s->{}); second=new TextField(theme,"X Y Z",s->{});
            loadRegion();
            role=new Segmented(theme,List.of("Both","Output","Input"),row.role(),i->{});
            match=new Segmented(theme,List.of("All items","Only","Except"),row.match(),i->{ error=false; });
            items.setOnChange(text -> {
                items.setInvalid(false); error=false;
                if (match.selected()==0 && !text.isBlank()) match.setSelected(1);
            });
            save=new Button(theme,"Save",Button.Kind.PRIMARY,Icon.CHECK,this::save);
            cancel=new Button(theme,"Back",Button.Kind.SECONDARY,Icon.CHEVRON_LEFT,StoragePanel.this::back);
            up=new Button(theme,"Higher priority",Button.Kind.SECONDARY,Icon.CHEVRON_UP,()->applyThen(()->access.moveStorage(row.id(),-1)));
            down=new Button(theme,"Lower priority",Button.Kind.SECONDARY,Icon.CHEVRON_DOWN,()->applyThen(()->access.moveStorage(row.id(),1)));
            merge=new Button(theme,"Merge next",Button.Kind.SECONDARY,Icon.MERGE,()->applyThen(()->access.mergeStorage(row.id())));
            remove=new Button(theme,"Remove storage",Button.Kind.DANGER,Icon.TRASH,this::removeEntry);
            nextArea=new Button(theme,"Next area",Button.Kind.SECONDARY,Icon.CHEVRON_RIGHT,()->{
                keepRegion(); region=(region+1)%regions.size(); loadRegion();
            });
            dropArea=new Button(theme,"Remove area",Button.Kind.SECONDARY,null,()->{
                if (regions.size()>1) { regions.remove(region); region=Math.min(region,regions.size()-1); loadRegion(); }
            });
            nextArea.setEnabled(regions.size()>1); dropArea.setEnabled(regions.size()>1);
            int index=access.storages().indexOf(row);
            up.setEnabled(index>0); down.setEnabled(index>=0 && index+1<access.storages().size());
            merge.setEnabled(index>=0 && index+1<access.storages().size());
            form=new Form();
        }
        private void removeEntry() {
            if (!confirming) { confirming=true; remove.setText("Confirm remove"); return; }
            action(access.removeStorage(original.id()));
        }
        private void loadRegion() { first.setText(regions.get(region).a()); second.setText(regions.get(region).b()); }
        private void keepRegion() { regions.set(region,new Region(first.text(),second.text())); }
        private Storage draft() { keepRegion(); return new Storage(original.id(),name.text(),role.selected(),match.selected(),items.text(),List.copyOf(regions)); }
        private void applyThen(java.util.function.Supplier<Result> action) {
            Result saved=access.updateStorage(draft());
            if (!saved.ok()) { message=saved.message(); error=true; ctx.toast(saved); return; }
            action(action.get());
        }
        private void save() {
            Result result=access.updateStorage(draft());
            if (result.ok()) { ctx.toast(result); back(); } else { message=result.message(); error=true; ctx.toast(result); items.setInvalid(message.toLowerCase(java.util.Locale.ROOT).contains("item")); }
        }
        private void action(Result result) { ctx.toast(result); if (result.ok()) back(); else { message=result.message(); error=true; } }
        @Override public List<Widget> children() { return List.of(cancel,save,form); }
        @Override protected void layout() {
            int p=theme.px(Theme.PAD), ch=theme.px(Theme.CONTROL_H);
            cancel.setBounds(x+p,y+p,cancel.preferredWidth(),ch);
            save.setBounds(x+w-p-save.preferredWidth(),y+p,save.preferredWidth(),ch);
            int top=y+p+ch+theme.px(12);
            form.setBounds(x+p,top,w-2*p,Math.max(0,y+h-top-p));
        }
        @Override public void draw(Canvas c, Frame f) {
            cancel.draw(c,f); save.draw(c,f); form.draw(c,f);
        }
        final class Form extends Widget {
            private float offset;
            private int total;
            Form() { super(StoragePanel.this.theme); }
            @Override public List<Widget> children() { return List.of(name,role,match,items,first,second,nextArea,dropArea,up,down,merge,remove); }
            @Override protected void layout() {
                int gap=theme.px(8), ch=theme.px(Theme.CONTROL_H), label=theme.px(20), half=(w-gap)/2;
                int top=y-Math.round(offset);
                name.setBounds(x,top+label,half,ch); role.setBounds(x+half+gap,top+label,w-half-gap,ch);
                top+=label+ch+theme.px(16);
                match.setBounds(x,top+label,Math.min(w,theme.px(300)),ch);
                top+=label+ch+gap; items.setBounds(x,top,w,ch);
                top+=ch+theme.px(16);
                first.setBounds(x,top+label,half,ch); second.setBounds(x+half+gap,top+label,w-half-gap,ch);
                top+=label+ch+gap;
                nextArea.setBounds(x,top,nextArea.preferredWidth(),ch);
                dropArea.setBounds(nextArea.x()+nextArea.w()+gap,top,dropArea.preferredWidth(),ch);
                top+=ch+theme.px(48);
                up.setBounds(x,top,half,ch); down.setBounds(x+half+gap,top,w-half-gap,ch);
                top+=ch+gap;
                merge.setBounds(x,top,half,ch); remove.setBounds(x+half+gap,top,w-half-gap,ch);
                total=top+ch+theme.px(8)-y+Math.round(offset);
            }
            @Override public boolean scroll(float mx,float my,float dy) {
                if (!contains(mx,my)) return false;
                ctx.blur(); offset=Math.max(0,Math.min(Math.max(0,total-h),offset-dy*theme.px(32))); layout(); return true;
            }
            @Override public void tick() {
                super.tick();
                Widget focus=ctx.focusedWidget();
                if (focus!=null && children().contains(focus)) {
                    if (focus.y()<y) { offset=Math.max(0,offset-(y-focus.y())); layout(); }
                    else if (focus.y()+focus.h()>y+h) { offset=Math.min(Math.max(0,total-h),offset+focus.y()+focus.h()-y-h); layout(); }
                }
                nextArea.setEnabled(regions.size()>1); dropArea.setEnabled(regions.size()>1);
            }
            @Override public void draw(Canvas c,Frame f) {
                layout(); c.pushClip(x,y,w,h);
                try {
                    for (Widget child:children()) child.draw(c,f);
                    label(c,"Name",name); label(c,"Use for",role); label(c,"Items allowed here",match);
                    label(c,"Area "+(region+1)+" of "+regions.size()+" · first corner",first); label(c,"Second corner",second);
                    float by=nextArea.y()+nextArea.h()+theme.pxf(20);
                    c.text(c.ellipsize(message,theme.small,w),x,by,theme.small,theme.color(error ? Theme.DANGER : Theme.TEXT_2));
                    if (total>h) c.text("Scroll for more",x+w-theme.pxf(100),y+h-theme.pxf(4),theme.small,theme.color(Theme.TEXT_3));
                } finally { c.popClip(); }
            }
            private void label(Canvas c,String text,Widget field) { c.text(c.ellipsize(text,theme.small,field.w()),field.x(),field.y()-theme.pxf(5),theme.small,theme.color(Theme.TEXT_2)); }
        }
    }
}
