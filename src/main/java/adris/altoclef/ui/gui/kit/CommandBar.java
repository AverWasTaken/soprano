package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.model.CommandHistory;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

// the help tab's command line, moved out of the tab into a strip over the status line so every tab has it. each
// button elsewhere is a chat command, this is where you type one yourself
public final class CommandBar extends Widget {

    public static final String IDLE_HINT = "Enter runs it. Up brings back the last one.";
    static final String PLACEHOLDER = "Run a command, like goto 100 64 -30";

    // a quiet # in front so it reads as chat, and a terminal style history on the arrow keys
    public final class Field extends TextField {
        private final float hashW;

        Field() {
            super(CommandBar.this.theme, PLACEHOLDER, s -> run());
            this.hashW = theme.measurer.measure("#", theme.body);
        }

        // the # sits where the text would, and the text keeps the field's usual 10 after it (the mock's spacing)
        @Override
        protected int leftInset() {
            return theme.px(10) + Math.round(hashW) + theme.px(6 + 10);
        }

        @Override
        protected void drawAdornments(Canvas c, Frame f) {
            c.text("#", x + theme.px(10), baselineIn(c, theme.body, y, h), theme.body, theme.color(Theme.TEXT_3));
        }

        @Override
        public boolean keyDown(int key, int mods) {
            // esc leaves the bar and keeps what you typed, the plain field would first put back an empty line
            if (key == Keys.ESCAPE) {
                return false;
            }
            // glfw also reports caps lock and num lock in mods, those must not break the arrow keys
            if (focused && (mods & 15) == 0) {
                if (key == Keys.UP) {
                    recall(history.back(text()));
                    return true;
                }
                if (key == Keys.DOWN && history.browsing()) {
                    recall(history.forward(text()));
                    return true;
                }
            }
            return super.keyDown(key, mods);
        }

        // red only belongs to the line that failed, a different line has not failed yet
        private void recall(String line) {
            if (!line.equals(text())) {
                setText(line);
                setInvalid(false);
            }
        }

        // the # in front is already drawn, a second one would read as "# #goto"
        @Override
        public boolean charTyped(char c) {
            if (focused && c == '#' && text().isEmpty()) {
                return true;
            }
            return super.charTyped(c);
        }
    }

    private final CommandHistory history = new CommandHistory();
    private final Field field;
    private final Consumer<Result> toast;
    private Function<String, Result> runner;
    private Result last;
    private String lastLine = "";
    // the field gives the result text whatever room it does not need, worked out again only when the text changes
    private String placedFor;
    private int placedW = -1;

    public CommandBar(Theme theme, Consumer<Result> toast) {
        super(theme);
        this.toast = toast;
        field = new Field();
        field.setOnChange(s -> {
            field.setInvalid(false);
            history.reset();
        });
    }

    // the screen hands in the facade's run, the kit does not know there is a game
    public void setRunner(Function<String, Result> runner) {
        this.runner = runner;
    }

    // what the player typed, as the command registry wants it: no chat prefix, no stray spaces
    public static String cleanLine(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    void run() {
        String line = cleanLine(field.text());
        if (line.isEmpty()) {
            return;
        }
        Result r;
        try {
            r = runner == null ? null : runner.apply(line);
        } catch (Throwable t) {
            Safe.report("command run", t);
            r = null;
        }
        if (r == null) {
            r = Result.fail("Could not run that. Try it in chat with a # in front.", line);
        }
        last = r;
        lastLine = line;
        history.add(line);
        field.setInvalid(!r.ok());
        if (r.ok()) {
            field.setText("");
        }
        Result shown = r;
        Safe.run("command toast", () -> toast.accept(shown));
    }

    // the small line at the right end of the bar
    public String inlineText() {
        if (last == null) {
            return IDLE_HINT;
        }
        if (!last.ok()) {
            String m = last.message();
            return m == null || m.isBlank() ? "That did not work. Check the command and try again." : firstLine(m);
        }
        String m = last.message();
        String base = "Ran " + lastLine;
        if (m == null || m.isBlank() || m.startsWith("Ran ")) {
            return base;
        }
        return base + " · " + firstLine(m);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    // ----- layout and drawing

    // the right end lines up with the stop button under it, which stops short of the resize grip
    private int rightInset() {
        return theme.px(Theme.PAD + Theme.SPACE_3);
    }

    private int resultRoom() {
        String text = inlineText();
        if (!text.equals(placedFor) || placedW < 0) {
            int cap = Math.round(w * 0.4f);
            int need = (int) Math.ceil(theme.measurer.measure(text, theme.small));
            // a cut off hint is just noise, a narrow panel gives its room to the field. a result is news, it stays
            placedW = last == null && need > cap ? 0 : Math.min(cap, need);
            placedFor = text;
        }
        return placedW;
    }

    private void placeField() {
        int pad = theme.px(Theme.PAD);
        int fh = theme.px(28);
        int room = resultRoom();
        int fw = Math.max(theme.px(80), w - pad - rightInset() - (room > 0 ? theme.px(Theme.SPACE_3) + room : 0));
        int fy = y + 1 + (h - 1 - fh) / 2;
        if (field.x() != x + pad || field.y() != fy || field.w() != fw || field.h() != fh) {
            field.setBounds(x + pad, fy, fw, fh);
        }
    }

    @Override
    protected void layout() {
        placedW = -1;
        placeField();
    }

    @Override
    public List<Widget> children() {
        return List.of(field);
    }

    @Override
    public void draw(Canvas c, Frame f) {
        c.fillRect(x, y, w, 1, theme.color(Theme.HAIRLINE));
        placeField();
        field.draw(c, f);
        if (resultRoom() == 0) {
            return;
        }
        String text = inlineText();
        int col = last == null ? theme.color(Theme.TEXT_3) : last.ok() ? theme.color(Theme.ACCENT_TEXT) : theme.color(Theme.DANGER);
        float left = field.x() + field.w() + theme.px(Theme.SPACE_3);
        float room = Math.max(0f, x + w - rightInset() - left);
        String shown = theme.measurer.ellipsize(text, theme.small, room);
        // right aligned so the end of the line sits over the end of the stop button
        float tw = theme.measurer.measure(shown, theme.small);
        c.text(shown, x + w - rightInset() - tw, baselineIn(c, theme.small, y + 1, h - 1), theme.small, col);
    }

    private static float baselineIn(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // ----- for the shell, tests and previews

    public Field field() {
        return field;
    }

    public Result lastResult() {
        return last;
    }

    public CommandHistory history() {
        return history;
    }
}
