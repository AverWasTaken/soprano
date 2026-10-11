package adris.altoclef.ui.gui.kit;

// static faces keep awt and the game's atlas on the same font metrics
public enum Weight {
    LIGHT("Roboto-Light.ttf"),
    REGULAR("Roboto-Regular.ttf"),
    MEDIUM("Roboto-Medium.ttf"),
    SEMIBOLD("Roboto-Bold.ttf");

    public final String file;

    Weight(String file) {
        this.file = file;
    }

    public static final String RESOURCE_DIR = "/adris/altoclef/ui/gui/font/";
}
