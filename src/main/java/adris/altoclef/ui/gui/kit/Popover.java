package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// the small card a pick tile opens: a title, one control, "Back to default". it sits in the shell's one overlay slot,
// so it owns its focus and keys (the shell hands it every key and char while it is open):
//   Tab cycles its own controls, Enter commits the draft and closes, Escape drops the draft and closes,
//   a click outside or anything else that closes it (tab switch, scroll, the tile moving) commits the draft if it is
//   valid and quietly keeps the old value if it is not.
// a swatch, an option, -/+ and Back to default are commits of their own the moment you click them, like the mock.
// no Dropdown in here ever: it would take the overlay slot and close us. build one with color(), number(), choice(),
// text() or key(), then open()
public final class Popover extends Control {

    // the mock's eight. names are what a colour tile says in its corner
    public static final int[] PALETTE = {0xD9182B, 0xFF9F0A, 0xFFD60A, 0x30D158, 0x63E6E2, 0x4DA3FF, 0xC77DFF, 0xFFFFFF};
    public static final String[] PALETTE_NAMES = {"Red", "Orange", "Yellow", "Green", "Teal", "Blue", "Purple", "White"};

    static final int WIDTH = 232;
    static final int PAD = 12;
    static final int TITLE_H = 16;
    static final int TITLE_BELOW = 10;
    static final int RESET_ABOVE = 8;

