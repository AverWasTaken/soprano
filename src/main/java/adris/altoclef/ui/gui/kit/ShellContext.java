package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.Result;

// what a tab may ask of the chrome around it
public interface ShellContext {

    Theme theme();

    Clipboard clipboard();

    // shows the human line and, under it, the exact command that ran. a failed result shows in danger red
    void toast(Result result);

    void toast(String line, String command);

    void focus(Widget widget);

    void blur();

    // a dropdown or popover drawn above everything; the Shell closes it on an outside click or Esc
    void openOverlay(Widget overlay);

    // same, and onClose runs once when the shell takes it down for any reason: an outside click, an Esc it did not
    // take, a tab switch, another overlay, the gui going away. a Popover commits its draft from there. contexts that
    // never close anything on their own (test fakes) can ignore it
    default void openOverlay(Widget overlay, Runnable onClose) {
        openOverlay(overlay);
    }

    void closeOverlay();

    void requestClose();

    // a tab's own key capture changed the open key: the shell must treat it as the close key from now on and
    // swallow the press that is still held down so it does not close the panel through key repeat
    default void openKeyRebound(int key) {
    }
}
