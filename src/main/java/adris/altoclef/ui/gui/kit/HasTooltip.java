package adris.altoclef.ui.gui.kit;

// a widget that has something to say when you rest the pointer on it. the shell's TooltipLayer asks the innermost one
// under the pointer, every frame while it is up, so keep it cheap. null or an empty tooltip means nothing to show
public interface HasTooltip {

    Tooltip tooltip();
}
