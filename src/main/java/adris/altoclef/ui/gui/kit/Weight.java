package adris.altoclef.ui.gui.kit;

// the three static inter files we ship. the variable font would be one file but awt handles the statics better
public enum Weight {
    REGULAR("Inter-Regular.ttf"),
    MEDIUM("Inter-Medium.ttf"),
    SEMIBOLD("Inter-SemiBold.ttf");

    public final String file;

    Weight(String file) {
        this.file = file;
    }

    public static final String RESOURCE_DIR = "/adris/altoclef/ui/gui/font/";
}
