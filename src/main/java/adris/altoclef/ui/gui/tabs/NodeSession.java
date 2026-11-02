package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.model.GraphDraft;

// what the node editor keeps across a gui rebuild. a window resize across a scale step makes every tab again, and closing
// the screen makes a new screen, so none of them can hold this. the screen's facade is stateless and the draft only talks
// to it through an interface, so an old draft carries on fine with the new tab. tests get their own, see CommandsTab
final class NodeSession {

    static final NodeSession SHARED = new NodeSession();

    boolean nodesView;
    GraphDraft draft;
    String routineId;
    String selectedCard;
    String selectedWire;
    boolean paletteOpen;
    boolean viewSet;
    float panX;
    float panY;
    int zoomIndex;
}