    // "Red" for a palette colour, "#3A7BD5" for anything else
    public static String colourName(int rgb) {
        int c = rgb & 0xFFFFFF;
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == c) {
                return PALETTE_NAMES[i];
            }
        }
        return String.format("#%06X", c);
    }

    // ----- factories. anchor is what it hangs under (a Tile in practice), title is the setting's name

    public static Popover color(ShellContext ctx, Widget anchor, String title, IntSupplier get, IntConsumer commit, int def) {
        Popover p = new Popover(ctx, anchor, title);
        p.setBody(p.new ColourBody(get, commit, def & 0xFFFFFF));
        return p;
    }

    // validate may be null; it sees the trimmed text after it parsed and returns null or a sentence
    public static Popover number(ShellContext ctx, Widget anchor, String title, NumberField.NumberFormat format,
                                 Supplier<String> get, Consumer<String> commit, String def, Function<String, String> validate) {
        Popover p = new Popover(ctx, anchor, title);
        p.setBody(p.new NumberBody(format, get, commit, def, validate));
        return p;
    }

    // options are the words shown, get and commit speak in indexes into them
    public static Popover choice(ShellContext ctx, Widget anchor, String title, List<String> options,
                                 IntSupplier get, IntConsumer commit, int def) {
        Popover p = new Popover(ctx, anchor, title);
        p.setBody(p.new ChoiceBody(options, get, commit, def));
        return p;
    }

    public static Popover text(ShellContext ctx, Widget anchor, String title, Supplier<String> get, Consumer<String> commit,
                               String def, Function<String, String> validate) {
        Popover p = new Popover(ctx, anchor, title);
        p.setBody(p.new TextBody(get, commit, def, validate));
        return p;
    }

    // -1 is unbound. commit gets the new key, or -1 when backspace cleared it
    public static Popover key(ShellContext ctx, Widget anchor, String title, KeyNames names, IntSupplier get,
                              IntConsumer commit, int def) {
        Popover p = new Popover(ctx, anchor, title);
        p.setBody(p.new KeyBody(names, get, commit, def));
        return p;
    }

    // ----- the card

    private final ShellContext ctx;
    private final Widget anchor;
    private final String title;
    private final TextStyle titleStyle;
    private final Button reset;
    private final List<Widget> kids = new ArrayList<>();
    private final Spring reveal = new Spring(0f);
    private final FadeCanvas fade = new FadeCanvas();
    private Body body;
    private Widget focusChild;
    private Runnable onClosed;
    private boolean opened;
    private boolean done;
    private boolean started;
    private boolean down = true;
    private int ax;
    private int ay;
    private int aw;
    private int ah;
    private TileGrid anchorGrid;
    // a key that started a capture in here is still down, its repeats are not the key you are binding
    private int heldKey = -2;

    private Popover(ShellContext ctx, Widget anchor, String title) {
        super(ctx.theme());
        this.ctx = ctx;
        this.anchor = anchor;
        this.title = title == null ? "" : title;
        titleStyle = new TextStyle(Weight.SEMIBOLD, theme.px(13));
        reset = new ResetButton(theme, () -> {
            body.resetToDefault();
            body.refresh();
        });
        visible = false;
    }

    private void setBody(Body b) {
        body = b;
        kids.clear();
        kids.add(b);
        kids.add(reset);
    }

    // runs once after it closed, whichever way. the caller refreshes the tile from here
    public Popover onClosed(Runnable r) {
        onClosed = r;
        return this;
    }

    public Widget anchor() {
        return anchor;
    }

    public boolean isOpen() {
        return opened && !done && visible;
    }

    public void open() {
        if (opened) {
            return;
        }
        opened = true;
        body.refresh();
        ax = anchor.x();
        ay = anchor.y();
        aw = anchor.w();
        ah = anchor.h();
        place();
        if (anchor instanceof Tile t) {
            t.setOpen(true);
            anchorGrid = t.owner;
            if (anchorGrid != null) {
                anchorGrid.popoverOpened(this);
            }
        }
        ctx.openOverlay(this, () -> end(true));
        Widget first = body.firstFocus();
        if (first != null) {
            focusChild(first);
        }
    }

    // like a click outside: keep a valid draft, close
    public void dismiss() {
        end(true);
        closeSlot();
    }

    // escape: drop the draft, close
    public void cancel() {
        body.discard();
        end(false);
        closeSlot();
    }

    // enter: commit or stay open showing what is wrong
    public void finish() {
        if (!body.commitDraft()) {
            return;
        }
        end(false);
        closeSlot();
    }

    private void closeSlot() {
        // only while we are the overlay: a popover that was already replaced must not close the new one
        if (visible) {
            ctx.closeOverlay();
        }
    }

    // the one exit everything goes through, so the draft is handled exactly once
    private void end(boolean keepDraft) {
        if (done) {
            return;
        }
        done = true;
        if (keepDraft) {
            body.commitIfValid();
        }
        if (focusChild != null) {
            focusChild.setFocused(false);
            focusChild = null;
        }
        if (anchor instanceof Tile t) {
            t.setOpen(false);
        }
        if (onClosed != null) {
            Safe.run("popover closed", onClosed);
        }
    }

    // ----- geometry. under the anchor, left edges lined up, above it when the bottom is too close

    private int innerW() {
        return w - 2 * theme.px(PAD);
    }

    private int heightFor() {
        return theme.px(PAD) + theme.px(TITLE_H) + theme.px(TITLE_BELOW) + body.heightPx()
                + theme.px(RESET_ABOVE) + theme.px(Theme.CONTROL_H) + theme.px(PAD);
    }

    private float[] region() {
        if (anchor instanceof Tile t && t.owner != null) {
            TileGrid g = t.owner;
            return new float[]{g.x(), g.y(), g.w(), g.h()};
        }
        return new float[]{Integer.MIN_VALUE / 4f, Integer.MIN_VALUE / 4f, Integer.MAX_VALUE / 2f, Integer.MAX_VALUE / 2f};
    }

    private void place() {
        int pw = theme.px(WIDTH);
        float[] r = region();
        float margin = theme.px(8);
        float gap = theme.px(6);
        // a long option list in a short panel gives up rows (it scrolls) before the card gives up the panel
        body.fitTo((int) Math.min(Integer.MAX_VALUE / 4f, r[3] - 2 * margin - (heightFor() - body.heightPx())));
        int ph = heightFor();
        float left = Math.max(r[0] + margin, Math.min(r[0] + r[2] - margin - pw, ax));
        float below = ay + ah + gap;
        float above = ay - gap - ph;
        down = below + ph <= r[1] + r[3] - margin || above < r[1] + margin;
        float top = down ? below : above;
        // neither side has room: pin it inside, over the tile if it has to be
        top = Math.max(r[1] + margin, Math.min(r[1] + r[3] - margin - ph, top));
        setBounds(Math.round(left), Math.round(top), pw, ph);
    }

    @Override
    protected void layout() {
        if (body == null) {
            return;
        }
        int pad = theme.px(PAD);
        int bodyTop = y + pad + theme.px(TITLE_H) + theme.px(TITLE_BELOW);
        body.setBounds(x + pad, bodyTop, innerW(), body.heightPx());
        reset.setBounds(x + pad, bodyTop + body.heightPx() + theme.px(RESET_ABOVE), innerW(), theme.px(Theme.CONTROL_H));
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        if (done) {
            return;
        }
        // the tile moved under us (scroll, resize, a reflow) or left its grid: we would be pointing at the wrong thing
        boolean left = anchor instanceof Tile t && t.owner != anchorGrid;
        if (left || anchor.x() != ax || anchor.y() != ay || anchor.w() != aw || anchor.h() != ah || !anchor.visible()) {
            dismiss();
            return;
        }
        if (h != heightFor()) {
            place();
        }
        if (!started) {
            started = true;
            reveal.set(1f, now, Theme.ANIM);
        }
        reset.setEnabled(!body.isDefault());
        // enter on a dim button would do nothing forever, so focus goes back into the body
        if (focusChild == reset && !reset.enabled()) {
            focusChild(body.firstFocus());
        }
        float a = reveal.value(now);
        Canvas g = a >= 0.999f ? c : fade.reset(c, a, (1f - a) * theme.px(6) * (down ? -1 : 1));
        theme.floating(g, x, y, w, h, theme.pxf(Theme.CARD_RADIUS));
        int pad = theme.px(PAD);
        String t = theme.measurer.ellipsize(title, titleStyle, innerW() - theme.px(4));
        g.text(t, x + pad + theme.px(2), baseline(g, titleStyle, y + pad, theme.px(TITLE_H)), titleStyle, theme.color(Theme.TEXT));
        body.draw(g, f);
        reset.draw(g, f);
    }

    // ----- keys. the shell gives us all of them while we are open

    private List<Widget> focusables() {
        List<Widget> out = new ArrayList<>();
        body.collectFocusable(out);
        reset.collectFocusable(out);
        out.removeIf(w -> w == body && !body.focusable());
        return out;
    }

    void focusChild(Widget w) {
        if (w == focusChild) {
            return;
        }
        if (focusChild != null) {
            focusChild.setFocused(false);
        }
        focusChild = w;
        if (w != null) {
            w.setFocused(true);
        }
    }

    Widget focusChild() {
        return focusChild;
    }

    private void cycle(boolean back) {
        List<Widget> list = focusables();
        if (list.isEmpty()) {
            return;
        }
        int n = list.size();
        int i = list.indexOf(focusChild);
        int next = i < 0 ? (back ? n - 1 : 0) : (i + (back ? -1 : 1) + n) % n;
        focusChild(list.get(next));
    }

    // the shell forwards releases, a held key stops being swallowed once it is up
    public void keyUp(int key) {
        if (key == heldKey) {
            heldKey = -2;
        }
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (done) {
            return false;
        }
        // only its own key up frees it: a shift pressed in between must not let the held key's repeat through
        if (key == heldKey) {
            return true;
        }
        // a capture in progress owns every key, tab and esc included (esc cancels the capture, not us)
        if (body instanceof KeyBody kb && kb.capturing()) {
            kb.press(key, mods);
            // whatever ended the capture (esc, a bind) is still down; its repeat must not close the card
            heldKey = key;
            return true;
        }
        switch (key) {
            case Keys.TAB -> {
                cycle(Keys.shift(mods));
                return true;
            }
            case Keys.ESCAPE -> {
                cancel();
                return true;
            }
            default -> {
            }
        }
        if (Keys.isEnter(key)) {
            // enter on a button is that button. on the key cap it starts a capture. anywhere else it is "done"
            if (focusChild instanceof Button b) {
                b.keyDown(key, mods);
                return true;
            }
            if (body instanceof KeyBody kb && focusChild == kb.cap) {
                kb.cap.keyDown(key, mods);
                heldKey = key;
                return true;
            }
            finish();
            return true;
        }
        if (focusChild != null && focusChild != body && focusChild.keyDown(key, mods)) {
            if (body instanceof KeyBody kb && kb.capturing()) {
                heldKey = key;
            }
            return true;
        }
        body.key(key, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c) {
        if (done || focusChild == null) {
            return true;
        }
        focusChild.charTyped(c);
        return true;
    }

    // ----- mouse

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (done || !contains(mx, my)) {
            return false;
        }
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i).mouseDown(mx, my, button, mods)) {
                break;
            }
        }
        if (done) {
            return true;
        }
        // same as the shell's retarget, inside the card: a field you click takes the keys
        List<Widget> list = focusables();
        for (int i = list.size() - 1; i >= 0; i--) {
            Widget k = list.get(i);
            if (k instanceof FocusOnClick && k.contains(mx, my)) {
                focusChild(k);
                break;
            }
        }
        return true;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (done) {
            return false;
        }
        if (!contains(mx, my)) {
            // the page would scroll out from under us, so we go first (the mock does the same)
            dismiss();
            return true;
        }
        body.scroll(mx, my, dy);
        return true;
    }

    // ----- bodies

    abstract class Body extends Widget {

        Body() {
            super(Popover.this.theme);
        }

        abstract int heightPx();

        // the most room the body may take; only a list can shrink, everything else is already small
        void fitTo(int maxPx) {
        }

        // load the shown value from the getter, drafts go
        abstract void refresh();

        // enter: true when there is nothing to commit or it committed, false when the draft is bad (stays open)
        abstract boolean commitDraft();

        // a click outside: commit a good draft, keep the old value for a bad one, never complain
        void commitIfValid() {
            commitDraft();
        }

        void discard() {
        }

        abstract boolean isDefault();

        abstract void resetToDefault();

        Widget firstFocus() {
            List<Widget> l = new ArrayList<>();
            collectFocusable(l);
            l.remove(this);
            return l.isEmpty() ? (focusable() ? this : null) : l.get(0);
        }

        // keys the focused control did not want
        void key(int key, int mods) {
        }
    }

    // the error under a field, when there is one
    private static final int ERROR_H = 4 + 15;

    private void drawError(Canvas c, String err, float left, float top, float width) {
        if (err == null) {
            return;
        }
        String s = theme.measurer.ellipsize(err, theme.small, width);
        c.text(s, left, baseline(c, theme.small, top + theme.px(4), theme.px(15)), theme.small, theme.color(Theme.DANGER));
    }

    // a field without its box: the big number between -/+ reads as the value, not as a form. it opens "fresh": no
    // caret, and the first digit you type replaces the value instead of landing after it (a select-all would paint
    // a blue box over the number every time the card opens)
    private final class BigField extends TextField {
        private final TextStyle big;
        boolean fresh;
        boolean decimals;

        BigField(Theme t) {
            super(t, "", null);
            big = new TextStyle(Weight.SEMIBOLD, t.px(24), true);
        }

        @Override
        protected TextStyle textStyle() {
            return big;
        }

        @Override
        protected boolean centred() {
            return true;
        }

        @Override
        protected int leftInset() {
            return theme.px(4);
        }

        @Override
        protected int rightInset() {
            return theme.px(4);
        }

        @Override
        protected void drawBox(Canvas c, float r, int border) {
            if (invalid()) {
                c.strokeRoundRect(x, y, w, h, r, theme.color(Theme.DANGER));
            }
        }

        boolean allows(int ch) {
            return (ch >= '0' && ch <= '9') || ch == '-' || (ch == '.' && decimals);
        }

        @Override
        protected boolean showCaret() {
            return !fresh;
        }

        @Override
        public boolean charTyped(char ch) {
            if (fresh && focused && allows(ch)) {
                edit().selectAll();
            }
            fresh = false;
            return super.charTyped(ch);
        }

        // the game sends a key down before the char it types, so a plain key must not end "fresh" or the first digit
        // lands after the old value. deleting while fresh wipes it, like deleting a selection
        @Override
        public boolean keyDown(int key, int mods) {
            if (fresh && (key == Keys.BACKSPACE || key == Keys.DELETE) && focused) {
                edit().selectAll();
                fresh = false;
            } else if (Keys.ctrl(mods) || key == Keys.LEFT || key == Keys.RIGHT || key == Keys.HOME || key == Keys.END) {
                fresh = false;
            }
            return super.keyDown(key, mods);
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            fresh = false;
            return super.mouseDown(mx, my, button, mods);
        }
    }

    final class NumberBody extends Body {
        private final NumberField.NumberFormat format;
        private final Supplier<String> get;
        private final Consumer<String> commit;
        private final String def;
        private final Function<String, String> validate;
        private final BigField field;
        private final Button minus;
        private final Button plus;
        private final List<Widget> parts;
        private String committed = "";
        private String error;

        NumberBody(NumberField.NumberFormat format, Supplier<String> get, Consumer<String> commit, String def,
                   Function<String, String> validate) {
            this.format = format;
            this.get = get;
            this.commit = commit;
            this.def = def == null ? "" : def.trim();
            this.validate = validate;
            field = new BigField(theme);
            boolean decimals = format == NumberField.NumberFormat.FLOAT || format == NumberField.NumberFormat.DOUBLE;
            field.decimals = decimals;
            field.edit().setCharFilter(field::allows);
            field.setOnChange(s -> {
                error = null;
                field.setInvalid(false);
            });
            minus = new Button(theme, "", Button.Kind.SECONDARY, Icon.MINUS, () -> step(-1, minusMods()));
            plus = new Button(theme, "", Button.Kind.SECONDARY, Icon.PLUS, () -> step(1, plusMods()));
            // mouse things, like NumberField's nudges. up and down do the same from the keyboard
            minus.setFocusable(false);
            plus.setFocusable(false);
            parts = List.of(minus, field, plus);
        }

        private int minusMods() {
            return minus.lastMods();
        }

        private int plusMods() {
            return plus.lastMods();
        }

        private String problem(String t) {
            String p = NumberField.checkText(format, false, t);
            if (p != null || validate == null) {
                return p;
            }
            try {
                return validate.apply(t);
            } catch (Throwable e) {
                Safe.report("popover validate", e);
                return null;
            }
        }

        // one notch, committed right away like the mock's -/+. a step the validator refuses shows why and saves nothing
        void step(int dir, int mods) {
            String next = NumberField.nudged(format, false, field.text(), committed, dir, mods);
            field.setText(next);
            String p = problem(next);
            if (p != null) {
                error = p;
                field.setInvalid(true);
                return;
            }
            send(next);
            refresh();
        }

        private void send(String t) {
            Safe.run("popover commit", () -> commit.accept(t));
        }

        @Override
        int heightPx() {
            return theme.px(36) + (error != null ? theme.px(ERROR_H) : 0);
        }

        @Override
        protected void layout() {
            int bw = theme.px(40);
            int bh = theme.px(36);
            minus.setBounds(x, y, bw, bh);
            plus.setBounds(x + w - bw, y, bw, bh);
            field.setBounds(x + bw + theme.px(4), y, w - 2 * bw - theme.px(8), bh);
        }

        @Override
        void refresh() {
            String v = read(get);
            committed = v == null ? "" : v.trim();
            field.setText(committed);
            field.fresh = true;
            field.setInvalid(false);
            error = null;
        }

        @Override
        boolean commitDraft() {
            String t = field.text().trim();
            if (t.equals(committed)) {
                return true;
            }
            String p = problem(t);
            if (p != null) {
                error = p;
                field.setInvalid(true);
                return false;
            }
            send(t);
            committed = t;
            return true;
        }

        @Override
        boolean isDefault() {
            return same(committed, def);
        }

        @Override
        void resetToDefault() {
            send(def);
        }

        @Override
        Widget firstFocus() {
            return field;
        }

        @Override
        void key(int key, int mods) {
            if (key == Keys.UP) {
                step(1, mods);
            } else if (key == Keys.DOWN) {
                step(-1, mods);
            }
        }

        @Override
        public List<Widget> children() {
            return parts;
        }

        @Override
        public void draw(Canvas c, Frame f) {
            minus.draw(c, f);
            field.draw(c, f);
            plus.draw(c, f);
            drawError(c, error, x, y + theme.px(36), w);
        }
    }

    // a getter that throws reads as nothing rather than taking the card down
    private static String read(Supplier<String> get) {
        try {
            return get.get();
        } catch (Throwable e) {
            Safe.report("popover get", e);
            return "";
        }
    }

    private static int readInt(IntSupplier get, int fallback) {
        try {
            return get.getAsInt();
        } catch (Throwable e) {
            Safe.report("popover get", e);
            return fallback;
        }
    }

    // "12" and "12.0" are the same number, the reset button should not light up over a trailing zero
    static boolean same(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    final class TextBody extends Body {
        private final Supplier<String> get;
        private final Consumer<String> commit;
        private final String def;
        private final Function<String, String> validate;
        private final TextField field;
        private final List<Widget> parts;
        private String committed = "";
        private String error;

        TextBody(Supplier<String> get, Consumer<String> commit, String def, Function<String, String> validate) {
            this.get = get;
            this.commit = commit;
            this.def = def == null ? "" : def;
            this.validate = validate;
            field = new TextField(theme, "", null);
            field.setOnChange(s -> {
                error = null;
                field.setInvalid(false);
            });
            parts = List.of(field);
        }

        @Override
        int heightPx() {
            return theme.px(Theme.CONTROL_H) + (error != null ? theme.px(ERROR_H) : 0);
        }

        @Override
        protected void layout() {
            field.setBounds(x, y, w, theme.px(Theme.CONTROL_H));
        }

        @Override
        void refresh() {
            String v = read(get);
            committed = v == null ? "" : v;
            field.setText(committed);
            field.setInvalid(false);
            error = null;
        }

        @Override
        boolean commitDraft() {
            String t = field.text();
            if (t.equals(committed)) {
                return true;
            }
            String p = null;
            if (validate != null) {
                try {
                    p = validate.apply(t);
                } catch (Throwable e) {
                    Safe.report("popover validate", e);
                }
            }
            if (p != null) {
                error = p;
                field.setInvalid(true);
                return false;
            }
            Safe.run("popover commit", () -> commit.accept(t));
            committed = t;
            return true;
        }

        @Override
        boolean isDefault() {
            return committed.equals(def);
        }

        @Override
        void resetToDefault() {
            Safe.run("popover commit", () -> commit.accept(def));
        }

        @Override
        public List<Widget> children() {
            return parts;
        }

        @Override
        public void draw(Canvas c, Frame f) {
            field.draw(c, f);
            drawError(c, error, x, y + theme.px(Theme.CONTROL_H), w);
        }
    }

    final class ChoiceBody extends Body {
        private static final int ROW = 32;
        private static final int MAX_ROWS = 8;
        private final List<String> options;
        private final IntSupplier get;
        private final IntConsumer commit;
        private final int def;
        private final TextStyle onStyle;
        private int current = -1;
        private int hot = -1;
        private int first;
        private boolean keyed;

        ChoiceBody(List<String> options, IntSupplier get, IntConsumer commit, int def) {
            this.options = List.copyOf(options);
            this.get = get;
            this.commit = commit;
            this.def = def;
            onStyle = theme.label;
        }

        private int maxRows = MAX_ROWS;

        private int rows() {
            return Math.max(1, Math.min(maxRows, options.size()));
        }

        @Override
        void fitTo(int maxPx) {
            maxRows = Math.max(1, Math.min(MAX_ROWS, maxPx / theme.px(ROW)));
            ensureHot();
        }

        @Override
        int heightPx() {
            return rows() * theme.px(ROW);
        }

        @Override
        public boolean focusable() {
            return visible && !options.isEmpty();
        }

        @Override
        void refresh() {
            current = readInt(get, -1);
            hot = Math.max(0, Math.min(options.size() - 1, current));
            keyed = false;
            ensureHot();
        }

        private void ensureHot() {
            int rows = rows();
            if (hot < first) {
                first = hot;
            } else if (hot >= first + rows) {
                first = hot - rows + 1;
            }
            first = Math.max(0, Math.min(Math.max(0, options.size() - rows), first));
        }

        private void pick(int i) {
            if (i < 0 || i >= options.size()) {
                return;
            }
            Safe.run("popover commit", () -> commit.accept(i));
            refresh();
            hot = i;
        }

        @Override
        boolean commitDraft() {
            // arrows moved the highlight: enter (or a click outside) takes it
            if (keyed && hot != current && hot >= 0) {
                int i = hot;
                Safe.run("popover commit", () -> commit.accept(i));
                current = i;
            }
            return true;
        }

        @Override
        void discard() {
            keyed = false;
        }

        @Override
        boolean isDefault() {
            return current == def;
        }

        @Override
        void resetToDefault() {
            pick(def);
        }

        @Override
        void key(int key, int mods) {
            int n = options.size();
            if (n == 0) {
                return;
            }
            switch (key) {
                case Keys.DOWN -> hot = Math.min(n - 1, hot + 1);
                case Keys.UP -> hot = Math.max(0, hot - 1);
                case Keys.HOME -> hot = 0;
                case Keys.END -> hot = n - 1;
                case Keys.SPACE -> pick(hot);
                default -> {
                    return;
                }
            }
            keyed = true;
            ensureHot();
        }

        @Override
        public boolean keyDown(int key, int mods) {
            return false;
        }

        private int rowAt(float mx, float my) {
            if (!contains(mx, my)) {
                return -1;
            }
            int i = first + (int) ((my - y) / theme.px(ROW));
            return i >= 0 && i < options.size() ? i : -1;
        }

        @Override
        public Widget mouseMove(float mx, float my) {
            Widget d = super.mouseMove(mx, my);
            int i = rowAt(mx, my);
            if (i >= 0) {
                hot = i;
                keyed = false;
            }
            return d;
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            int i = button == 0 ? rowAt(mx, my) : -1;
            if (i < 0) {
                return false;
            }
            pick(i);
            return true;
        }

        @Override
        public boolean scroll(float mx, float my, float dy) {
            first = Math.max(0, Math.min(Math.max(0, options.size() - rows()), first - Math.round(Math.signum(dy))));
            return true;
        }

        @Override
        public void draw(Canvas c, Frame f) {
            int rh = theme.px(ROW);
            int is = theme.px(Theme.ICON);
            int padX = theme.px(8);
            c.pushClip(x, y, w, h);
            for (int k = 0; k < rows() && first + k < options.size(); k++) {
                int i = first + k;
                float ry = y + k * rh;
                boolean lit = i == hot && (keyed || hovered);
                if (lit) {
                    c.fillRoundRect(x, ry, w, rh, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.HOVER));
                }
                boolean on = i == current;
                TextStyle s = on ? onStyle : theme.body;
                String label = theme.measurer.ellipsize(options.get(i), s, w - 2f * padX - is - theme.px(8));
                c.text(label, x + padX, baseline(c, s, ry, rh), s, theme.color(Theme.TEXT));
                if (on) {
                    c.icon(Icon.CHECK, x + w - padX - is, ry + (rh - is) / 2f, is, theme.color(Theme.ACCENT_TEXT));
                }
            }
            c.popClip();
            if (options.size() > rows()) {
                float thumb = Math.max(theme.px(18), h * rows() / (float) options.size());
                float top = y + (h - thumb) * first / (float) (options.size() - rows());
                int bw = theme.px(3);
                c.fillRoundRect(x + w + theme.px(4), top, bw, thumb, bw / 2f, theme.color(Theme.HAIRLINE_STRONG));
            }
        }
    }

    final class ColourBody extends Body {
        private static final int DOT = 32;
        private static final int ROW_GAP = 10;
        private final IntSupplier get;
        private final IntConsumer commit;
        private final int def;
        private final TextField[] rgb = new TextField[3];
        private final List<Widget> parts;
        private int current;

        ColourBody(IntSupplier get, IntConsumer commit, int def) {
            this.get = get;
            this.commit = commit;
            this.def = def;
            String[] ph = {"R", "G", "B"};
            List<Widget> l = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                TextField t = new TextField(theme, ph[i], null);
                t.edit().setCharFilter(ch -> ch >= '0' && ch <= '9');
                t.setOnChange(s -> t.setInvalid(false));
                rgb[i] = t;
                l.add(t);
            }
            parts = List.copyOf(l);
        }

        private int swatchesH() {
            return theme.px(2) + 2 * theme.px(DOT) + theme.px(ROW_GAP) + theme.px(2);
        }

        @Override
        int heightPx() {
            return swatchesH() + theme.px(10) + theme.px(Theme.CONTROL_H);
        }

        @Override
        protected void layout() {
            int gap = theme.px(8);
            int fw = (w - 2 * gap) / 3;
            int fy = y + swatchesH() + theme.px(10);
            for (int i = 0; i < 3; i++) {
                int fx = x + i * (fw + gap);
                int fwi = i == 2 ? x + w - fx : fw;
                rgb[i].setBounds(fx, fy, fwi, theme.px(Theme.CONTROL_H));
            }
        }

        // the mock's grid: four 32 px dots spread across the row, 6 in from the sides
        private float dotX(int col) {
            float left = x + theme.px(6);
            float span = w - 2f * theme.px(6) - theme.px(DOT);
            return left + span * col / 3f;
        }

        private float dotY(int row) {
            return y + theme.px(2) + row * (theme.px(DOT) + theme.px(ROW_GAP));
        }

        private int swatchAt(float mx, float my) {
            int d = theme.px(DOT);
            for (int i = 0; i < PALETTE.length; i++) {
                float dx = dotX(i % 4);
                float dy = dotY(i / 4);
                if (mx >= dx && mx < dx + d && my >= dy && my < dy + d) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        void refresh() {
            current = readInt(get, 0) & 0xFFFFFF;
            int[] v = {(current >> 16) & 255, (current >> 8) & 255, current & 255};
            for (int i = 0; i < 3; i++) {
                rgb[i].setText(String.valueOf(v[i]));
                rgb[i].setInvalid(false);
            }
        }

        // the three boxes as one colour, or -1 with the bad boxes marked
        private int draft(boolean mark) {
            int out = 0;
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                String t = rgb[i].text().trim();
                int v = -1;
                if (!t.isEmpty() && t.length() <= 3) {
                    v = Integer.parseInt(t);
                }
                if (v < 0 || v > 255) {
                    ok = false;
                    if (mark) {
                        rgb[i].setInvalid(true);
                    }
                    continue;
                }
                out = (out << 8) | v;
            }
            return ok ? out : -1;
        }

        @Override
        boolean commitDraft() {
            int v = draft(true);
            if (v < 0) {
                return false;
            }
            if (v != current) {
                Safe.run("popover commit", () -> commit.accept(v));
                current = v;
            }
            return true;
        }

        @Override
        boolean isDefault() {
            return current == def;
        }

        @Override
        void resetToDefault() {
            Safe.run("popover commit", () -> commit.accept(def));
        }

        @Override
        public List<Widget> children() {
            return parts;
        }

        // up and down nudge the focused channel like the old colour row did: 1, 10 with shift, clamped to a byte.
        // it is a draft like typing, enter or a click outside saves the three as one colour
        @Override
        void key(int key, int mods) {
            if (key != Keys.UP && key != Keys.DOWN) {
                return;
            }
            for (TextField t : rgb) {
                if (!t.isFocused()) {
                    continue;
                }
                String s = t.text().trim();
                int cur = s.isEmpty() || s.length() > 3 ? 0 : Integer.parseInt(s);
                int step = (Keys.shift(mods) ? 10 : 1) * (key == Keys.UP ? 1 : -1);
                t.setText(String.valueOf(Math.max(0, Math.min(255, cur + step))));
                t.setInvalid(false);
            }
        }

        @Override
        public boolean mouseDown(float mx, float my, int button, int mods) {
            if (button == 0) {
                int i = swatchAt(mx, my);
                if (i >= 0) {
                    int c = PALETTE[i];
                    Safe.run("popover commit", () -> commit.accept(c));
                    refresh();
                    return true;
                }
            }
            return super.mouseDown(mx, my, button, mods);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            int d = theme.px(DOT);
            for (int i = 0; i < PALETTE.length; i++) {
                float dx = dotX(i % 4);
                float dy = dotY(i / 4);
                c.fillRoundRect(dx, dy, d, d, d / 2f, 0xFF000000 | PALETTE[i]);
                c.strokeRoundRect(dx, dy, d, d, d / 2f, Theme.fade(theme.color(Theme.TEXT), 0.22f));
                if (PALETTE[i] == current) {
                    // a 2 px accent ring 4 px out, the mock's ::after
                    float o = theme.px(4);
                    for (int k = 0; k < 2; k++) {
                        float e = o + k;
                        c.strokeRoundRect(dx - e, dy - e, d + 2 * e, d + 2 * e, d / 2f + e, theme.color(Theme.ACCENT));
                    }
                }
            }
            for (TextField t : rgb) {
                t.draw(c, f);
            }
        }
    }

    final class KeyBody extends Body {
        private final IntSupplier get;
        private final IntConsumer commit;
        private final int def;
        final KeyCapture cap;
        private final List<Widget> parts;
        private int current = -1;

        KeyBody(KeyNames names, IntSupplier get, IntConsumer commit, int def) {
            this.get = get;
            this.commit = commit;
            this.def = def;
            // the cap asks its context for focus when a capture starts; inside the card that is our focus, not the
            // shell's (shell focus on a widget nobody draws would be a ghost)
            cap = new KeyCapture(theme, -1, names, this::bound, inner());
            parts = List.of(cap);
        }

        boolean capturing() {
            return cap.capturing();
        }

        void press(int key, int mods) {
            cap.keyDown(key, mods);
        }

        private void bound(int key) {
            Safe.run("popover commit", () -> commit.accept(key));
            current = key;
            // a key bound is the whole job; the shell swallows the rest of that press for us
            end(false);
            closeSlot();
        }

        @Override
        int heightPx() {
            return theme.px(Theme.CONTROL_H) + theme.px(ERROR_H);
        }

        @Override
        protected void layout() {
            cap.setBounds(x, y, w, theme.px(Theme.CONTROL_H));
        }

        @Override
        void refresh() {
            current = readInt(get, -1);
            cap.setKey(current);
        }

        @Override
        boolean commitDraft() {
            return true;
        }

        @Override
        boolean isDefault() {
            return current == def;
        }

        @Override
        void resetToDefault() {
            Safe.run("popover commit", () -> commit.accept(def));
        }

        @Override
        public List<Widget> children() {
            return parts;
        }

        @Override
        public void draw(Canvas c, Frame f) {
            cap.draw(c, f);
            String hint = cap.capturing() ? "Esc to stop. Backspace clears it." : "Click it, then press the new key.";
            hint = theme.measurer.ellipsize(hint, theme.small, w);
            c.text(hint, x, baseline(c, theme.small, y + theme.px(Theme.CONTROL_H) + theme.px(4), theme.px(15)),
                    theme.small, theme.color(Theme.TEXT_3));
        }
    }

    private ShellContext inner() {
        return new ShellContext() {
            @Override
            public Theme theme() {
                return ctx.theme();
            }

            @Override
            public Clipboard clipboard() {
                return ctx.clipboard();
            }

            @Override
            public void toast(Result result) {
                ctx.toast(result);
            }

            @Override
            public void toast(String line, String command) {
                ctx.toast(line, command);
            }

            @Override
            public void focus(Widget widget) {
                focusChild(widget);
            }

            @Override
            public void blur() {
                focusChild(null);
            }

            @Override
            public void openOverlay(Widget overlay) {
            }

            @Override
            public void closeOverlay() {
            }

            @Override
            public void requestClose() {
            }
        };
    }

    // ghost, full width, glyph and words on the left like the mock's gp-reset
    private static final class ResetButton extends Button {
        ResetButton(Theme theme, Runnable onClick) {
            super(theme, "Back to default", Kind.GHOST, Icon.RESET, onClick);
        }

        @Override
        protected void drawContent(Canvas c, int col, float hv) {
            float is = iconSize();
            float cx = x + theme.px(8);
            c.icon(Icon.RESET, cx, y + (h - is) / 2f, is, col);
            String s = theme.measurer.ellipsize(text, theme.label, w - theme.px(8) - is - theme.px(6) - theme.px(8));
            c.text(s, cx + is + theme.px(6), baseline(c, theme.label, y, h), theme.label, col);
        }
    }
}
