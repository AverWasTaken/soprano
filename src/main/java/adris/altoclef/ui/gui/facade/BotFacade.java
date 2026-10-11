package adris.altoclef.ui.gui.facade;

import adris.altoclef.ui.gui.kit.Clipboard;

// everything the gui knows about the game, behind interfaces the preview and the tests can fake
public interface BotFacade {

    SettingsAccess settings();

    CommandsAccess commands();

    ItemsAccess items();

    WorldAccess world();

    StatusAccess status();

    KeyNames keyNames();

    Clipboard clipboard();
}
