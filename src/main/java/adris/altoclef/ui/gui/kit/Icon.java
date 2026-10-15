package adris.altoclef.ui.gui.kit;

// every glyph the gui needs. drawn as vector strokes by Icons into the atlas at the pixel size asked for, so no pngs
public enum Icon {
    GET,            // a parcel: cube with a tape stripe
    GOTO,           // a path from a green dot to a red one
    FOLLOW,         // a person with an arrow leading off to the right
    WAYPOINTS,      // flag
    HELP,           // question mark in a ring
    SETTINGS,       // two sliders
    STATS,          // three bars, ascending
    SCHEMATICS,     // a wall of bricks, what a schematic turns into
    WARNING,        // triangle with a bang
    SEARCH,         // magnifier
    CLOSE,          // x
    PLUS,
    MINUS,
    CHEVRON_DOWN,
    CHEVRON_RIGHT,
    CHECK,
    COPY,           // two overlapping squares
    RESET,          // counter clockwise arrow
    STOP,           // filled square
    KEY,            // keyboard key outline
    DOT,            // filled circle (busy)
    RING,           // hollow circle (idle)
    TRASH,
    PIN,            // my position
    PLAYER,         // head and shoulders
    ENTITY,         // paw
    BOAT, PARKOUR, BOLT, RUN, PICKAXE, BLOCK, WRENCH, BACKPACK, WATER, LADDER, EYE
}
